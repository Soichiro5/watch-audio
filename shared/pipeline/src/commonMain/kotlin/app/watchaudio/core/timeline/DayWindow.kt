package app.watchaudio.core.timeline

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** One local calendar day as epoch-ms `[start, end)`; 23 or 25 hours long on DST changes. */
data class DayWindow(val start: Long, val end: Long) {
    val lengthMs: Long get() = end - start
}

fun dayWindow(date: LocalDate, timeZone: TimeZone): DayWindow = DayWindow(
    start = date.atStartOfDayIn(timeZone).toEpochMilliseconds(),
    end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds(),
)

fun localDateOf(epochMs: Long, timeZone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date
