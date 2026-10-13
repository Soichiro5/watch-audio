package app.watchaudio.core.summary

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.HOUR_MS
import app.watchaudio.core.db.HourSummaryRecord
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.timeline.DayWindow

/** An hour with less than this many characters of speech is 「発話が少ない」: no LLM call (#27 §4.2). */
const val LITTLE_SPEECH_CHARS = 200

/**
 * How much was said in an hour, computed from the segments (never by the LLM, #27 §4.2), for the
 * height of the hour's bar (#28) and for deciding whether to summarize.
 */
data class HourDensity(val speechMs: Long, val chars: Int) {
    /**
     * 0..1: the mean of the speech time against [FULL_SPEECH_MS] and of the characters against
     * [FULL_CHARS] (both capped at 1), so a long quiet monologue and a short dense talk both show.
     */
    val score: Float
        get() = ((speechMs.toFloat() / FULL_SPEECH_MS).coerceAtMost(1f) + (chars.toFloat() / FULL_CHARS).coerceAtMost(1f)) / 2

    companion object {
        /** Speech time that counts as a full hour of talk. */
        const val FULL_SPEECH_MS = 15 * 60_000L

        /** Characters that count as a full hour of talk (the average talking hour, 02 §9.3). */
        const val FULL_CHARS = 6_500

        /** Of the segments that start in `[hourStart, hourStart + 1 h)`; speech is clipped to the hour. */
        fun of(segments: List<SegmentRecord>, hourStart: Long): HourDensity {
            val hourEnd = hourStart + HOUR_MS
            val inHour = segments.filter { it.startAt in hourStart..<hourEnd }
            return HourDensity(
                speechMs = inHour.sumOf { (minOf(it.endAt, hourEnd) - it.startAt).coerceAtLeast(0) },
                chars = inHour.sumOf { it.text.trim().length },
            )
        }
    }
}

/** What to do about an hour of a day ([planDay]). */
enum class HourAction {
    /** Nothing was said and there is no summary: the hour is not listed. */
    NOTHING,

    /** Some of the hour's audio is still waiting for transcription. */
    WAIT_TRANSCRIPTION,

    /** The hour is not over yet. */
    HOUR_NOT_OVER,

    /** Too little speech: stored as [HourSummaryState.LITTLE_SPEECH] without calling the LLM. */
    LITTLE_SPEECH,

    /** Needs the LLM: no summary yet, the segments changed (stale), the last attempt failed or the prompts changed. */
    SUMMARIZE,

    /** The stored summary is current. */
    UP_TO_DATE,
}

/** One hour of a day with what is stored and what to do. */
data class HourPlan(
    val hourStart: Long,
    val density: HourDensity,
    val summary: HourSummaryRecord?,
    val action: HourAction,
) {
    val hourEnd: Long get() = hourStart + HOUR_MS
}

/**
 * The hours of [window] (local hours; 23 or 25 on DST changes) with what [SummaryRun] would do
 * for each (#27 §4.4): hours with pending transcription and the hour that is not over are left for
 * later; a summary is made again when its segments changed, when it failed (the user asked again)
 * or when [SummaryPrompts.VERSION] moved; refusals are not retried.
 *
 * @param chunks chunks overlapping the day (for pending transcription).
 * @param segments segments that start in the day.
 * @param summaries stored summaries of the day's hours.
 */
fun planDay(
    window: DayWindow,
    chunks: List<ChunkRecord>,
    segments: List<SegmentRecord>,
    summaries: List<HourSummaryRecord>,
    now: Long,
): List<HourPlan> {
    val byHour = summaries.associateBy { it.hourStart }
    val sorted = segments.sortedBy { it.startAt }
    return generateSequence(window.start) { it + HOUR_MS }.takeWhile { it < window.end }.map { hourStart ->
        val hourEnd = hourStart + HOUR_MS
        val hourSegments = sorted.filter { it.startAt in hourStart..<hourEnd }
        val density = HourDensity.of(hourSegments, hourStart)
        val summary = byHour[hourStart]
        val pending = chunks.any {
            it.startedAt < hourEnd && it.endedAt > hourStart && (it.state == ChunkState.RECEIVED || it.state == ChunkState.TRANSCRIBING)
        }
        val action = when {
            hourSegments.isEmpty() && summary == null && !pending -> HourAction.NOTHING
            pending -> HourAction.WAIT_TRANSCRIPTION
            hourEnd > now -> HourAction.HOUR_NOT_OVER
            density.chars < LITTLE_SPEECH_CHARS ->
                if (summary?.state == HourSummaryState.LITTLE_SPEECH && !summary.stale) HourAction.UP_TO_DATE else HourAction.LITTLE_SPEECH
            summary == null || summary.stale || summary.state == HourSummaryState.FAILED ||
                summary.state == HourSummaryState.LITTLE_SPEECH || summary.promptVersion != SummaryPrompts.VERSION -> HourAction.SUMMARIZE
            else -> HourAction.UP_TO_DATE
        }
        HourPlan(hourStart, density, summary, action)
    }.toList()
}
