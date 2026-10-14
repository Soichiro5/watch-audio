package app.watchaudio.core.summary

import app.watchaudio.core.db.SegmentRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptLinesTest {
    private fun segment(id: Long, startAt: Long, text: String, endAt: Long = startAt + 1_500) =
        SegmentRecord(id, "c", startAt, endAt, text, "ja", "e")

    @Test
    fun closeShortSegmentsAreJoinedAndNumbered() {
        val lines = PromptLines.build(
            listOf(
                segment(1, 0, "おはよう"),
                segment(2, 2_000, "今日は"), // 0.5 s after the previous one ended
                segment(3, 10_000, "会議です"), // 6.5 s later: a new line
                segment(4, 11_000, "あ".repeat(59)), // would make the line longer than 60 characters
            ),
            marks = emptyList(),
        )
        assertEquals(listOf("[1] おはよう 今日は", "[2] 会議です", "[3] ${"あ".repeat(59)}"), lines.map { it.render() })
        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L)), lines.map { it.segmentIds })
        assertEquals(listOf(0L, 10_000L, 11_000L), lines.map { it.startAt })
    }

    @Test
    fun linesNearAMarkGetAStar() {
        val lines = PromptLines.build(
            listOf(segment(1, 0, "前"), segment(2, 300_000, "印の近く"), segment(3, 600_000, "後")),
            marks = listOf(400_000),
        )
        assertEquals(listOf(false, true, false), lines.map { it.marked })
        assertEquals("[2]★ 印の近く", lines[1].render())
    }

    @Test
    fun textIsKeptOnOneLineAndBlankSegmentsAreSkipped() {
        val lines = PromptLines.build(
            listOf(segment(2, 5_000, "二行の\n発話"), segment(1, 0, "  "), segment(3, 20_000, "x".repeat(400))),
            marks = emptyList(),
        )
        assertEquals("[1] 二行の 発話", lines[0].render())
        assertEquals(PromptLines.MAX_SEGMENT_CHARS + 1, lines[1].text.length)
        assertTrue(lines[1].text.endsWith("…"))
        assertFalse(lines.any { '\n' in it.render() })
    }
}
