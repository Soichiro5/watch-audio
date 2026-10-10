package app.watchaudio.android.alert

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.watchaudio.android.MainActivity
import app.watchaudio.app.alert.GapAlertEvaluator
import app.watchaudio.app.logic.GapAlertDecision
import app.watchaudio.core.db.LocalStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phone-side "recording is not arriving" alert (#43). The rules live in the shared
 * [GapAlertEvaluator]; this class runs the checks and posts the notification:
 * - after every stored chunk the visible alert is cleared and a one-off [GapAlertWorker] is
 *   armed for when the next alert would be due (replacing the previous one),
 * - a periodic [GapAlertWorker] every 15 minutes (the WorkManager minimum) as a safety net,
 *   e.g. for alerts postponed by quiet hours or a timer the OS delayed.
 */
class GapAlertMonitor(
    context: Context,
    store: LocalStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val evaluator = GapAlertEvaluator(store)
    private val notifications = NotificationManagerCompat.from(app)
    private val workManager: WorkManager get() = WorkManager.getInstance(app)
    private val lock = Mutex()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<GapAlertWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A chunk was stored: the current gap (if any) is over; arm the timer for the next one. */
    fun onChunkStored() {
        notifications.cancel(NOTIFICATION_ID)
        scope.launch { evaluate(armTimer = true) }
    }

    /** The alert or quiet-hours settings changed. */
    fun onSettingsChanged() {
        scope.launch { evaluate(armTimer = true) }
    }

    /** Called by [GapAlertWorker]; only alerts (a running worker must not replace itself). */
    suspend fun check() = evaluate(armTimer = false)

    private suspend fun evaluate(armTimer: Boolean) = lock.withLock {
        val now = clock()
        when (val decision = evaluator.decide(now)) {
            GapAlertDecision.None -> if (armTimer) workManager.cancelUniqueWork(TIMER)
            is GapAlertDecision.Wait -> if (armTimer) {
                val request = OneTimeWorkRequestBuilder<GapAlertWorker>()
                    .setInitialDelay((decision.at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniqueWork(TIMER, ExistingWorkPolicy.REPLACE, request)
            }
            is GapAlertDecision.Notify -> if (post(decision.lastReceivedAt)) evaluator.markShown(decision.lastReceivedAt)
        }
    }

    /** @return false if notifications are not allowed (the alert is retried by the next check). */
    private fun post(lastReceivedAt: Long): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permitted || !notifications.areNotificationsEnabled()) {
            Log.i(TAG, "gap alert due, but notifications are not allowed")
            return false
        }
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(evaluator.channelName)
                .build(),
        )
        val openApp = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = evaluator.message(lastReceivedAt)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(evaluator.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        return try {
            notifications.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot post the gap alert", e)
            false
        }
    }

    private companion object {
        const val TAG = "GapAlertMonitor"
        const val CHANNEL_ID = "gap_alert"
        const val NOTIFICATION_ID = 43
        const val PERIODIC = "gap-alert-periodic"
        const val TIMER = "gap-alert-timer"
    }
}
