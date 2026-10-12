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
import app.watchaudio.core.settings.loadSettings
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads the speech models (see [ModelManager]), or with [KEY_ENGLISH] the English model while
 * 「英語も文字起こしする」 is on (#81). Runs as a foreground worker when it can, because the files
 * can take longer than WorkManager's 10-minute limit for normal workers.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val english get() = inputData.getBoolean(KEY_ENGLISH, false)

    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        graph.startup.join()
        val models = graph.models
        if (english) {
            if (models.isEnglishReady() || !graph.store.loadSettings().transcribeEnglish) return Result.success()
        } else if (models.isReady()) {
            return Result.success()
        }
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // Started while the app is in the background (Android 12+): continue as a normal worker.
            Log.i(TAG, "running without foreground service", e)
        }
        return try {
            if (english) models.performEnglishDownload() else models.performDownload()
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
            .setContentTitle(if (english) Strings.ENGLISH_MODEL_DOWNLOAD_NOTIFICATION_TITLE else Strings.MODEL_DOWNLOAD_NOTIFICATION_TITLE)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val id = if (english) ENGLISH_NOTIFICATION_ID else NOTIFICATION_ID
        return ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /** Input: download the English model instead of the speech models. */
        const val KEY_ENGLISH = "english"
        private const val TAG = "ModelDownloadWorker"
        private const val CHANNEL_ID = "model-download"
        private const val NOTIFICATION_ID = 1001
        private const val ENGLISH_NOTIFICATION_ID = 1002
        private const val MAX_ATTEMPTS = 5
    }
}
