package app.watchaudio.harness

import app.watchaudio.core.summary.HighlightDraft
import app.watchaudio.core.summary.OutputSchema
import app.watchaudio.core.summary.SummaryDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The automatic metrics of the hour-summary evaluation (#197, docs/research/on-device-llm.md §7.2).
 * All deterministic; what keyword matching cannot judge (paraphrases, naturalness, hallucinations
 * not listed in `mustNot`) is left to a person reading `summaries.md`.
 */
object SummaryScoring {
    /**
     * A highlight's citation is correct when one of its cited script lines is within this many
     * lines of a supporting line of a fact the highlight states: the line before or after (a
     * question and its answer) says the same thing as often as not.
     */
    const val CITATION_TOLERANCE_LINES = 1

    /** A highlight and what its citation check found. */
    data class Citation(
        val highlight: HighlightDraft,
        /** Script lines its prompt lines stand for. */
        val citedLines: Set<Int>,
        /** Facts whose keys the highlight's title and body match. */
        val facts: List<String>,
        /** Null when the highlight states no listed fact (not judged). */
        val correct: Boolean?,
    )

    /** The score of one summary against its conversation's facts. */
    data class Score(
        val covered: List<String>,
        val missed: List<String>,
        /** Ids of `mustNot` claims the summary makes. */
        val hallucinations: List<String>,
        val citations: List<Citation>,
    ) {
        val facts: Int get() = covered.size + missed.size
    }

    /**
     * Scores [draft] (null when the model refused or failed: every fact missed) against [facts].
     *
     * - **Fact recall**: a fact is covered when the summary text — the summary and every
     *   highlight's title and body — matches its keys ([KeyTerms]).
     * - **Hallucinations**: the `mustNot` claims the summary text matches.
     * - **Citations**: each highlight is matched against the facts by its own title and body; when
     *   it states one or more, its citation is correct if a cited script line ([citedLines]) is
     *   within [CITATION_TOLERANCE_LINES] of a line of one of those facts.
     */
    fun score(facts: ConversationFacts, draft: SummaryDraft?, citedLines: (HighlightDraft) -> Set<Int>): Score {
        if (draft == null) return Score(emptyList(), facts.facts.map { it.id }, emptyList(), emptyList())
        val whole = CerText.normalize(summaryText(draft))
        val (covered, missed) = facts.facts.partition { KeyTerms.matchesNormalized(it.keys, whole) }
        val hallucinations = facts.mustNot.filter { KeyTerms.matchesNormalized(it.keys, whole) }.map { it.id }
        val citations = draft.highlights.map { highlight ->
            val stated = facts.facts.filter { KeyTerms.matches(it.keys, highlight.title + "\n" + highlight.body) }
            val cited = citedLines(highlight)
            val correct = if (stated.isEmpty()) {
                null
            } else {
                stated.any { fact -> fact.lines.any { line -> cited.any { kotlin.math.abs(it - line) <= CITATION_TOLERANCE_LINES } } }
            }
            Citation(highlight, cited, stated.map { it.id }, correct)
        }
        return Score(covered.map { it.id }, missed.map { it.id }, hallucinations, citations)
    }

    /** Everything the user would read: the summary, then each highlight's title and body. */
    fun summaryText(draft: SummaryDraft): String = buildString {
        appendLine(draft.summary)
        draft.highlights.forEach { appendLine(it.title).appendLine(it.body) }
    }

    private val strict = Json

    /**
     * Whether a model's answer has [schema]'s shape exactly, the way the app reads it: the JSON
     * object in the text (a model may wrap it in prose or a code fence, which the app tolerates)
     * parsed strictly, every property present with the right type (extra properties allowed), lists
     * no longer than their maximum, integers as JSON numbers.
     */
    fun conformsToSchema(answer: String, schema: OutputSchema): Boolean {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return false
        val element = runCatching { strict.parseToJsonElement(answer.substring(start, end + 1)) }.getOrNull() ?: return false
        return conforms(element, schema)
    }

    fun conforms(element: JsonElement, schema: OutputSchema): Boolean = when (schema) {
        is OutputSchema.Text -> element is JsonPrimitive && element.isString
        is OutputSchema.Integer -> element is JsonPrimitive && !element.isString && element.intOrNull != null
        is OutputSchema.ListOf -> element is JsonArray && element.size <= schema.maxItems && element.all { conforms(it, schema.items) }
        is OutputSchema.Object -> element is JsonObject && schema.properties.all { p -> element[p.name]?.let { conforms(it, p.schema) } == true }
    }
}

/** Median, 90th percentile and maximum (nearest rank) of some durations, in ms. */
data class LatencyStats(val count: Int, val medianMs: Long, val p90Ms: Long, val maxMs: Long, val totalMs: Long) {
    companion object {
        fun of(values: List<Long>): LatencyStats? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            fun rank(p: Double) = sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            return LatencyStats(sorted.size, rank(0.5), rank(0.9), sorted.last(), sorted.sum())
        }
    }
}
