package app.watchaudio.android.asr

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph
import app.watchaudio.app.Strings
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads the speech models (see [ModelManager]). Runs as a foreground worker when it can,
 * because the archive can take longer than WorkManager's 10-minute limit for normal workers.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        graph.startup.join()
        if (graph.models.isReady()) return Result.success()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // Started while the app is in the background (Android 12+): continue as a normal worker.
            Log.i(TAG, "running without foreground service", e)
        }
        return try {
            graph.models.performDownload()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifications = NotificationManagerCompat.from(applicationContext)
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(Strings.MODEL_DOWNLOAD_CHANNEL)
                .build(),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(Strings.MODEL_DOWNLOAD_NOTIFICATION_TITLE)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val TAG = "ModelDownloadWorker"
        const val CHANNEL_ID = "model-download"
        const val NOTIFICATION_ID = 1001
        const val MAX_ATTEMPTS = 5
    }
}
