package app.watchaudio.core.model

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** A half-open time interval `[start, end)` on the recording timeline. */
@Serializable
data class TimeRange(val start: Instant, val end: Instant) {
    init {
        require(start <= end) { "start ($start) must not be after end ($end)" }
    }

    val duration: Duration get() = end - start

    operator fun contains(instant: Instant): Boolean = instant >= start && instant < end

    fun overlaps(other: TimeRange): Boolean = start < other.end && other.start < end
}
