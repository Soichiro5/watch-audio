package app.watchaudio.android.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph
import app.watchaudio.app.ModelState
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.DiagnosticsPipelineObserver
import app.watchaudio.core.pipeline.TranscriptionPipeline
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * Transcribes pending chunks until none are left or WorkManager stops the worker, then applies the
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
            val stop = if (isStopped) " stopped=true stop_reason=$stopReason" else ""
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
        // The one-off and the periodic worker may overlap; one pipeline run at a time.
        val result = graph.transcriptionLock.withLock {
            try {
                graph.pipeline.processPending(ChunkLog(diagnostics)) { !isStopped }
            } finally {
                graph.transcriber.release()
            }
        }
        Log.i(TAG, "transcribed ${result.transcribed}, failed ${result.failed}")
        report(" transcribed=${result.transcribed} failed=${result.failed}")
        applyRetention(graph, diagnostics)
        if (!isStopped && graph.hasPendingChunks()) graph.scheduler.requestNow()
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
