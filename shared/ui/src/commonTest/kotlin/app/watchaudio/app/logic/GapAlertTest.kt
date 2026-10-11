package app.watchaudio.app.logic

import app.watchaudio.core.protocol.ChunkEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class GapAlertTest {
    private val settings = GapAlertSettings()
    private val noQuiet = GapAlertSettings(quietHours = QuietHours(enabled = false))
    private val nextDay = 24 * HOUR

    @Test
    fun quietHoursWrapPastMidnight() {
        val quiet = QuietHours() // 23:00–07:00
        assertTrue(quiet.contains(at(23), TOKYO))
        assertTrue(quiet.contains(at(2), TOKYO))
        assertTrue(quiet.contains(at(6, 59), TOKYO))
        assertFalse(quiet.contains(at(7), TOKYO))
        assertFalse(quiet.contains(at(22, 59), TOKYO))
        assertFalse(quiet.copy(enabled = false).contains(at(2), TOKYO))
        assertFalse(QuietHours(startMinute = 60, endMinute = 60).contains(at(1), TOKYO))
    }

    @Test
    fun quietHoursWithinOneDay() {
        val quiet = QuietHours(startMinute = 13 * 60, endMinute = 14 * 60 + 30)
        assertTrue(quiet.contains(at(13, 15), TOKYO))
        assertFalse(quiet.contains(at(14, 30), TOKYO))
        assertFalse(quiet.contains(at(12, 59), TOKYO))
        assertEquals(at(14, 30), quiet.endOf(at(13, 15), TOKYO))
    }

    @Test
    fun quietHoursEnd() {
        val quiet = QuietHours()
        assertEquals(at(7), quiet.endOf(at(3), TOKYO))
        assertEquals(at(7) + nextDay, quiet.endOf(at(23, 30), TOKYO)) // the next morning
        assertEquals(at(12), quiet.endOf(at(12), TOKYO)) // not quiet: unchanged
    }

    @Test
    fun quietHoursEndAcrossDaylightSavingChange() {
        val berlin = TimeZone.of("Europe/Berlin")
        // 2026-03-29 02:00 does not exist in Berlin (clocks jump to 03:00).
        val quiet = QuietHours(startMinute = 0, endMinute = 2 * 60 + 30)
        val night = LocalDateTime(2026, 3, 29, 1, 0).toInstant(berlin).toEpochMilliseconds()
        val end = quiet.endOf(night, berlin)
        assertTrue(end > night)
        assertFalse(quiet.contains(end, berlin))
    }

    @Test
    fun alertTenMinutesAfterTheLastContinuingChunk() {
        assertEquals(at(15, 10), gapAlertAt(ChunkEndReason.ROTATION, at(15), settings, TOKYO))
        assertEquals(at(15, 10), gapAlertAt(ChunkEndReason.MARK, at(15), settings, TOKYO))
    }

    @Test
    fun noAlertWhenRecordingStoppedForAKnownReason() {
        listOf(ChunkEndReason.USER_STOP, ChunkEndReason.CHARGING, ChunkEndReason.OFF_BODY, ChunkEndReason.TERMINATED)
            .forEach { assertNull(gapAlertAt(it, at(15), settings, TOKYO), it.name) }
        assertNull(gapAlertAt(null, null, settings, TOKYO))
        assertNull(gapAlertAt(ChunkEndReason.ROTATION, at(15), settings.copy(enabled = false), TOKYO))
    }

    @Test
    fun alertDueInQuietHoursMovesToTheirEnd() {
        assertEquals(at(7) + nextDay, gapAlertAt(ChunkEndReason.ROTATION, at(22, 55), settings, TOKYO))
        assertEquals(at(23, 5), gapAlertAt(ChunkEndReason.ROTATION, at(22, 55), noQuiet, TOKYO))
    }

    @Test
    fun decisionWaitsThenNotifiesOnce() {
        val last = at(15)
        fun decide(now: Long, lastReceivedAt: Long = last, alertedFor: Long? = null) =
            gapAlertDecision(ChunkEndReason.ROTATION, lastReceivedAt, now, settings, TOKYO, alertedFor)

        assertEquals(GapAlertDecision.Wait(at(15, 10) + 1), decide(now = at(15, 5)))
        assertEquals(GapAlertDecision.Wait(at(15, 10) + 1), decide(now = at(15, 10)))
        assertEquals(GapAlertDecision.Notify(last), decide(now = at(15, 11)))
        assertEquals(GapAlertDecision.None, decide(now = at(16), alertedFor = last))
        // A newer chunk opens a new gap.
        assertEquals(GapAlertDecision.Notify(at(16)), decide(now = at(16, 30), lastReceivedAt = at(16), alertedFor = last))
    }

    @Test
    fun decisionNeverNotifiesInsideQuietHours() {
        // Due at 15:10, but the first check runs at 23:30: wait until 7:00.
        assertEquals(
            GapAlertDecision.Wait(at(7) + nextDay),
            gapAlertDecision(ChunkEndReason.ROTATION, at(15), at(23, 30), settings, TOKYO),
        )
        assertEquals(
            GapAlertDecision.Notify(at(15)),
            gapAlertDecision(ChunkEndReason.ROTATION, at(15), at(23, 30), noQuiet, TOKYO),
        )
    }

    @Test
    fun decisionIgnoresStoppedRecorderAndMissingData() {
        assertEquals(GapAlertDecision.None, gapAlertDecision(ChunkEndReason.CHARGING, at(15), at(18), settings, TOKYO))
        assertEquals(GapAlertDecision.None, gapAlertDecision(null, null, at(18), settings, TOKYO))
        assertEquals(
            GapAlertDecision.None,
            gapAlertDecision(ChunkEndReason.ROTATION, at(15), at(18), settings.copy(enabled = false), TOKYO),
        )
    }
}
