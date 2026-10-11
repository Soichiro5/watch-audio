package app.watchaudio.app.alert

import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatClock
import kotlinx.datetime.TimeZone

/**
 * The texts of the gap alert notification (#43); the platforms post it when
 * [app.watchaudio.core.alert.GapAlertEvaluator] says so.
 */
object GapAlertText {
    val title: String get() = Strings.GAP_ALERT_TITLE
    val channelName: String get() = Strings.GAP_ALERT_CHANNEL

    /** 「録音が届いていません（最後の受信: HH:mm）。…」 */
    fun message(lastReceivedAt: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
        Strings.gapAlert(formatClock(lastReceivedAt, timeZone))
}
