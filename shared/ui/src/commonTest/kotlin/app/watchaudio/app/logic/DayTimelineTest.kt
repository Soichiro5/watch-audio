package app.watchaudio.app.logic

import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.timeline.GapCause
import app.watchaudio.core.timeline.TimelineSpan
import app.watchaudio.core.timeline.buildDayTimeline
import app.watchaudio.core.timeline.dayWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class DayTimelineTest {
    private val endOfDay = WINDOW.end

    @Test
    fun emptyPastDayIsOneUnknownGap() {
        val timeline = buildDayTimeline(WINDOW, emptyList(), previous = null, now = endOfDay + HOUR)
        assertEquals(listOf(TimelineSpan.Gap(WINDOW.start, WINDOW.end, GapCause.NoRecording)), timeline.spans)
        assertEquals(0f, timeline.coverage)
        assertFalse(timeline.hasRecording)
    }

    @Test
    fun gapsCarryTheEndReasonOfThePrecedingChunk() {
        val chunks = run("a", at(8), at(12), lastReason = ChunkEndReason.CHARGING) +
            run("b", at(13), at(18), lastReason = ChunkEndReason.USER_STOP)
        val timeline = buildDayTimeline(WINDOW, chunks, previous = null, now = endOfDay)

        assertEquals(
            listOf(
                TimelineSpan.Gap(WINDOW.start, at(8), GapCause.NoRecording),
                TimelineSpan.Audio(at(8), at(12)),
                TimelineSpan.Gap(at(12), at(13), GapCause.Stopped(ChunkEndReason.CHARGING)),
                TimelineSpan.Audio(at(13), at(18)),
                TimelineSpan.Gap(at(18), WINDOW.end, GapCause.Stopped(ChunkEndReason.USER_STOP)),
            ),
            timeline.spans,
        )
        assertEquals(9 * HOUR, timeline.audioMs)
        assertEquals(9f / 24f, timeline.coverage, 0.0001f)
    }

    @Test
    fun previousDayExplainsTheMorningGap() {
        val previous = chunk("p", at(-2), at(-1), endReason = ChunkEndReason.OFF_BODY)
        val timeline = buildDayTimeline(WINDOW, run("a", at(7), at(8)), previous, now = endOfDay)
        assertEquals(TimelineSpan.Gap(WINDOW.start, at(7), GapCause.Stopped(ChunkEndReason.OFF_BODY)), timeline.spans.first())
    }

    @Test
    fun chunkSpanningMidnightIsClipped() {
        val overnight = chunk("o", at(-1), at(1), endReason = ChunkEndReason.ROTATION)
        val timeline = buildDayTimeline(WINDOW, listOf(overnight), previous = overnight, now = endOfDay)
        assertEquals(TimelineSpan.Audio(WINDOW.start, at(1)), timeline.spans.first())
        // The recorder did not report stopping, so the rest of the day is missing data.
        assertEquals(TimelineSpan.Gap(at(1), WINDOW.end, GapCause.Missing), timeline.spans.last())
    }

    @Test
    fun silentChunksCountAsCoveredButNotAsAudio() {
        val chunks = listOf(
            chunk("a", at(9), at(9, 5)),
            chunk("s", at(9, 5), at(9, 10), audio = false),
            chunk("b", at(9, 10), at(9, 15), endReason = ChunkEndReason.USER_STOP),
        )
        val timeline = buildDayTimeline(WINDOW, chunks, previous = null, now = endOfDay)
        assertEquals(
            listOf(
                TimelineSpan.Audio(at(9), at(9, 5)),
                TimelineSpan.Silent(at(9, 5), at(9, 10)),
                TimelineSpan.Audio(at(9, 10), at(9, 15)),
            ),
            timeline.spans.filter { it !is TimelineSpan.Gap },
        )
        assertEquals(10 * MIN, timeline.audioMs)
        assertEquals(5 * MIN, timeline.silentMs)
        assertEquals(15 * MIN, timeline.coveredMs)
    }

    @Test
    fun rotationJitterIsNotAGapButAMissingChunkIs() {
        val chunks = listOf(
            chunk("a", at(9), at(9, 5) - 2_000),
            chunk("b", at(9, 5), at(9, 10)),
            // 9:10–9:15 never arrived
            chunk("c", at(9, 15), at(9, 20), endReason = ChunkEndReason.USER_STOP),
        )
        val spans = buildDayTimeline(WINDOW, chunks, previous = null, now = endOfDay).spans
        assertTrue(TimelineSpan.Audio(at(9), at(9, 10)) in spans)
        assertTrue(TimelineSpan.Gap(at(9, 10), at(9, 15), GapCause.Missing) in spans)
    }

    @Test
    fun overlappingSourcesAreCountedOnce() {
        val chunks = listOf(chunk("watch", at(9), at(10)), chunk("phone", at(9, 30), at(10, 30), endReason = ChunkEndReason.USER_STOP))
        val timeline = buildDayTimeline(WINDOW, chunks, previous = null, now = endOfDay)
        assertEquals(90 * MIN, timeline.audioMs)
        assertEquals(TimelineSpan.Gap(at(10, 30), WINDOW.end, GapCause.Stopped(ChunkEndReason.USER_STOP)), timeline.spans.last())
    }

    @Test
    fun todayEndsInFutureAndRecentHoleIsAwaitingTransfer() {
        val now = at(15, 10)
        val timeline = buildDayTimeline(WINDOW, run("a", at(14), at(15)), previous = null, now = now)
        assertEquals(
            listOf(
                TimelineSpan.Gap(WINDOW.start, at(14), GapCause.NoRecording),
                TimelineSpan.Audio(at(14), at(15)),
                TimelineSpan.Gap(at(15), now, GapCause.Awaiting),
                TimelineSpan.Future(now, WINDOW.end),
            ),
            timeline.spans,
        )
        // Coverage is still measured against the whole day.
        assertEquals(1f / 24f, timeline.coverage, 0.0001f)
    }

    @Test
    fun oldHoleWhileRecordingIsMissing() {
        val now = at(16)
        val timeline = buildDayTimeline(WINDOW, run("a", at(14), at(15)), previous = null, now = now)
        assertTrue(TimelineSpan.Gap(at(15), now, GapCause.Missing) in timeline.spans)
    }

    @Test
    fun futureDayIsAllFuture() {
        val timeline = buildDayTimeline(WINDOW, emptyList(), previous = null, now = WINDOW.start - HOUR)
        assertEquals(listOf(TimelineSpan.Future(WINDOW.start, WINDOW.end)), timeline.spans)
    }

    @Test
    fun spansAlwaysTileTheWindow() {
        val chunks = run("a", at(0, 1), at(3), ChunkEndReason.INTERRUPTION) +
            listOf(chunk("s", at(5), at(6), audio = false)) +
            run("b", at(6), at(23, 58))
        for (now in listOf(at(2), at(12), endOfDay, endOfDay + HOUR)) {
            val spans = buildDayTimeline(WINDOW, chunks, previous = null, now = now).spans
            assertEquals(WINDOW.start, spans.first().start)
            assertEquals(WINDOW.end, spans.last().end)
            spans.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, "$a -> $b") }
        }
    }

    @Test
    fun dstDayIsLongerThan24Hours() {
        val berlin = TimeZone.of("Europe/Berlin")
        val window = dayWindow(LocalDate(2026, 10, 25), berlin)
        assertEquals(25 * HOUR, window.lengthMs)
    }
}
