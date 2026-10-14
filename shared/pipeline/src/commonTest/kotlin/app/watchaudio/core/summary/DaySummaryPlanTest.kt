package app.watchaudio.core.summary

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.HOUR_MS
import app.watchaudio.core.db.HourSummaryRecord
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.timeline.dayWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Which hours of a day get summarized (#27 §4.4) and the density score (§4.2). */
class DaySummaryPlanTest {
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val window = dayWindow(LocalDate(2026, 10, 10), tokyo)
    private fun hour(h: Int) = window.start + h * HOUR_MS

    private fun chunk(id: String, start: Long, state: ChunkState = ChunkState.TRANSCRIBED) = ChunkRecord(
        id, "s", "w", SourcePlatform.WEAROS, start, start + 300_000, tokyo.id, "$id.m4a", ChunkEndReason.ROTATION, state, 0, null,
    )

    private var nextId = 1L
    private fun speech(at: Long, chars: Int) = SegmentRecord(nextId++, "c", at, at + 5_000, "あ".repeat(chars), "ja", "e")

    private fun summary(h: Int, state: HourSummaryState = HourSummaryState.DONE, stale: Boolean = false, version: Int = SummaryPrompts.VERSION) =
        HourSummaryRecord(hour(h), tokyo.id, state, "要約", "fake", "1", version, "hash", stale, 0, null, 0)

    @Test
    fun eachHourGetsAnAction() {
        val segments = listOf(
            speech(hour(7), 300), // 7: to summarize
            speech(hour(8), 50), // 8: little speech
            speech(hour(9), 300), // 9: done
            speech(hour(10), 300), // 10: stale
            speech(hour(11), 300), // 11: failed (the user asks again)
            speech(hour(12), 300), // 12: refused, not retried
            speech(hour(13), 300), // 13: older prompts
            speech(hour(14), 300), // 14: a chunk is still waiting for transcription
            speech(hour(15), 300), // 15: not over yet
        )
        val chunks = listOf(chunk("p", hour(14) + 600_000, ChunkState.RECEIVED), chunk("f", hour(16), ChunkState.FAILED))
        val summaries = listOf(
            summary(9),
            summary(10, stale = true),
            summary(11, HourSummaryState.FAILED),
            summary(12, HourSummaryState.REFUSED),
            summary(13, version = SummaryPrompts.VERSION - 1),
        )
        val plan = planDay(window, chunks, segments, summaries, now = hour(15) + 40 * 60_000)
        assertEquals(24, plan.size)
        val actions = plan.associate { ((it.hourStart - window.start) / HOUR_MS).toInt() to it.action }
        assertEquals(
            mapOf(
                7 to HourAction.SUMMARIZE,
                8 to HourAction.LITTLE_SPEECH,
                9 to HourAction.UP_TO_DATE,
                10 to HourAction.SUMMARIZE,
                11 to HourAction.SUMMARIZE,
                12 to HourAction.UP_TO_DATE,
                13 to HourAction.SUMMARIZE,
                14 to HourAction.WAIT_TRANSCRIPTION,
                15 to HourAction.HOUR_NOT_OVER,
            ),
            actions.filterValues { it != HourAction.NOTHING },
        )
        // A stored little-speech hour is current until its segments change.
        val again = planDay(window, chunks, segments, listOf(summary(8, HourSummaryState.LITTLE_SPEECH)), now = hour(23))
        assertEquals(HourAction.UP_TO_DATE, again[8].action)
    }

    @Test
    fun aDstDayHasItsLocalHours() {
        val berlin = TimeZone.of("Europe/Berlin")
        assertEquals(25, planDay(dayWindow(LocalDate(2026, 10, 25), berlin), emptyList(), emptyList(), emptyList(), 0).size)
        assertEquals(23, planDay(dayWindow(LocalDate(2026, 3, 29), berlin), emptyList(), emptyList(), emptyList(), 0).size)
    }

    @Test
    fun densityComesFromSpeechTimeAndCharacters() {
        val start = hour(9)
        assertEquals(0f, HourDensity.of(emptyList(), start).score)
        val full = HourDensity.of((0 until 200).map { SegmentRecord(it.toLong(), "c", start + it * 15_000L, start + it * 15_000L + 6_000, "あ".repeat(40), "ja", "e") }, start)
        assertEquals(1f, full.score)
        val half = HourDensity(speechMs = HourDensity.FULL_SPEECH_MS / 2, chars = HourDensity.FULL_CHARS / 2)
        assertEquals(0.5f, half.score)
        // Speech past the end of the hour is not counted; segments of other hours are ignored.
        val edge = HourDensity.of(listOf(SegmentRecord(1, "c", start + HOUR_MS - 1_000, start + HOUR_MS + 9_000, "あ", "ja", "e"), speech(start + HOUR_MS, 10)), start)
        assertEquals(HourDensity(1_000, 1), edge)
    }
}
