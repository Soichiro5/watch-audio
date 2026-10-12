package app.watchaudio.core.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * [LanguageRouting] for a transcriber that finds the utterances itself and recognizes one language
 * per pass (iOS: SpeechAnalyzer / SpeechTranscriber, #170), instead of recognizing the spans of a
 * shared VAD one by one like Android ([transcribeSpeech]).
 *
 * The primary (Japanese) pass hears the whole input. The second (English) pass then hears, in one
 * go, only what the primary pass may have got wrong:
 * - each primary segment that [LanguageRouting.suspectsOtherLanguage] (too few letters for its
 *   length, or mostly katakana and Latin letters), and
 * - each stretch of at least [minGapMs] where the primary pass wrote nothing: English that the
 *   Japanese model could not make sense of may leave no segment at all, where a VAD span would
 *   have had blank text.
 *
 * Each is widened by [paddingMs]; overlapping ones form one [Region]. The regions are cut out of the
 * input and joined with [separatorMs] of silence between them ([regionParts]), and the second pass's
 * segments are mapped back to the region they fall in ([assign]). A region's second-pass text
 * replaces the suspect primary segments in it when [LanguageRouting.keeps] it (the second pass states
 * its language in [TimedText.lang]); otherwise the primary segments stay ([merge]). Primary segments
 * that are not suspect are never replaced, so Japanese speech only costs the second pass on sparse
 * segments and pauses.
 */
data class SegmentRouting(
    val routing: LanguageRouting = LanguageRouting.DEFAULT,
    /** Shortest stretch without primary text that the second pass also hears. */
    val minGapMs: Long = 1_000,
    /** Audio added before and after each suspect segment or gap. */
    val paddingMs: Long = 300,
    /** Silence between two regions in the second pass's input, so their speech is not joined. */
    val separatorMs: Long = 1_000,
) {
    init {
        require(minGapMs > 0 && paddingMs >= 0 && separatorMs >= 0) { "invalid routing $this" }
    }

    /**
     * One stretch `[startMs, endMs)` of the input (ms from its start) for the second pass, with the
     * indices of the suspect primary segments in it ([suspects], possibly none: a pause).
     */
    data class Region(val startMs: Long, val endMs: Long, val suspects: List<Int>) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * Transcribes [parts] (see [Transcriber.transcribe]) with [primary], then hears the [regions] of
     * its result with [second] and keeps what [merge] keeps. [second] is not called when there is no
     * region. Times of the result are relative to the start of [parts].
     */
    suspend fun transcribe(
        parts: List<AudioPart>,
        primary: suspend (List<AudioPart>) -> List<TimedText>,
        second: suspend (List<AudioPart>) -> List<TimedText>,
    ): List<TimedText> {
        val first = primary(parts).sortedBy { it.startMs }
        val regions = regions(first, parts.sumOf { it.durationMs })
        if (regions.isEmpty()) return first
        return merge(first, regions, assign(second(regionParts(parts, regions)), regions))
    }

    /** The stretches of an input of [totalMs] that the second pass hears, given the [primary] segments (sorted by start). */
    fun regions(primary: List<TimedText>, totalMs: Long): List<Region> {
        val candidates = mutableListOf<Region>()
        var covered = 0L
        primary.forEachIndexed { index, segment ->
            if (segment.startMs - covered >= minGapMs) candidates += Region(covered, segment.startMs, emptyList())
            val seconds = (segment.endMs - segment.startMs) / 1000f
            if (routing.suspectsOtherLanguage(Recognition(segment.text, segment.confidence, segment.lang), seconds)) {
                candidates += Region(segment.startMs, segment.endMs, listOf(index))
            }
            covered = max(covered, segment.endMs)
        }
        if (totalMs - covered >= minGapMs) candidates += Region(covered, totalMs, emptyList())

        val merged = mutableListOf<Region>()
        for (candidate in candidates.sortedBy { it.startMs }) {
            val start = (candidate.startMs - paddingMs).coerceIn(0, totalMs)
            val end = (candidate.endMs + paddingMs).coerceIn(start, totalMs)
            if (end <= start) continue
            val last = merged.lastOrNull()
            if (last != null && start <= last.endMs) {
                merged[merged.lastIndex] = Region(last.startMs, max(last.endMs, end), last.suspects + candidate.suspects)
            } else {
                merged += Region(start, end, candidate.suspects)
            }
        }
        return merged
    }

    /**
     * The second pass's input: each region cut out of [parts] (see [sliceParts]), with [separatorMs]
     * of silence between two regions. The silence is a range past the end of the first part's file,
     * which a [Transcriber] pads with silence.
     */
    fun regionParts(parts: List<AudioPart>, regions: List<Region>): List<AudioPart> {
        val silence = AudioPart(parts.first().file, SILENCE_FROM_MS, SILENCE_FROM_MS + separatorMs)
        return regions.flatMapIndexed { index, region ->
            val audio = sliceParts(parts, region.startMs, region.endMs)
            if (index > 0 && separatorMs > 0) listOf(silence) + audio else audio
        }
    }

    /**
     * The second pass's segments ([heard], times in its input from [regionParts]) per region, in
     * input times: each goes to the region it overlaps most and is clipped to it; segments that lie
     * only in the silence between regions are dropped.
     */
    fun assign(heard: List<TimedText>, regions: List<Region>): List<List<TimedText>> {
        val offsets = regions.runningFold(0L) { offset, region -> offset + region.durationMs + separatorMs }
        val assigned = List(regions.size) { mutableListOf<TimedText>() }
        for (segment in heard) {
            var best = -1
            var bestOverlap = 0L
            regions.forEachIndexed { index, region ->
                val from = offsets[index]
                val overlap = min(segment.endMs, from + region.durationMs) - max(segment.startMs, from)
                if (overlap > bestOverlap) {
                    best = index
                    bestOverlap = overlap
                }
            }
            if (best < 0) continue
            val region = regions[best]
            val from = offsets[best]
            assigned[best] += segment.copy(
                startMs = region.startMs + (segment.startMs - from).coerceIn(0, region.durationMs),
                endMs = region.startMs + (segment.endMs - from).coerceIn(0, region.durationMs),
            )
        }
        return assigned
    }

    /**
     * The [primary] segments, with each region's suspect ones replaced by the region's [heard]
     * segments when [LanguageRouting.keeps] their joined text (with their length-weighted
     * confidence and the language of the first), sorted by start. Heard segments lying mostly
     * inside a primary segment that is not suspect are left out first: they are the padding
     * re-hearing speech the primary pass already wrote.
     */
    fun merge(primary: List<TimedText>, regions: List<Region>, heard: List<List<TimedText>>): List<TimedText> {
        val suspects = regions.flatMapTo(mutableSetOf()) { it.suspects }
        val kept = primary.filterIndexed { index, _ -> index !in suspects }
        val replaced = mutableSetOf<Int>()
        val added = mutableListOf<TimedText>()
        regions.forEachIndexed { index, region ->
            // What the second pass heard mostly inside a primary segment that stays (the padding
            // reaching into Japanese the primary pass wrote well) is not a new utterance.
            val texts = heard[index].filterNot { segment -> kept.any { overlapMs(it, segment) * 2 > segment.endMs - segment.startMs } }
            if (texts.isEmpty()) return@forEachIndexed
            val recognition = Recognition(texts.joinToString(" ") { it.text }, weightedConfidence(texts), texts.first().lang)
            if (routing.keeps(recognition)) {
                replaced += region.suspects
                added += texts
            }
        }
        return (primary.filterIndexed { index, _ -> index !in replaced } + added).sortedBy { it.startMs }
    }

    companion object {
        /**
         * Start of the silent [AudioPart]s between regions: far past the end of any chunk (~11 days),
         * yet small enough for sample positions of any rate to fit in 64 bits.
         */
        const val SILENCE_FROM_MS = 1_000_000_000L
    }
}

/**
 * `[fromMs, toMs)` of the concatenation of [parts], as ranges of the same files (empty pieces
 * left out). Each part counts with its declared length, as in [Transcriber.transcribe].
 */
fun sliceParts(parts: List<AudioPart>, fromMs: Long, toMs: Long): List<AudioPart> {
    val slices = mutableListOf<AudioPart>()
    var offset = 0L
    for (part in parts) {
        val from = max(fromMs, offset)
        val to = min(toMs, offset + part.durationMs)
        if (to > from) slices += AudioPart(part.file, part.fromMs + (from - offset), part.fromMs + (to - offset))
        offset += part.durationMs
    }
    return slices
}

/** Mean confidence of [texts] weighted by text length, or null if none states one. */
private fun weightedConfidence(texts: List<TimedText>): Float? {
    var weighted = 0f
    var weight = 0
    for (text in texts) {
        val confidence = text.confidence ?: continue
        val length = FalsePositiveFilter.textLength(text.text).coerceAtLeast(1)
        weighted += confidence * length
        weight += length
    }
    return if (weight > 0) weighted / weight else null
}

/** How long [a] and [b] overlap, in ms (0 if they do not). */
private fun overlapMs(a: TimedText, b: TimedText): Long = max(0L, min(a.endMs, b.endMs) - max(a.startMs, b.startMs))
