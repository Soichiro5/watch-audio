package app.watchaudio.core.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TimeRangeTest {
    private val t0 = Instant.parse("2026-10-10T09:00:00Z")

    @Test
    fun containsIsHalfOpen() {
        val range = TimeRange(t0, t0 + 5.minutes)
        assertTrue(t0 in range)
        assertFalse(t0 + 5.minutes in range)
    }

    @Test
    fun adjacentRangesDoNotOverlap() {
        val a = TimeRange(t0, t0 + 5.minutes)
        val b = TimeRange(t0 + 5.minutes, t0 + 10.minutes)
        assertFalse(a.overlaps(b))
        assertTrue(a.overlaps(TimeRange(t0 + 4.minutes, t0 + 6.minutes)))
    }

    @Test
    fun rejectsInvertedRange() {
        assertFailsWith<IllegalArgumentException> { TimeRange(t0 + 1.minutes, t0) }
    }
}
