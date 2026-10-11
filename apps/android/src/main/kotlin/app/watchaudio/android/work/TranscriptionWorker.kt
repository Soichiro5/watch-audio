package app.watchaudio.android.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph
import app.watchaudio.app.ModelState
import app.watchaudio.app.settings.loadSettings
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.DiagnosticsPipelineObserver
import app.watchaudio.core.pipeline.PauseReason
import app.watchaudio.core.pipeline.TranscriptionPipeline
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * Transcribes pending chunks until none are left, the battery/thermal policy pauses (#75; checked
 * between chunks, then a later run is scheduled) or WorkManager stops the worker, then applies the
 * audio retention setting. Progress lives in the database, so a stopped run loses at most the
 * chunk in progress (it is re-queued).
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val diagnostics = graph.diagnostics
        diagnostics.log(DiagnosticEvents.WORKER_START, "worker=transcription attempt=${runAttemptCount + 1}")
        var summary = ""
        try {
            return run(graph, diagnostics) { summary = it }
        } finally {
            val stop = when {
                !isStopped -> ""
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> " stopped=true stop_reason=$stopReason"
                else -> " stopped=true"
            }
            diagnostics.log(DiagnosticEvents.WORKER_STOP, "worker=transcription$summary$stop")
        }
    }

    /** [report] receives the run's outcome for the `worker_stop` line. */
    private suspend fun run(graph: AppGraph, diagnostics: Diagnostics, report: (String) -> Unit): Result {
        graph.startup.join()
        if (!graph.models.isReady()) {
            // ModelManager requests a run as soon as the model arrives; retry only while it is on its way.
            Log.i(TAG, "speech model not ready; skipping transcription")
            report(" skipped=model_not_ready")
            applyRetention(graph, diagnostics)
            return if (graph.models.state.value is ModelState.Downloading) Result.retry() else Result.success()
        }
        val chargingOnly = graph.store.loadSettings().transcribeOnlyWhileCharging
        val userRequested = inputData.getBoolean(TranscriptionScheduler.KEY_USER_REQUESTED, false)
        // The one-off and the periodic worker may overlap; one pipeline run at a time.
        val outcome = graph.transcriptionLock.withLock {
            try {
                // The battery/thermal policy (#75) is asked before every chunk: a pause stops between chunks.
                graph.gatedTranscription().run(chargingOnly, userRequested, ChunkLog(diagnostics)) { !isStopped }
            } finally {
                graph.transcriber.release()
            }
        }
        val result = outcome.result
        val pause = outcome.pause
        graph.transcriptionPause.value = pause?.reason
        Log.i(TAG, "transcribed ${result.transcribed}, failed ${result.failed}" + pause?.let { ", paused: ${it.reason.code}" }.orEmpty())
        report(" transcribed=${result.transcribed} failed=${result.failed}" + pause?.let { " paused=${it.reason.code}" }.orEmpty())
        applyRetention(graph, diagnostics)
        if (!isStopped && graph.hasPendingChunks()) {
            if (pause == null) {
                graph.scheduler.requestNow()
            } else {
                // A delayed retry (warm phone, hourly cap) and/or a run once plugged in.
                pause.retryAfterMs?.let { graph.scheduler.requestLater(it) }
                if (pause.reason != PauseReason.THERMAL) graph.scheduler.requestOnCharge()
            }
        }
        return Result.success()
    }

    /** Deletes audio past the user's retention setting; only transcribed chunks lose audio (#33). */
    private suspend fun applyRetention(graph: AppGraph, diagnostics: Diagnostics) {
        try {
            val purged = graph.storage.applyRetention { !isStopped }
            if (purged.purgedChunks > 0) {
                Log.i(TAG, "deleted audio of ${purged.purgedChunks} chunks (${purged.freedBytes} bytes)")
                diagnostics.log(DiagnosticEvents.RETENTION_PURGE, "chunks=${purged.purgedChunks} bytes=${purged.freedBytes}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "audio retention failed", e)
            diagnostics.log(DiagnosticEvents.RETENTION_PURGE, "failed=true error=${DiagnosticsLog.typeName(e)}")
        }
    }

    /**
     * One line per chunk: progress for `adb logcat` and the diagnostics log (ids and timings only,
     * never transcript text).
     */
    private class ChunkLog(diagnostics: Diagnostics) : TranscriptionPipeline.Observer {
        private val diagnosticsObserver = DiagnosticsPipelineObserver(diagnostics)

        override fun started(chunkId: String) {
            Log.i(TAG, "transcribing chunk $chunkId")
            diagnosticsObserver.started(chunkId)
        }

        override fun finished(outcome: TranscriptionPipeline.ChunkOutcome) {
            val verb = if (outcome.transcribed) "transcribed" else "failed"
            Log.i(TAG, "$verb chunk ${outcome.chunkId} in ${outcome.elapsedMs} ms")
            diagnosticsObserver.finished(outcome)
        }
    }

    internal companion object {
        const val TAG = "TranscriptionWorker"
    }
}
