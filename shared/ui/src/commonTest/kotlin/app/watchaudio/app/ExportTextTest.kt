package app.watchaudio.app

import app.watchaudio.core.export.MarkdownText
import app.watchaudio.core.protocol.ChunkEndReason
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportTextTest {
    /** The exported Markdown (#155) names gaps with the same words as the timeline. */
    @Test
    fun exportedGapReasonsMatchTheTimeline() {
        ChunkEndReason.entries.forEach { assertEquals(Strings.endReason(it), MarkdownText.endReason(it), it.name) }
        assertEquals(Strings.GAP_MISSING, MarkdownText.GAP_MISSING)
        assertEquals(Strings.GAP_AWAITING, MarkdownText.GAP_AWAITING)
        assertEquals(Strings.GAPS, MarkdownText.GAPS)
        assertEquals(Strings.WEEKDAYS, MarkdownText.WEEKDAYS)
    }
}
