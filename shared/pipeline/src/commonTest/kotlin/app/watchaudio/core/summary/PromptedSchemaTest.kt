package app.watchaudio.core.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The request as one text for a model without guided generation (Gemini Nano on Android, #205). */
class PromptedSchemaTest {
    @Test
    fun theExampleHasTheShapeOfTheSchema() {
        assertEquals(
            """{"summary": "…", "highlights": [{"title": "…", "body": "…", "lines": [1]}]}""",
            SummaryPrompts.SCHEMA.exampleJson(),
        )
        // The example itself parses as an answer citing line 1.
        val draft = assertNotNull(parseSummaryOutput(SummaryPrompts.SCHEMA.exampleJson(), setOf(1)))
        assertEquals(listOf(1), draft.highlights.single().lines)
    }

    @Test
    fun everyFieldIsDescribedWithItsLimits() {
        val text = SummaryPrompts.SCHEMA.schemaInstructions()
        assertTrue(text.startsWith("答えは次の形の JSON だけを書く"), text)
        assertTrue("- summary: 話されたことの要約（日本語）" in text, text)
        assertTrue("- highlights: 大事な出来事・決まったこと・予定・数字（最大 3 件）、最大 ${SummaryPrompts.MAX_HIGHLIGHTS} 件" in text, text)
        assertTrue("- highlights.title: 要点の短い見出し（日本語、20 文字以内）" in text, text)
        assertTrue("- highlights.lines: 根拠になった行の番号、最大 ${SummaryPrompts.MAX_CITED_LINES} 件" in text, text)
    }

    @Test
    fun theSingleTextIsInstructionsThenShapeThenLines() {
        val request = LlmRequest("指示です。", "[1] 発話\n[2] 次の発話", SummaryPrompts.SCHEMA, 500)
        val text = request.toSingleText()
        val instructions = text.indexOf("指示です。")
        val shape = text.indexOf(SummaryPrompts.SCHEMA.exampleJson())
        val lines = text.indexOf("[1] 発話\n[2] 次の発話")
        assertTrue(instructions == 0 && shape > instructions && lines > shape, text)
        assertTrue(text.endsWith("入力:\n[1] 発話\n[2] 次の発話"), text)
    }
}
