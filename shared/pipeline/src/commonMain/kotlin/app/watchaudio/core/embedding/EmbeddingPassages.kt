package app.watchaudio.core.embedding

/** Segments `[firstIndex, firstIndex + count)` of a chunk, embedded as one passage. */
data class PassageSpan(val firstIndex: Int, val count: Int) {
    val lastIndex: Int get() = firstIndex + count - 1
}

/**
 * Joins short segments into passages before embedding (#24). Since #68 a segment is one short
 * utterance (median ~2 s, ~12 characters), too little for a meaningful embedding and ~3 million
 * vectors a year; consecutive segments of a chunk are joined until a passage has [Rules.minTokens]
 * tokens, never beyond [Rules.maxTokens] (a longer single segment stays alone), and never across a
 * pause longer than [Rules.maxGapMs] (likely another conversation). A short last passage is added
 * to the one before it when that still fits. Pure; the token counts come from the model's
 * tokenizer.
 */
object EmbeddingPassages {
    data class Rules(
        val minTokens: Int = 32,
        val maxTokens: Int = 128,
        val maxGapMs: Long = 30_000,
    )

    /** One segment as the planner sees it. Times are epoch ms. */
    data class Segment(val startAt: Long, val endAt: Long, val tokens: Int)

    /** Passages covering every segment of [segments] (in (start, id) order) exactly once, in order. */
    fun plan(segments: List<Segment>, rules: Rules = Rules()): List<PassageSpan> {
        val spans = mutableListOf<PassageSpan>()
        val tokens = mutableListOf<Int>()
        var first = 0
        var count = 0
        var sum = 0
        fun close() {
            if (count == 0) return
            spans += PassageSpan(first, count)
            tokens += sum
            count = 0
            sum = 0
        }
        segments.forEachIndexed { i, segment ->
            if (count > 0) {
                val gap = segment.startAt - segments[i - 1].endAt
                if (gap > rules.maxGapMs || sum >= rules.minTokens || sum + segment.tokens > rules.maxTokens) close()
            }
            if (count == 0) first = i
            count++
            sum += segment.tokens
        }
        close()
        if (spans.size >= 2) {
            val last = spans.last()
            val previous = spans[spans.size - 2]
            val gap = segments[last.firstIndex].startAt - segments[previous.lastIndex].endAt
            val lastTokens = tokens.last()
            if (lastTokens < rules.minTokens && gap <= rules.maxGapMs && tokens[tokens.size - 2] + lastTokens <= rules.maxTokens) {
                spans.removeAt(spans.size - 1)
                spans[spans.size - 1] = PassageSpan(previous.firstIndex, previous.count + last.count)
            }
        }
        return spans
    }

    /** The text embedded for a passage of [texts] (its segments, in order). */
    fun text(texts: List<String>): String = texts.joinToString(" ") { it.trim() }
}
