package app.watchaudio.core.summary

import app.watchaudio.core.db.SegmentRecord

/**
 * One numbered line of an hour's transcript as the LLM sees it (#27 §4.2): one or more short
 * consecutive segments joined. [number] only exists in the prompt; which segments a line stands for
 * stays here ([segmentIds]), so database ids never reach the model and it cannot invent them.
 */
data class PromptLine(
    val number: Int,
    val text: String,
    val segmentIds: List<Long>,
    val startAt: Long,
    /** Within [PromptLines.MARK_WINDOW_MS] of a mark the user set: shown with ★ and preferred for highlights. */
    val marked: Boolean,
) {
    /** `[12] text`, or `[12]★ text` near a mark. */
    fun render(): String = if (marked) "[$number]★ $text" else "[$number] $text"
}

/** Turns an hour's segments into [PromptLine]s. */
object PromptLines {
    /** Segments closer than this are joined into one line. */
    const val MAX_JOIN_GAP_MS = 2_000L

    /** A line grows by joining up to about this many characters. */
    const val MAX_LINE_CHARS = 60

    /** A single segment longer than this is cut (a recognizer can return a long run of text). */
    const val MAX_SEGMENT_CHARS = 300

    /** Lines whose segments start or end within this of a mark get ★. */
    const val MARK_WINDOW_MS = 120_000L

    /**
     * Lines of [segments] (sorted by start, then id, here) numbered from 1; [marks] are mark times
     * (epoch ms). Blank segments are skipped.
     */
    fun build(segments: List<SegmentRecord>, marks: List<Long>): List<PromptLine> {
        val sorted = segments.sortedWith(compareBy({ it.startAt }, { it.id })).filter { it.text.isNotBlank() }
        val groups = mutableListOf<MutableList<SegmentRecord>>()
        var length = 0
        for (segment in sorted) {
            val open = groups.lastOrNull()
            val text = clean(segment.text)
            val joins = open != null &&
                segment.startAt - open.last().endAt < MAX_JOIN_GAP_MS &&
                length + 1 + text.length <= MAX_LINE_CHARS
            if (joins) {
                open.add(segment)
                length += 1 + text.length
            } else {
                groups.add(mutableListOf(segment))
                length = text.length
            }
        }
        return groups.mapIndexed { index, group ->
            PromptLine(
                number = index + 1,
                text = group.joinToString(" ") { clean(it.text) },
                segmentIds = group.map { it.id },
                startAt = group.first().startAt,
                marked = marks.any { mark -> group.any { mark >= it.startAt - MARK_WINDOW_MS && mark <= it.endAt + MARK_WINDOW_MS } },
            )
        }
    }

    /** One line of text: no line breaks (they would break the numbering), long runs cut. */
    private fun clean(text: String): String {
        val flat = text.replace('\r', ' ').replace('\n', ' ').trim()
        return if (flat.length > MAX_SEGMENT_CHARS) flat.take(MAX_SEGMENT_CHARS) + "…" else flat
    }
}
