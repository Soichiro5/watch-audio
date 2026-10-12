package app.watchaudio.app.logic

import app.watchaudio.app.Strings
import app.watchaudio.core.timeline.localDateOf
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/** `HH:mm` in [timeZone]. */
fun formatClock(epochMs: Long, timeZone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${t.hour.pad2()}:${t.minute.pad2()}"
}

/** `HH:mm` for a minute after local midnight (quiet hours). */
fun formatMinuteOfDay(minuteOfDay: Int): String = "${(minuteOfDay / 60).pad2()}:${(minuteOfDay % 60).pad2()}"

/** `HH:mm–HH:mm`; an end at local midnight is shown as `24:00`. */
fun formatClockRange(from: Long, to: Long, timeZone: TimeZone): String {
    val end = Instant.fromEpochMilliseconds(to).toLocalDateTime(timeZone)
    val endText = if (end.hour == 0 && end.minute == 0 && to > from && localDateOf(from, timeZone) != end.date) {
        "24:00"
    } else {
        formatClock(to, timeZone)
    }
    return "${formatClock(from, timeZone)}–$endText"
}

fun formatDate(date: LocalDate): String =
    Strings.date(date.year, date.month.number, date.day, Strings.WEEKDAYS[date.dayOfWeek.isoDayNumber - 1])

fun formatDateShort(date: LocalDate): String =
    Strings.dateShort(date.month.number, date.day, Strings.WEEKDAYS[date.dayOfWeek.isoDayNumber - 1])

/** Rounded down to the largest sensible units: `3時間12分`, `12分`, `45秒`. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> Strings.hoursMinutes(h, m)
        h > 0 -> Strings.hours(h)
        m > 0 -> Strings.minutes(m)
        else -> Strings.seconds(totalSeconds)
    }
}

/** `3分前`, or `たった今` under a minute. */
fun formatAgo(ms: Long): String = if (ms < 60_000) Strings.JUST_NOW else Strings.ago(formatDuration(ms))

/** Megabytes with one decimal, e.g. `123.4 MB`. */
fun formatMegabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10} MB"
}

/** Hours with one decimal, e.g. `12.5 時間`. */
fun formatHours(ms: Long): String {
    val tenths = (ms + 180_000) / 360_000
    return "${tenths / 10}.${tenths % 10} 時間"
}

fun formatPercent(fraction: Float): String = "${(fraction * 100).toInt().coerceIn(0, 100)}%"

private fun Int.pad2() = toString().padStart(2, '0')
