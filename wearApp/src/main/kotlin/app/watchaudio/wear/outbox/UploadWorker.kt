package app.watchaudio.wear.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.watchaudio.wear.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/** Runs [OutboxUploader.drain] from WorkManager so uploads continue without the recorder. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val outcome = AppGraph.of(applicationContext).uploader.drain()) {
        // Sent everything; come back to resend if ACKs do not arrive.
        is OutboxUploader.Outcome.Done -> if (outcome.awaitingAck > 0) Result.retry() else Result.success()
        is OutboxUploader.Outcome.Failed -> Result.retry()
        // No phone nearby: retrying on a timer would only drain the battery. The reconnect
        // (capability change), the next closed chunk or the periodic run will try again.
        is OutboxUploader.Outcome.NoPhone, OutboxUploader.Outcome.Disabled -> Result.success()
    }
}

object UploadScheduler {
    private const val UNIQUE_NOW = "outbox-upload"
    private const val UNIQUE_PERIODIC = "outbox-upload-periodic"

    /** Immediate in-process attempt plus a WorkManager job as the durable fallback. */
    fun uploadNow(context: Context) {
        val graph = AppGraph.of(context)
        graph.scope.launch {
            graph.uploader.drain()
            graph.refreshPendingCount()
        }
        enqueue(context)
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a running/backing-off job already drains everything that is due.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Safety net: everything un-ACKed is retried at least every ~15 minutes. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
