package app.watchaudio.android.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.pipeline.TranscriptionRun
import app.watchaudio.core.pipeline.TranscriptionRun.FollowUp

/**
 * Runs the shared [TranscriptionRun] from WorkManager and maps its [FollowUp] to the
 * [TranscriptionScheduler]: transcribes pending chunks until none are left, the battery/thermal
 * policy pauses (#75; checked between chunks, then a later run is scheduled) or WorkManager stops
 * the worker, then applies the audio retention setting. Progress lives in the database, so a
 * stopped run loses at most the chunk in progress (it is re-queued). When nothing waits for
 * transcription, the run also embeds transcripts for semantic search (#24), and its own follow-up
 * (more to embed while charging, or older chunks waiting for the charger) is scheduled here too.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        graph.startup.join()
        val userRequested = inputData.getBoolean(TranscriptionScheduler.KEY_USER_REQUESTED, false)
        val report = graph.transcriptionRun.run(
            worker = "transcription",
            userRequested = userRequested,
            observer = ChunkLog,
            shouldContinue = { !isStopped },
            startDetail = " attempt=${runAttemptCount + 1}",
            stopDetail = ::stopDetail,
        )
        report.transcription?.let { outcome ->
            graph.transcriptionPause.value = outcome.pause?.reason
            Log.i(
                TAG,
                "transcribed ${outcome.result.transcribed}, failed ${outcome.result.failed}" +
                    outcome.pause?.let { ", paused: ${it.reason.code}" }.orEmpty(),
            )
        } ?: Log.i(TAG, "speech model not ready; skipping transcription")
        when (val retention = report.retention) {
            is TranscriptionRun.Retention.Purged -> retention.result.takeIf { it.purgedChunks > 0 }?.let {
                Log.i(TAG, "deleted audio of ${it.purgedChunks} chunks (${it.freedBytes} bytes)")
            }
            is TranscriptionRun.Retention.Failed -> Log.w(TAG, "audio retention failed", retention.error)
        }
        if (report.followUp == FollowUp.None) {
            when (report.embedding?.followUp) {
                EmbeddingStep.FollowUp.RunAgain -> graph.scheduler.requestNow()
                EmbeddingStep.FollowUp.OnCharge -> graph.scheduler.requestOnCharge()
                EmbeddingStep.FollowUp.None, null -> Unit
            }
        }
        report.embedding?.takeIf { it.embeddedChunks > 0 || it.failedChunks > 0 }?.let {
            Log.i(TAG, "embedded ${it.embeddedChunks} chunks (${it.passages} passages), failed ${it.failedChunks}")
        }
        return when (val followUp = report.followUp) {
            FollowUp.None, FollowUp.Interrupted -> Result.success()
            FollowUp.RunAgain -> {
                graph.scheduler.requestNow()
                Result.success()
            }
            is FollowUp.Paused -> {
                // A delayed retry (warm phone, hourly cap) and/or a run once plugged in.
                followUp.retryAfterMs?.let { graph.scheduler.requestLater(it) }
                if (followUp.onCharge) graph.scheduler.requestOnCharge()
                Result.success()
            }
            // ModelManager requests a run as soon as the model arrives; retry only while it is on its way.
            is FollowUp.WaitForModel -> if (followUp.downloading) Result.retry() else Result.success()
        }
    }

    /** Why WorkManager stopped this worker, for the `worker_stop` line. */
    private fun stopDetail(): String = when {
        !isStopped -> ""
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> " stopped=true stop_reason=$stopReason"
        else -> " stopped=true"
    }

    /**
     * One logcat line per chunk, for `adb logcat` (ids and timings only, never transcript text). The
     * diagnostics log gets its own lines from [TranscriptionRun]; scripts read those, not these.
     */
    private object ChunkLog : TranscriptionPipeline.Observer {
        override fun started(chunkId: String) {
            Log.i(TAG, "transcribing chunk $chunkId")
        }

        override fun finished(outcome: TranscriptionPipeline.ChunkOutcome) {
            val verb = if (outcome.transcribed) "transcribed" else "failed"
            Log.i(TAG, "$verb chunk ${outcome.chunkId} in ${outcome.elapsedMs} ms")
        }
    }

    internal companion object {
        const val TAG = "TranscriptionWorker"
    }
}
