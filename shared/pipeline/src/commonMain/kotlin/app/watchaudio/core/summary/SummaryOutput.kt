package app.watchaudio.core.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A checked answer: a summary and highlights whose [HighlightDraft.lines] all exist in the prompt. */
data class SummaryDraft(val summary: String, val highlights: List<HighlightDraft>)

/** A highlight citing prompt line numbers (never empty). */
data class HighlightDraft(val title: String, val body: String, val lines: List<Int>)

/** Longest summary kept; a longer one is cut (the model ignored the length it was asked for). */
const val MAX_SUMMARY_CHARS = 400
const val MAX_TITLE_CHARS = 40
const val MAX_BODY_CHARS = 200

private val lenient = Json { isLenient = true }

/**
 * Parses and checks an answer of [SummaryPrompts.SCHEMA] (#27 §4.5):
 * - line numbers that are not in [validLines] (not in the prompt, or outside the hour) are dropped;
 * - a highlight left without a line, or without a title and body, is dropped; at most
 *   [SummaryPrompts.MAX_HIGHLIGHTS] are kept;
 * - texts are trimmed and cut to their maximum length.
 *
 * @return null if [json] is not such an answer at all (not JSON, no summary): the caller asks again.
 */
fun parseSummaryOutput(json: String, validLines: Set<Int>): SummaryDraft? {
    val root = runCatching { lenient.parseToJsonElement(extractJsonObject(json)) }.getOrNull() as? JsonObject ?: return null
    val summary = root.text("summary")?.let { cut(it, MAX_SUMMARY_CHARS) }?.takeIf { it.isNotEmpty() } ?: return null
    val highlights = (root["highlights"] as? JsonArray).orEmpty().mapNotNull { element ->
        val highlight = element as? JsonObject ?: return@mapNotNull null
        val title = highlight.text("title")?.let { cut(it, MAX_TITLE_CHARS) }.orEmpty()
        val body = highlight.text("body")?.let { cut(it, MAX_BODY_CHARS) }.orEmpty()
        val lines = (highlight["lines"] as? JsonArray).orEmpty()
            .mapNotNull { lineNumber(it) }
            .filter { it in validLines }
            .distinct()
            .take(SummaryPrompts.MAX_CITED_LINES)
        if (lines.isEmpty() || (title.isEmpty() && body.isEmpty())) return@mapNotNull null
        HighlightDraft(title.ifEmpty { cut(body, MAX_TITLE_CHARS) }, body.ifEmpty { title }, lines)
    }.take(SummaryPrompts.MAX_HIGHLIGHTS)
    return SummaryDraft(summary, highlights)
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

/** 12, "12" and "[12]" all name line 12. */
private fun lineNumber(element: kotlinx.serialization.json.JsonElement): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.trim().removePrefix("[").removeSuffix("]").trim().toIntOrNull()
}

/** The JSON object in [text], also when a model wraps it in prose or a code fence. */
private fun extractJsonObject(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start in 0..<end) text.substring(start, end + 1) else text
}

private fun cut(text: String, max: Int): String {
    val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length > max) flat.take(max - 1) + "…" else flat
}
