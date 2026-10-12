package app.watchaudio.core.embedding

import app.watchaudio.core.embedding.EmbeddingPassages.Rules
import app.watchaudio.core.embedding.EmbeddingPassages.Segment
import kotlin.test.Test
import kotlin.test.assertEquals

/** Joining short segments into passages before embedding (#24). */
class EmbeddingPassagesTest {
    private val rules = Rules(minTokens = 10, maxTokens = 20, maxGapMs = 5_000)

    /** Segments of [tokens] each, 1 s long, [gapMs] apart. */
    private fun segments(vararg tokens: Int, gapMs: Long = 500): List<Segment> {
        var at = 0L
        return tokens.map { t -> Segment(at, at + 1_000, t).also { at += 1_000 + gapMs } }
    }

    @Test
    fun shortSegmentsAreJoinedUntilTheMinimum() {
        // 3+3+3+3 reaches 10 at the fourth segment; the next passage starts after it.
        assertEquals(
            listOf(PassageSpan(0, 4), PassageSpan(4, 4)),
            EmbeddingPassages.plan(segments(3, 3, 3, 3, 3, 3, 3, 3), rules),
        )
    }

    @Test
    fun aPassageNeverGrowsBeyondTheMaximum() {
        assertEquals(listOf(PassageSpan(0, 1), PassageSpan(1, 1), PassageSpan(2, 1)), EmbeddingPassages.plan(segments(9, 15, 30), rules))
        // A long segment stays alone, even above the maximum.
        assertEquals(listOf(PassageSpan(0, 1), PassageSpan(1, 2)), EmbeddingPassages.plan(segments(25, 5, 6), rules))
    }

    @Test
    fun aLongPauseStartsANewPassage() {
        val pause = segments(2, 2) + segments(2, 2).map { it.copy(startAt = it.startAt + 60_000, endAt = it.endAt + 60_000) }
        assertEquals(listOf(PassageSpan(0, 2), PassageSpan(2, 2)), EmbeddingPassages.plan(pause, rules))
    }

    @Test
    fun aShortTailJoinsThePassageBeforeIt() {
        // 5+5 closes at 10; the tail (3) is short and fits (10 + 3 <= 20).
        assertEquals(listOf(PassageSpan(0, 3)), EmbeddingPassages.plan(segments(5, 5, 3), rules))
        // It does not fit: 18 + 3 > 20.
        assertEquals(listOf(PassageSpan(0, 2), PassageSpan(2, 1)), EmbeddingPassages.plan(segments(9, 9, 3), rules))
    }

    @Test
    fun everySegmentIsInExactlyOnePassage() {
        val tokens = IntArray(200) { (it * 7919) % 23 + 1 }
        val all = segments(*tokens, gapMs = 1_000)
        val spans = EmbeddingPassages.plan(all, Rules())
        assertEquals((0 until 200).toList(), spans.flatMap { it.firstIndex..it.lastIndex })
        assertEquals(emptyList(), EmbeddingPassages.plan(emptyList()))
        assertEquals(listOf(PassageSpan(0, 1)), EmbeddingPassages.plan(segments(1)))
    }

    @Test
    fun theTextJoinsSegmentsWithSpaces() {
        assertEquals("えーと 来週の 木曜", EmbeddingPassages.text(listOf(" えーと", "来週の ", "木曜")))
    }
}
