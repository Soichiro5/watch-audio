package app.watchaudio.wear.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.watchaudio.wear.MainActivity
import app.watchaudio.wear.R
import app.watchaudio.wear.ui.statusText

/** The ongoing recording notification and the "recording stopped" alerts (notification + haptic). */
class RecorderAlerts(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },
        )
    }

    /**
     * Foreground notification, shown as an Ongoing Activity on the watch face / launcher. While
     * recording it carries a stopwatch from [sessionStartedAtEpochMs].
     */
    fun ongoingNotification(mode: RecorderMode, sessionStartedAtEpochMs: Long?): Notification {
        val text = statusText(context, mode.statusKey(), armed = true)
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(resume = false))

        val status = if (mode == RecorderMode.Recording && sessionStartedAtEpochMs != null) {
            // Ongoing Activity timers use the elapsedRealtime time base.
            val startElapsed = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sessionStartedAtEpochMs)
            Status.Builder()
                .addTemplate("#elapsed#")
                .addPart("elapsed", Status.StopwatchPart(startElapsed))
                .build()
        } else {
            Status.Builder().addTemplate(text).build()
        }
        OngoingActivity.Builder(context, NOTIFICATION_ONGOING, builder)
            .setStaticIcon(R.drawable.ic_mic)
            .setTouchIntent(openAppIntent(resume = false))
            .setStatus(status)
            .build()
            .apply(context)
        return builder.build()
    }

    fun updateOngoing(mode: RecorderMode, sessionStartedAtEpochMs: Long?) {
        runCatching { manager.notify(NOTIFICATION_ONGOING, ongoingNotification(mode, sessionStartedAtEpochMs)) }
    }

    fun show(alert: RecorderAlert) = when (alert) {
        RecorderAlert.LOW_BATTERY -> post(R.string.alert_low_battery_title, R.string.alert_low_battery_text, resume = false)
        RecorderAlert.LOW_STORAGE -> post(R.string.alert_low_storage_title, R.string.alert_low_storage_text, resume = false)
        RecorderAlert.INTERRUPTED -> post(R.string.alert_interrupted_title, R.string.alert_interrupted_text, resume = false)
    }

    /** The process died while armed; recording can only be resumed from the foreground. */
    fun showTerminated() = post(R.string.alert_terminated_title, R.string.alert_terminated_text, resume = true)

    fun cancelAlert() = manager.cancel(NOTIFICATION_ALERT)

    private fun post(title: Int, text: Int, resume: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(resume))
            .build()
        // Fails silently without POST_NOTIFICATIONS; the haptic still gets the wearer's attention.
        runCatching { manager.notify(NOTIFICATION_ALERT, notification) }
        vibrate()
    }

    private fun vibrate() {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)) }
    }

    private fun openAppIntent(resume: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (resume) MainActivity.ACTION_RESUME else Intent.ACTION_MAIN)
        return PendingIntent.getActivity(context, if (resume) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIFICATION_ONGOING = 1
        const val NOTIFICATION_ALERT = 2
        private val VIBRATION_PATTERN = longArrayOf(0, 250, 150, 250, 150, 250)
    }
}
