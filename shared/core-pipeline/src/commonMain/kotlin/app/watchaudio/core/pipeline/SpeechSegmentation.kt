package app.watchaudio.core.pipeline

import kotlin.math.abs
import kotlin.math.pow

/**
 * Settings shared by every sherpa-onnx based transcriber (Android app and the JVM harness), so
 * that harness results predict on-device behaviour.
 */
object AsrSettings {
    const val SAMPLE_RATE = 16_000

    /** Tuned in the M0-5 spike: 0.3 with 0.3 s padding found 23/23 utterances (0.5: 22/23). */
    const val VAD_THRESHOLD = 0.3f
    const val VAD_MIN_SILENCE_SECONDS = 0.5f
    const val VAD_MIN_SPEECH_SECONDS = 0.25f
    const val VAD_MAX_SPEECH_SECONDS = 20f
    const val VAD_WINDOW_SIZE = 512

    /** Context added around each VAD segment; without it word onsets get clipped. */
    const val SEGMENT_PAD_SECONDS = 0.3f

    /**
     * Upper bound on one recognizer input, padding included. The ReazonSpeech Zipformer drops
     * whole sentences from long inputs (#68), so longer speech is split (see [padSpeechSpans]).
     */
    const val MAX_SEGMENT_SECONDS = 20f

    /** A near-silent pause this long inside a VAD segment splits it (turn-taking gaps). */
    const val MIN_PAUSE_SECONDS = 0.22f

    /** How far below the segment's loud frames a pause must be, in dB. */
    const val PAUSE_DB = 30f

    const val JA_MODEL_NAME = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
    const val JA_ENGINE_ID = "sherpa-zipformer-ja-reazonspeech-int8"
}

/** A detected utterance: offset into the chunk (in samples) and its samples. */
class SpeechSpan(val startSample: Int, val samples: FloatArray) {
    val startMs: Long get() = startSample * 1000L / AsrSettings.SAMPLE_RATE
    val endMs: Long get() = (startSample + samples.size) * 1000L / AsrSettings.SAMPLE_RATE
}

/**
 * Turns VAD segments (start, length in samples) into recognizer inputs cut from [audio]:
 *
 * - each segment is widened by [padSamples] on both sides with real audio, so word onsets are
 *   not clipped;
 * - neighbours whose padding overlaps are joined into one input, but only while the result
 *   stays within [maxSamples]. Where neighbours stay apart, the shared padding is divided at its
 *   quietest point, so no audio is recognized twice;
 * - a single segment longer than [maxSamples] (speech without a pause the VAD could use) is
 *   split at its quietest points into pieces of at most [maxSamples].
 *
 * No returned span is longer than [maxSamples] (unless [maxSamples] is not positive), and spans
 * never overlap.
 */
fun padSpeechSpans(
    vadSegments: List<Pair<Int, Int>>,
    audio: FloatArray,
    padSamples: Int = (AsrSettings.SEGMENT_PAD_SECONDS * AsrSettings.SAMPLE_RATE).toInt(),
    maxSamples: Int = (AsrSettings.MAX_SEGMENT_SECONDS * AsrSettings.SAMPLE_RATE).toInt(),
    minPauseSamples: Int = (AsrSettings.MIN_PAUSE_SECONDS * AsrSettings.SAMPLE_RATE).toInt(),
    pauseDb: Float = AsrSettings.PAUSE_DB,
): List<SpeechSpan> {
    val limit = if (maxSamples > 0) maxSamples else Int.MAX_VALUE
    val speech = vadSegments
        .map { (start, length) -> start.coerceIn(0, audio.size) to (start + length).coerceIn(0, audio.size) }
        .filter { (start, end) -> end > start }
        .sortedBy { it.first }

    // Join neighbours whose padding overlaps while the padded result fits the limit.
    val groups = mutableListOf<IntArray>() // raw speech [start, end) of each future input
    for ((start, end) in speech) {
        val last = groups.lastOrNull()
        val overlaps = last != null && start - padSamples < last[1] + padSamples
        val joined = last != null && overlaps &&
            minOf(audio.size, maxOf(last[1], end) + padSamples) - maxOf(0, last[0] - padSamples) <= limit
        if (joined) last!![1] = maxOf(last[1], end) else groups += intArrayOf(start, end)
    }

    // Pad each group; where the padding of two groups overlaps, cut at the quietest point.
    val ranges = mutableListOf<IntArray>()
    for (group in groups) {
        val next = intArrayOf(maxOf(0, group[0] - padSamples), minOf(audio.size, group[1] + padSamples))
        val previous = ranges.lastOrNull()
        if (previous != null && next[0] < previous[1]) {
            val cut = quietestPoint(audio, maxOf(next[0], previous[0]), previous[1])
            previous[1] = cut
            next[0] = cut
        }
        if (next[1] > next[0]) ranges += next
    }

    return ranges
        .flatMap { splitAtPauses(audio, it[0], it[1], minPauseSamples, pauseDb) }
        .flatMap { splitLongRange(audio, it.first, it.last, limit) }
        .filter { it.last > it.first }
        .map { SpeechSpan(it.first, audio.copyOfRange(it.first, it.last)) }
}

