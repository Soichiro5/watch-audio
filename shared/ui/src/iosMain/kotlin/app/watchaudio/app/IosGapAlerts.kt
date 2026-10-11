package app.watchaudio.app

import app.watchaudio.app.alert.GapAlertText
import app.watchaudio.core.alert.GapAlertEvaluator
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import kotlin.time.Clock
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS delivery of the gap alert (#43). The app may be suspended when chunks stop arriving, so
 * instead of checking periodically it keeps one pending local notification for the moment the
 * alert would be due ([GapAlertEvaluator.alertAt]) and replaces it after every received chunk
 * (none if the chunk ended the recording session, e.g. charging or user stop).
 *
 * The notification is only shown while the app is in the background (no
 * `UNUserNotificationCenterDelegate`); in the foreground the status tab shows the warning.
 */
internal class IosGapAlerts(
    private val evaluator: GapAlertEvaluator,
    private val diagnostics: Diagnostics = Diagnostics.NONE,
) {
    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    /**
     * Replaces the pending alert. [chunkArrived]: a chunk was just stored, so a delivered alert
     * is out of date and removed as well.
     */
    suspend fun reschedule(chunkArrived: Boolean) {
        val ids = listOf(NOTIFICATION_ID)
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        if (chunkArrived) center.removeDeliveredNotificationsWithIdentifiers(ids)

        val at = evaluator.alertAt() ?: return
        val lastReceivedAt = evaluator.lastReceivedAt() ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        if (at <= now) return // already overdue: the status tab shows it; do not alert on launch

        val content = UNMutableNotificationContent()
        content.setTitle(GapAlertText.title)
        content.setBody(GapAlertText.message(lastReceivedAt))
        content.setSound(UNNotificationSound.defaultSound())
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval((at - now) / 1000.0, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(NOTIFICATION_ID, content, trigger), null)
        diagnostics.log(DiagnosticEvents.GAP_ALERT_SCHEDULED, "at=$at last_received_at=$lastReceivedAt")
    }

    private companion object {
        const val NOTIFICATION_ID = "app.watchaudio.gap-alert"
    }
}
