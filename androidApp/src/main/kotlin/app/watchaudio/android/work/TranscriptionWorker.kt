package app.watchaudio.android.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph
import app.watchaudio.app.ModelState
import kotlinx.coroutines.sync.withLock

/**
 * Transcribes pending chunks until none are left or WorkManager stops the worker. Progress lives
 * in the database, so a stopped run loses at most the chunk in progress (it is re-queued).
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        graph.startup.join()
        if (!graph.models.isReady()) {
            // ModelManager requests a run as soon as the model arrives; retry only while it is on its way.
            Log.i(TAG, "speech model not ready; skipping")
            return if (graph.models.state.value is ModelState.Downloading) Result.retry() else Result.success()
        }
        // The one-off and the periodic worker may overlap; one pipeline run at a time.
        val result = graph.transcriptionLock.withLock {
            try {
                graph.pipeline.processPending { !isStopped }
            } finally {
                graph.transcriber.release()
            }
        }
        Log.i(TAG, "transcribed ${result.transcribed}, failed ${result.failed}")
        if (!isStopped && graph.hasPendingChunks()) graph.scheduler.requestNow()
        return Result.success()
    }

    private companion object {
        const val TAG = "TranscriptionWorker"
    }
}