/** Length of the frames whose energy [splitAtPauses] compares (10 ms). */
private const val PAUSE_FRAME = AsrSettings.SAMPLE_RATE / 100

/**
 * Splits `[from, to)` in the middle of every pause inside it: a run of at least
 * [minPauseSamples] whose 10 ms frames are all [pauseDb] dB or more below the loud frames (95th
 * percentile) of the range. Catches turn-taking gaps the VAD rides through, e.g. after
 * reverberant speech, where Silero stays above its threshold for over a second.
 */
internal fun splitAtPauses(audio: FloatArray, from: Int, to: Int, minPauseSamples: Int, pauseDb: Float): List<IntRange> {
    val frames = (to - from) / PAUSE_FRAME
    if (minPauseSamples <= 0 || frames < 3) return listOf(from..to)
    val energy = DoubleArray(frames) { f ->
        var sum = 0.0
        for (i in from + f * PAUSE_FRAME until from + (f + 1) * PAUSE_FRAME) sum += audio[i].toDouble() * audio[i]
        sum / PAUSE_FRAME
    }
    val quiet = energy.sortedArray()[frames * 95 / 100] * 10.0.pow(-pauseDb / 10.0)
    val minFrames = (minPauseSamples + PAUSE_FRAME - 1) / PAUSE_FRAME
    val cuts = mutableListOf<Int>()
    var runStart = -1
    for (f in 0..frames) {
        val isQuiet = f < frames && energy[f] <= quiet
        if (isQuiet && runStart < 0) runStart = f
        if (!isQuiet && runStart >= 0) {
            // Quiet runs at the edges are padding, not a gap between two utterances.
            if (f - runStart >= minFrames && runStart > 0 && f < frames) cuts += from + (runStart + f) * PAUSE_FRAME / 2
            runStart = -1
        }
    }
    return (listOf(from) + cuts + to).zipWithNext { a, b -> a..b }
}

/** Splits `[from, to)` at its quietest points into pieces no longer than [limit]. */
private fun splitLongRange(audio: FloatArray, from: Int, to: Int, limit: Int): List<IntRange> {
    val pieces = mutableListOf<IntRange>()
    var start = from
    while (to - start > limit) {
        // Keep every piece, including the last, at least a quarter of the limit long.
        val minPiece = maxOf(1, limit / 4)
        val cut = quietestPoint(audio, start + minPiece, minOf(start + limit, to - minPiece))
        pieces += start..cut
        start = cut
    }
    pieces += start..to
    return pieces
}

/** Half the window over which the loudness of a candidate cut point is measured (50 ms). */
private const val QUIET_HALF_WINDOW = AsrSettings.SAMPLE_RATE / 20

/** Distance between candidate cut points (10 ms). */
private const val QUIET_STEP = AsrSettings.SAMPLE_RATE / 100

/**
 * The position in `[from, to]` with the least energy in the ±50 ms around it; among equally
 * quiet positions, the one nearest the middle.
 */
internal fun quietestPoint(audio: FloatArray, from: Int, to: Int): Int {
    if (to <= from) return from
    val middle = (from + to) / 2
    var best = from
    var bestEnergy = Double.MAX_VALUE
    var position = from
    while (true) {
        var energy = 0.0
        for (i in maxOf(0, position - QUIET_HALF_WINDOW) until minOf(audio.size, position + QUIET_HALF_WINDOW)) {
            energy += audio[i].toDouble() * audio[i]
        }
        val better = energy < bestEnergy ||
            (energy == bestEnergy && abs(position - middle) < abs(best - middle))
        if (better) {
            best = position
            bestEnergy = energy
        }
        if (position == to) break
        position = minOf(to, position + QUIET_STEP)
    }
    return best
}
