package app.watchaudio.app.logic

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.protocol.ChunkEndReason

/** Timing assumptions shared by the timeline and the arrival indicator. */
object Thresholds {
    /** Holes between chunks shorter than this are encoder/rotation jitter, not gaps. */
    const val JITTER_MS = 5_000L

    /**
     * How long after recording a chunk may reasonably still be on its way: one chunk (~5 min)
     * plus the 20-minute transfer target from the architecture doc. Newer holes are "awaiting".
     */
    const val AWAITING_TRANSFER_MS = 25 * 60_000L

    /** No chunk for this long while the watch should be recording raises a warning (#43). */
    const val ARRIVAL_WARNING_MS = 10 * 60_000L
}

/** Why nothing was recorded during a gap. */
sealed interface GapCause {
    /** The recorder stopped and said why (the previous chunk's end reason). */
    data class Stopped(val reason: ChunkEndReason) : GapCause

    /** The recorder was running, but no chunk covering this span reached the phone. */
    data object Missing : GapCause

    /** Recent span whose chunk is probably still being recorded or transferred. */
    data object Awaiting : GapCause

    /** No earlier chunk at all, so nothing is known (e.g. before the first recording). */
    data object NoRecording : GapCause
}

sealed interface TimelineSpan {
    val start: Long
    val end: Long
    val durationMs: Long get() = end - start

    /** Recorded, with audio kept. */
    data class Audio(override val start: Long, override val end: Long) : TimelineSpan

    /** Recorded, but confidently silent: the watch kept the time range and dropped the audio. */
    data class Silent(override val start: Long, override val end: Long) : TimelineSpan

    data class Gap(override val start: Long, override val end: Long, val cause: GapCause) : TimelineSpan

    /** Later today; has not happened yet. */
    data class Future(override val start: Long, override val end: Long) : TimelineSpan
}

/**
 * What one day looks like: [spans] cover [window] completely, in order, with neighbours of
 * the same kind merged.
 */
data class DayTimeline(
    val window: DayWindow,
    val spans: List<TimelineSpan>,
) {
    val audioMs: Long = spans.filterIsInstance<TimelineSpan.Audio>().sumOf { it.durationMs }
    val silentMs: Long = spans.filterIsInstance<TimelineSpan.Silent>().sumOf { it.durationMs }

    /** Time the recorder was running (audio + silent). */
    val coveredMs: Long get() = audioMs + silentMs

    /** Recording coverage: [coveredMs] over the whole day (24 h, or 23/25 h on DST changes). */
    val coverage: Float get() = if (window.lengthMs <= 0) 0f else coveredMs.toFloat() / window.lengthMs

    val gaps: List<TimelineSpan.Gap> get() = spans.filterIsInstance<TimelineSpan.Gap>()

    val hasRecording: Boolean get() = coveredMs > 0
}

/**
 * Builds the timeline of [window] from the chunks overlapping it.
 *
 * @param previous the latest chunk that started before the window, if any; its end reason
 *   explains a gap at the start of the day.
 * @param now current time; the part of the window after it is [TimelineSpan.Future].
 */
fun buildDayTimeline(
    window: DayWindow,
    chunks: List<ChunkRecord>,
    previous: ChunkRecord?,
    now: Long,
): DayTimeline {
    val spans = SpanBuilder()
    val limit = now.coerceIn(window.start, window.end)
    var cursor = window.start
    // End reason of the chunk that ended last, i.e. what happened after it.
    var lastReason: ChunkEndReason? = previous?.takeIf { it.endedAt <= window.start }?.endReason

    for (chunk in chunks.sortedBy { it.startedAt }) {
        val start = maxOf(chunk.startedAt, cursor)
        val end = minOf(chunk.endedAt, window.end)
        if (end <= start) {
            // Fully inside spans already drawn (overlapping sources); still the newest news.
            if (chunk.endedAt >= cursor) lastReason = chunk.endReason
            continue
        }
        val drawFrom = if (start - cursor > Thresholds.JITTER_MS) {
            spans.add(TimelineSpan.Gap(cursor, start, gapCause(lastReason)))
            start
        } else {
            cursor // absorb rotation jitter into this chunk
        }
        spans.add(
            if (chunk.audioPath != null) TimelineSpan.Audio(drawFrom, end) else TimelineSpan.Silent(drawFrom, end),
        )
        cursor = end
        lastReason = chunk.endReason
    }

    if (limit - cursor > Thresholds.JITTER_MS) {
        val stillRunning = lastReason != null && !lastReason.startsGap
        val cause = if (stillRunning && limit == now && now - cursor <= Thresholds.AWAITING_TRANSFER_MS) {
            GapCause.Awaiting
        } else {
            gapCause(lastReason)
        }
        spans.add(TimelineSpan.Gap(cursor, limit, cause))
    } else if (limit > cursor) {
        if (spans.isEmpty()) spans.add(TimelineSpan.Gap(cursor, limit, gapCause(lastReason))) else spans.extendLastTo(limit)
    }
    // A watch clock ahead of the phone can put chunks after "now"; the future starts after them.
    spans.add(TimelineSpan.Future(maxOf(limit, cursor), window.end))

    return DayTimeline(window, spans.result())
}

private fun gapCause(lastReason: ChunkEndReason?): GapCause = when {
    lastReason == null -> GapCause.NoRecording
    lastReason.startsGap -> GapCause.Stopped(lastReason)
    else -> GapCause.Missing
}

/** Appends spans, merging a span into its predecessor when they touch and are of the same kind. */
private class SpanBuilder {
    private val spans = mutableListOf<TimelineSpan>()

    fun isEmpty() = spans.isEmpty()

    fun add(span: TimelineSpan) {
        if (span.end <= span.start) return
        val last = spans.lastOrNull()
        val merged = when {
            last == null || last.end != span.start -> null
            last is TimelineSpan.Audio && span is TimelineSpan.Audio -> TimelineSpan.Audio(last.start, span.end)
            last is TimelineSpan.Silent && span is TimelineSpan.Silent -> TimelineSpan.Silent(last.start, span.end)
            last is TimelineSpan.Gap && span is TimelineSpan.Gap && last.cause == span.cause ->
                TimelineSpan.Gap(last.start, span.end, last.cause)
            else -> null
        }
        if (merged != null) spans[spans.lastIndex] = merged else spans += span
    }

    /** Absorbs a jitter-sized hole into the previous span. */
    fun extendLastTo(end: Long) {
        val last = spans.lastOrNull() ?: return
        if (end <= last.end) return
        spans[spans.lastIndex] = when (last) {
            is TimelineSpan.Audio -> last.copy(end = end)
            is TimelineSpan.Silent -> last.copy(end = end)
            is TimelineSpan.Gap -> last.copy(end = end)
            is TimelineSpan.Future -> last.copy(end = end)
        }
    }

    fun result(): List<TimelineSpan> = spans.toList()
}
