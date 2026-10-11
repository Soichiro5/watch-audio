package app.watchaudio.android.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph
import app.watchaudio.app.ModelState
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
        graph.startup.join()
        if (!graph.models.isReady()) {
            // ModelManager requests a run as soon as the model arrives; retry only while it is on its way.
            Log.i(TAG, "speech model not ready; skipping transcription")
            applyRetention(graph)
            return if (graph.models.state.value is ModelState.Downloading) Result.retry() else Result.success()
        }
        // The one-off and the periodic worker may overlap; one pipeline run at a time.
        val result = graph.transcriptionLock.withLock {
            try {
                graph.pipeline.processPending(ChunkLog) { !isStopped }
            } finally {
                graph.transcriber.release()
            }
        }
        Log.i(TAG, "transcribed ${result.transcribed}, failed ${result.failed}")
        applyRetention(graph)
        if (!isStopped && graph.hasPendingChunks()) graph.scheduler.requestNow()
        return Result.success()
    }

    /** Deletes audio past the user's retention setting; only transcribed chunks lose audio (#33). */
    private suspend fun applyRetention(graph: AppGraph) {
        try {
            val purged = graph.storage.applyRetention { !isStopped }
            if (purged.purgedChunks > 0) Log.i(TAG, "deleted audio of ${purged.purgedChunks} chunks (${purged.freedBytes} bytes)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "audio retention failed", e)
        }
    }

    /** One line per chunk: progress for `adb logcat` (ids only, never transcript text). */
    private object ChunkLog : TranscriptionPipeline.Observer {
        override fun started(chunkId: String) {
            Log.i(TAG, "transcribing chunk $chunkId")
        }

        override fun finished(chunkId: String, transcribed: Boolean) {
            Log.i(TAG, if (transcribed) "transcribed chunk $chunkId" else "failed chunk $chunkId")
        }
    }

    internal companion object {
        const val TAG = "TranscriptionWorker"
    }
}
