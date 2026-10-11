package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path

class BoundaryJoinTest {
    private fun chunk(
        id: String,
        start: Long,
        end: Long = start + 300_000,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        session: String = "s",
        audio: Boolean = true,
    ) = ChunkRecord(
        id = id, sessionId = session, deviceId = "w", source = SourcePlatform.WEAROS,
        startedAt = start, endedAt = end, timeZone = "UTC", audioPath = if (audio) "$id.m4a" else null,
        endReason = endReason, state = ChunkState.TRANSCRIBED, attempts = 1, lastError = null,
    )

    private var nextId = 1L

    private fun segment(chunk: ChunkRecord, start: Long, end: Long, text: String = "x") =
        SegmentRecord(nextId++, chunk.id, start, end, text, "ja", "test")

    private val a = chunk("a", 0)
    private val b = chunk("b", 300_000)

    @Test
    fun seamlessNeedsSameSessionNoGapReasonAudioAndAdjacentTimes() {
        assertTrue(BoundaryJoin.isSeamless(a, b))
        assertTrue(BoundaryJoin.isSeamless(chunk("m", 0, endReason = ChunkEndReason.MARK), b))
        assertTrue(BoundaryJoin.isSeamless(a, chunk("b", 300_800)))
        assertFalse(BoundaryJoin.isSeamless(chunk("x", 0, endReason = ChunkEndReason.USER_STOP), b))
        assertFalse(BoundaryJoin.isSeamless(a, chunk("b", 300_000, session = "other")))
        assertFalse(BoundaryJoin.isSeamless(a, chunk("b", 305_000)))
        assertFalse(BoundaryJoin.isSeamless(a, chunk("b", 300_000, audio = false)))
    }

    @Test
    fun tailStartsJustBeforeTheSpeechReachingTheEnd() {
        val earlier = segment(a, 10_000, 20_000)
        val last = segment(a, 290_000, 300_000)
        assertEquals(BoundaryContext(a, 289_500, 300_000), BoundaryJoin.tail(a, listOf(earlier, last)))
        // The margin never reaches back into the previous utterance.
        val close = segment(a, 280_000, 289_800)
        assertEquals(BoundaryContext(a, 289_800, 300_000), BoundaryJoin.tail(a, listOf(close, last)))
        // Speech ending within NEAR_BOUNDARY_MS of the end still counts.
        assertEquals(BoundaryContext(a, 289_500, 300_000), BoundaryJoin.tail(a, listOf(segment(a, 290_000, 298_600))))
    }

    @Test
    fun noTailWithoutSpeechAtTheEndOrWhenTooLong() {
        assertNull(BoundaryJoin.tail(a, emptyList()))
        assertNull(BoundaryJoin.tail(a, listOf(segment(a, 280_000, 297_000))))
        assertNull(BoundaryJoin.tail(a, listOf(segment(a, 250_000, 300_000))))
        // Speech that started in an earlier chunk and covers this one entirely.
        val short = chunk("short", 0, 10_000)
        assertNull(BoundaryJoin.tail(short, listOf(segment(chunk("z", -300_000), -1_000, 10_000))))
    }

    @Test
    fun headEndsJustAfterTheSpeechAtTheStart() {
        val first = segment(b, 300_000, 304_000)
        val later = segment(b, 304_200, 306_000)
        assertEquals(BoundaryContext(b, 300_000, 304_200), BoundaryJoin.head(b, listOf(first, later)))
        assertEquals(BoundaryContext(b, 300_000, 304_500), BoundaryJoin.head(b, listOf(first)))
        // A segment the previous chunk owns (it started there) counts as speech at the start.
        val spanning = segment(a, 298_000, 303_000)
        assertEquals(BoundaryContext(b, 300_000, 303_500), BoundaryJoin.head(b, listOf(spanning, segment(b, 310_000, 312_000))))
    }

    @Test
    fun noHeadWithoutSpeechAtTheStartOrWhenTooLong() {
        assertNull(BoundaryJoin.head(b, emptyList()))
        assertNull(BoundaryJoin.head(b, listOf(segment(b, 302_000, 304_000))))
        assertNull(BoundaryJoin.head(b, listOf(segment(b, 300_000, 340_000))))
        // Speech running on past the end of a short chunk would be truncated.
        val short = chunk("short", 300_000, 310_000)
        assertNull(BoundaryJoin.head(short, listOf(segment(short, 300_000, 312_000))))
    }

    @Test
    fun partsCoverTheTailTheChunkAndTheHead() {
        val c = chunk("c", 600_000)
        val joined = JoinedTranscription(b, BoundaryContext(a, 289_500, 300_000), BoundaryContext(c, 600_000, 604_500))
        assertEquals(
            listOf(
                AudioPart(Path("/r", "a.m4a"), 289_500, 300_000),
                AudioPart(Path("/r", "b.m4a"), 0, 300_000),
                AudioPart(Path("/r", "c.m4a"), 0, 4_500),
            ),
            joined.parts(Path("/r")),
        )
    }

    @Test
    fun resultsGoToTheChunkTheyStartInWithAbsoluteTimes() {
        val c = chunk("c", 600_000)
        // Concatenation: a 289.5–300 s (0–10.5 s), b (10.5–310.5 s), c 600–604.5 s (310.5–315 s).
        val joined = JoinedTranscription(b, BoundaryContext(a, 289_500, 300_000), BoundaryContext(c, 600_000, 604_500))
        val assigned = joined.assign(
            listOf(
                TimedText(500, 12_500, "前から続く", "ja"),
                TimedText(100_000, 101_000, "途中", "ja"),
                TimedText(309_000, 312_000, "次へ続く", "ja"),
                TimedText(311_000, 314_000, "次の頭", "ja"),
            ),
        )
        assertEquals(listOf(NewSegment(290_000, 302_000, "前から続く", "ja")), assigned["a"])
        assertEquals(
            listOf(NewSegment(389_500, 390_500, "途中", "ja"), NewSegment(598_500, 601_500, "次へ続く", "ja")),
            assigned["b"],
        )
        assertEquals(listOf(NewSegment(600_500, 603_500, "次の頭", "ja")), assigned["c"])
    }

    @Test
    fun singleChunkKeepsItsOwnTimeline() {
        val assigned = JoinedTranscription(a).assign(listOf(TimedText(1_000, 2_000, "こんにちは")))
        assertEquals(mapOf("a" to listOf(NewSegment(1_000, 2_000, "こんにちは", null))), assigned)
    }
}
