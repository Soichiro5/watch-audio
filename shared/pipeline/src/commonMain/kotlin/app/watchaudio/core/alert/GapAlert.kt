package app.watchaudio.core.alert

import app.watchaudio.core.protocol.ChunkEndReason
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Timing of the gap alert (#43). */
object GapAlertTiming {
    /** No chunk for this long while the watch should be recording raises a warning. */
    const val SILENCE_MS = 10 * 60_000L
}

/**
 * Daily local time window in which no gap alert is shown (e.g. while sleeping).
 * `[startMinute, endMinute)` in minutes after local midnight; wraps past midnight when
 * [startMinute] > [endMinute]. An empty window (start == end) never applies.
 */
data class QuietHours(
    val enabled: Boolean = true,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY) {
            "minutes out of range: $startMinute–$endMinute"
        }
    }

    fun containsMinute(minuteOfDay: Int): Boolean = when {
        !enabled || startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun contains(epochMs: Long, timeZone: TimeZone): Boolean = containsMinute(minuteOfDay(epochMs, timeZone))

    /** When the quiet window that contains [epochMs] ends; [epochMs] itself if it is not inside one. */
    fun endOf(epochMs: Long, timeZone: TimeZone): Long {
        if (!contains(epochMs, timeZone)) return epochMs
        val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
        val minute = local.hour * 60 + local.minute
        // Inside a window that wraps midnight and started this evening: it ends tomorrow.
        val endDate = if (minute >= endMinute) local.date.plus(1, DateTimeUnit.DAY) else local.date
        val end = LocalDateTime(endDate, LocalTime(endMinute / 60, endMinute % 60)).toInstant(timeZone).toEpochMilliseconds()
        return maxOf(end, epochMs)
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

private fun minuteOfDay(epochMs: Long, timeZone: TimeZone): Int {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return local.hour * 60 + local.minute
}

/** User preferences for the phone-side "recording is not arriving" alert (#43). */
data class GapAlertSettings(
    val enabled: Boolean = true,
    val quietHours: QuietHours = QuietHours(),
)

/**
 * When to alert that chunks stopped arriving, or null when no alert is expected: alerts are
 * off, nothing was received yet, or the newest chunk ended with a reason that starts a gap
 * (user stop, charging, off body, …: the recorder stopped knowingly and the timeline shows why).
 *
 * The alert is due [silenceMs] after [lastReceivedAt]; inside quiet hours it moves to their end.
 * iOS schedules a local notification for this time after every received chunk.
 *
 * @param lastEndReason end reason of the newest chunk (by start time).
 * @param lastReceivedAt when the phone last stored a chunk.
 */
fun gapAlertAt(
    lastEndReason: ChunkEndReason?,
    lastReceivedAt: Long?,
    settings: GapAlertSettings,
    timeZone: TimeZone,
    silenceMs: Long = GapAlertTiming.SILENCE_MS,
): Long? {
    if (!settings.enabled || lastEndReason == null || lastReceivedAt == null || lastEndReason.startsGap) return null
    return settings.quietHours.endOf(lastReceivedAt + silenceMs, timeZone)
}

sealed interface GapAlertDecision {
    /** Nothing to do: recording is not expected, or this gap was already alerted. */
    data object None : GapAlertDecision

    /** Check again at [at] (epoch ms). */
    data class Wait(val at: Long) : GapAlertDecision

    /** Show the alert now; [lastReceivedAt] identifies the gap so it is alerted only once. */
    data class Notify(val lastReceivedAt: Long) : GapAlertDecision
}

/**
 * What a periodic check (Android WorkManager) should do at [now]. Like [gapAlertAt], plus: a
 * gap is alerted once ([alertedFor] is the `lastReceivedAt` of the last alert shown), and never
 * while [now] is inside quiet hours (an alert that came due earlier waits for their end).
 */
fun gapAlertDecision(
    lastEndReason: ChunkEndReason?,
    lastReceivedAt: Long?,
    now: Long,
    settings: GapAlertSettings,
    timeZone: TimeZone,
    alertedFor: Long? = null,
    silenceMs: Long = GapAlertTiming.SILENCE_MS,
): GapAlertDecision {
    if (lastReceivedAt == null || lastReceivedAt == alertedFor) return GapAlertDecision.None
    val dueAt = gapAlertAt(lastEndReason, lastReceivedAt, settings, timeZone, silenceMs) ?: return GapAlertDecision.None
    // Strictly more than silenceMs, like arrivalState's "late".
    if (now <= dueAt) return GapAlertDecision.Wait(dueAt + 1)
    if (settings.quietHours.contains(now, timeZone)) return GapAlertDecision.Wait(settings.quietHours.endOf(now, timeZone))
    return GapAlertDecision.Notify(lastReceivedAt)
}
