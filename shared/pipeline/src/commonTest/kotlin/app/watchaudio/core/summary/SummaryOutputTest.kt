package app.watchaudio.core.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checking the LLM's answer (#27 §4.5). */
class SummaryOutputTest {
    private val valid = setOf(1, 2, 3)

    @Test
    fun unknownLineNumbersAreDroppedAndAHighlightWithoutAnyIsDropped() {
        val draft = parseSummaryOutput(
            """
            {"summary": " 会議の日程を決めた。 ", "highlights": [
              {"title": "日程", "body": "水曜に決まった", "lines": [2, 99, 2, 0]},
              {"title": "作り話", "body": "どこにもない", "lines": [42, 7]},
              {"title": "根拠なし", "body": "行がない", "lines": []},
              {"title": "文字の番号", "body": "書き方の違い", "lines": ["3", "[1]"]}
            ]}
            """,
            valid,
        )!!
        assertEquals("会議の日程を決めた。", draft.summary)
        assertEquals(
            listOf(HighlightDraft("日程", "水曜に決まった", listOf(2)), HighlightDraft("文字の番号", "書き方の違い", listOf(3, 1))),
            draft.highlights,
        )
    }

    @Test
    fun atMostThreeHighlightsAndTextsAreCut() {
        val highlight = """{"title": "${"見".repeat(50)}", "body": "${"本".repeat(300)}", "lines": [1]}"""
        val draft = parseSummaryOutput(
            """{"summary": "${"要".repeat(500)}", "highlights": [$highlight, $highlight, $highlight, $highlight]}""",
            valid,
        )!!
        assertEquals(3, draft.highlights.size)
        assertEquals(MAX_SUMMARY_CHARS, draft.summary.length)
        assertEquals(MAX_TITLE_CHARS, draft.highlights[0].title.length)
        assertEquals(MAX_BODY_CHARS, draft.highlights[0].body.length)
        assertTrue(draft.summary.endsWith("…"))
    }

    @Test
    fun anAnswerThatIsNotOfTheSchemaIsNull() {
        assertNull(parseSummaryOutput("要約できません", valid))
        assertNull(parseSummaryOutput("""{"highlights": []}""", valid))
        assertNull(parseSummaryOutput("""{"summary": "  "}""", valid))
        assertNull(parseSummaryOutput("""["summary"]""", valid))
        assertNull(parseSummaryOutput("""{"summary": "途中で""", valid))
    }

    @Test
    fun jsonInProseOrACodeFenceIsRead() {
        val draft = parseSummaryOutput("はい。\n```json\n{\"summary\": \"話した。\"}\n```", valid)!!
        assertEquals(SummaryDraft("話した。", emptyList()), draft)
    }

    @Test
    fun theSchemaJsonIsTheSwiftContract() {
        val json = SummaryPrompts.SCHEMA.toJson()
        assertTrue(json.startsWith("""{"type":"object","name":"HourSummary","properties":[{"name":"summary","schema":{"type":"string""""), json)
        assertTrue(""""type":"array","items":{"type":"object","name":"Highlight"""" in json, json)
        assertTrue(""""maxItems":3""" in json && """"maxItems":5""" in json && """{"type":"integer","description":"根拠の行番号"}""" in json, json)
    }
}
