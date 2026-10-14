package app.watchaudio.harness

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ground truth of the hour-summary evaluation (#197, `testdata/summary-eval-facts.json`): per
 * conversation of the test-audio corpus, the facts a good summary contains and the claims it must
 * not make. Lines are 1-based lines of the corpus file without its `#` header ([CorpusConversation]).
 */
@Serializable
data class SummaryFactSet(val version: Int, val conversations: List<ConversationFacts>) {
    fun of(conversation: String): ConversationFacts? = conversations.firstOrNull { it.id == conversation }

    companion object {
        const val PATH = "tools/harness/testdata/summary-eval-facts.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun load(file: File): SummaryFactSet = json.decodeFromString(serializer(), file.readText())
    }
}

@Serializable
data class ConversationFacts(
    /** The corpus file name without `.txt`, e.g. `01_weekly_meeting` (the manifest's `group`). */
    val id: String,
    val facts: List<SummaryFact>,
    val mustNot: List<SummaryClaim> = emptyList(),
)

/** A fact a summary should contain; [lines] are the script lines that say it. */
@Serializable
data class SummaryFact(val id: String, val text: String, val lines: List<Int>, val keys: List<List<String>>)

/** A claim a summary must not make (a likely hallucination: a wrong date, number, place...). */
@Serializable
data class SummaryClaim(val id: String, val text: String, val keys: List<List<String>>)

/**
 * Deterministic matching of [SummaryFact.keys] / [SummaryClaim.keys]: a list of groups, each a list
 * of alternatives. A text matches when **every** group has **an** alternative occurring in it, both
 * compared after [CerText.normalize] (NFKC, lower case, punctuation, spaces and symbols removed,
 * kanji numerals as Arabic digits: 「十二万円」「12万円」「120,000円」 all become `120000円`).
 */
object KeyTerms {
    fun matches(keys: List<List<String>>, text: String): Boolean = matchesNormalized(keys, CerText.normalize(text))

    /** [matches] for a text already normalized with [CerText.normalize]. */
    fun matchesNormalized(keys: List<List<String>>, normalized: String): Boolean =
        keys.isNotEmpty() && keys.all { group -> group.any { alternative -> CerText.normalize(alternative).let { it.isNotEmpty() && it in normalized } } }
}

/** One utterance line of a corpus script: [number] counts from 1 without the `#` header and blank lines. */
data class ScriptLine(val number: Int, val speaker: String, val text: String)

/** A conversation of `tools/test-audio/corpus` (format: `tools/test-audio/corpus.py`). */
data class CorpusConversation(val id: String, val title: String, val lines: List<ScriptLine>) {
    /** The whole conversation as one text. */
    val text: String get() = lines.joinToString("\n") { it.text }

    companion object {
        const val DIRECTORY = "tools/test-audio/corpus"

        private val RUBY = Regex("""\{([^|{}]+)\|[^{}]+\}""")

        /** Parses a script; `{text|reading}` keeps the text (the ground truth), as `corpus.py` does. */
        fun parse(id: String, script: String): CorpusConversation {
            var title = id
            val lines = mutableListOf<ScriptLine>()
            for (raw in script.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (raw.startsWith("#")) {
                    val key = raw.drop(1).substringBefore(':').trim()
                    if (key == "title") title = raw.substringAfter(':').trim()
                    continue
                }
                val speaker = raw.substringBefore(':', missingDelimiterValue = "").trim()
                val body = raw.substringAfter(':', missingDelimiterValue = "").trim()
                require(speaker.isNotEmpty() && body.isNotEmpty()) { "$id: expected '<speaker>: <text>': $raw" }
                lines += ScriptLine(lines.size + 1, speaker, RUBY.replace(body) { it.groupValues[1] })
            }
            return CorpusConversation(id, title, lines)
        }

        /** Every conversation of [directory], by file name. */
        fun loadAll(directory: File): List<CorpusConversation> =
            directory.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }.map { parse(it.nameWithoutExtension, it.readText()) }
    }
}
