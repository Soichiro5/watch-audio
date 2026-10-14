package app.watchaudio.harness

import app.watchaudio.core.db.SegmentRecord

/**
 * One conversation as the summarizer's input: transcript segments as the app stores them, and the
 * script lines (1-based, [ScriptLine.number]) each segment stands for, to check citations.
 */
data class SummaryInput(
    val conversation: String,
    val segments: List<SegmentRecord>,
    /** Segment id → script lines it covers (empty for a segment that overlaps no utterance). */
    val scriptLines: Map<Long, Set<Int>>,
) {
    val chars: Int get() = segments.sumOf { it.text.length }
}

/** Builds [SummaryInput]s from the scripts and from the harness's ASR output (#197, design §7.1). */
object SummaryInputs {
    /** Start of every conversation (an arbitrary hour; the summarizer only sees text). */
    const val START_EPOCH_MS = TestSetEvaluator.START_EPOCH_MS

    private val SENTENCE_END = Regex("(?<=[。？！?!])")

    /**
     * The script text itself (an error-free transcript): one segment per sentence like the ASR's,
     * with made-up but realistic timing (about 150 ms per character, 400 ms between sentences), so
     * [app.watchaudio.core.summary.PromptLines] joins them as it would join real segments.
     */
    fun fromScript(conversation: CorpusConversation): SummaryInput {
        val segments = mutableListOf<SegmentRecord>()
        val lines = mutableMapOf<Long, Set<Int>>()
        var at = START_EPOCH_MS
        for (line in conversation.lines) {
            for (sentence in SENTENCE_END.split(line.text).map { it.trim() }.filter { it.isNotEmpty() }) {
                val id = segments.size + 1L
                val end = at + 500 + sentence.length * 150L
                segments += SegmentRecord(id, "script", at, end, sentence, "ja", "script")
                lines[id] = setOf(line.number)
                at = end + 400
            }
        }
        return SummaryInput(conversation.id, segments, lines)
    }

    /**
     * The stored segments of one recording of the test audio set (`segments.tsv` of `evaluate`,
     * times relative to the recording), split into conversations as the CER scoring does
     * ([scoreRecording]): each segment goes to the conversation whose time span (±[marginMs]) it
     * overlaps most; segments outside every conversation are dropped. A segment stands for the
     * script lines whose ground-truth utterances it overlaps in time.
     *
     * Conversations of [recording] that are not in [conversations] (English, ReazonSpeech) are
     * skipped; one without any segment is returned with none (the ASR missed it).
     */
    fun fromAsr(
        recording: TestRecording,
        hypotheses: List<Hypothesis>,
        conversations: List<CorpusConversation>,
        marginMs: Long = 1000,
    ): List<SummaryInput> {
        val byId = conversations.associateBy { it.id }
        val groups = recording.segments.groupBy { it.group }.filterKeys { it in byId }.mapValues { (_, truth) -> truth.sortedBy { it.startMs } }
        val lineOf = groups.mapValues { (id, truth) -> scriptLineNumbers(truth, byId.getValue(id)) }
        val windows = groups.mapValues { (_, truth) -> (truth.minOf { it.startMs } - marginMs) to (truth.maxOf { it.endMs } + marginMs) }
        val assigned = mutableMapOf<String, MutableList<Hypothesis>>()
        for (h in hypotheses.sortedBy { it.startMs }) {
            val best = windows.maxByOrNull { (_, w) -> overlapMs(h.startMs, h.endMs, w.first, w.second) } ?: continue
            if (overlapMs(h.startMs, h.endMs, best.value.first, best.value.second) > 0) assigned.getOrPut(best.key) { mutableListOf() } += h
        }
        return groups.keys.map { id ->
            val truth = groups.getValue(id)
            val numbers = lineOf.getValue(id)
            val segments = mutableListOf<SegmentRecord>()
            val lines = mutableMapOf<Long, Set<Int>>()
            for (h in assigned[id].orEmpty()) {
                val segmentId = segments.size + 1L
                segments += SegmentRecord(
                    segmentId, recording.id, START_EPOCH_MS + h.startMs, START_EPOCH_MS + h.endMs, h.text, h.lang, "asr",
                )
                lines[segmentId] = truth.indices.filter { overlapMs(h.startMs, h.endMs, truth[it].startMs, truth[it].endMs) > 0 }
                    .mapTo(sortedSetOf()) { numbers[it] }
            }
            SummaryInput(id, segments, lines)
        }
    }

    /**
     * The script line of each ground-truth utterance of a conversation (in recording order): the
     * test set has one utterance per script line, in order, possibly only a part of the script
     * (the committed fixtures). Fails when the texts do not line up.
     */
    fun scriptLineNumbers(truth: List<TruthSegment>, conversation: CorpusConversation): List<Int> {
        var next = 0
        return truth.sortedBy { it.startMs }.map { utterance ->
            val text = CerText.normalize(utterance.text)
            val found = (next until conversation.lines.size).firstOrNull { CerText.normalize(conversation.lines[it].text) == text }
                ?: throw IllegalArgumentException("${conversation.id}: utterance '${utterance.text}' is not a script line after line $next")
            next = found + 1
            conversation.lines[found].number
        }
    }

    private fun overlapMs(a0: Long, a1: Long, b0: Long, b1: Long) = minOf(a1, b1) - maxOf(a0, b0)
}
