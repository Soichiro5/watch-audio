package app.watchaudio.core.pipeline

import kotlin.math.abs
import kotlin.math.pow

/**
 * Audio format shared by every sherpa-onnx based transcriber (Android app and the JVM harness):
 * the decoded audio, the Silero VAD and the recognizer all work at [SAMPLE_RATE]. The model is
 * described by [AsrModelSpec], speech detection and segmentation by [VadSettings].
 */
object AsrSettings {
    const val SAMPLE_RATE = 16_000
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
 * - each input is split in the middle of every pause of at least [minPauseSamples], so that
 *   turns of a conversation are recognized apart. A pause is near-silent ([pauseDb] below the
 *   loud frames within [pauseLoudWindowSamples]) or, in noise, close to the noise floor (within
 *   [pauseFloorDb], at least [pauseMinContrastDb] below the loud frames, for at least
 *   [minNoisyPauseSamples]);
 * - anything still longer than [maxSamples] (speech without such a pause) is split at its
 *   quietest points into pieces of at most [maxSamples].
 *
 * No returned span is longer than [maxSamples] (unless [maxSamples] is not positive), and spans
 * never overlap. The noise floor is the [noiseFloorPercentile]th percentile of the frame energies
 * over the range and [noiseFloorContextSamples] on each side ([noiseFloor]).
 */
fun padSpeechSpans(
    vadSegments: List<Pair<Int, Int>>,
    audio: FloatArray,
    padSamples: Int = VadSettings.DEFAULT.padSamples,
    maxSamples: Int = VadSettings.DEFAULT.maxSamples,
    minPauseSamples: Int = VadSettings.DEFAULT.minPauseSamples,
    pauseDb: Float = VadSettings.DEFAULT.pauseDb,
    pauseFloorDb: Float = VadSettings.DEFAULT.pauseFloorDb,
    pauseMinContrastDb: Float = VadSettings.DEFAULT.pauseMinContrastDb,
    minNoisyPauseSamples: Int = VadSettings.DEFAULT.minNoisyPauseSamples,
    pauseLoudWindowSamples: Int = VadSettings.DEFAULT.pauseLoudWindowSamples,
    noiseFloorPercentile: Int = VadSettings.DEFAULT.noiseFloorPercentile,
    noiseFloorContextSamples: Int = VadSettings.DEFAULT.noiseFloorContextSamples,
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
        .flatMap {
            splitAtPauses(
                audio, it[0], it[1], minPauseSamples, pauseDb, pauseFloorDb, pauseMinContrastDb, minNoisyPauseSamples,
                pauseLoudWindowSamples, noiseFloorPercentile, noiseFloorContextSamples,
            )
        }
        .flatMap { (from, to) -> splitLongRange(audio, from, to, limit) }
        .filter { (from, to) -> to > from }
        .map { (from, to) -> SpeechSpan(from, audio.copyOfRange(from, to)) }
}

/** [padSpeechSpans] with the padding, limit and pause splitting of [settings]. */
fun padSpeechSpans(vadSegments: List<Pair<Int, Int>>, audio: FloatArray, settings: VadSettings): List<SpeechSpan> =
    padSpeechSpans(
        vadSegments,
        audio,
        padSamples = settings.padSamples,
        maxSamples = settings.maxSamples,
        minPauseSamples = settings.minPauseSamples,
        pauseDb = settings.pauseDb,
        pauseFloorDb = settings.pauseFloorDb,
        pauseMinContrastDb = settings.pauseMinContrastDb,
        minNoisyPauseSamples = settings.minNoisyPauseSamples,
        pauseLoudWindowSamples = settings.pauseLoudWindowSamples,
        noiseFloorPercentile = settings.noiseFloorPercentile,
        noiseFloorContextSamples = settings.noiseFloorContextSamples,
    )

/** Length of the frames whose energy [splitAtPauses] compares (10 ms). */
private const val PAUSE_FRAME = AsrSettings.SAMPLE_RATE / 100

/**
 * Splits `[from, to)` into `[start, end)` pieces in the middle of every pause inside it. A pause
 * is a run of near-silent 10 ms frames, [pauseDb] dB or more below the loud frames (95th
 * percentile of the range, or of the frames within [loudWindowSamples] if positive), lasting at
 * least [minPauseSamples]; or a run of frames at most
 * [pauseFloorDb] above the [noiseFloor] around the range (and still [pauseMinContrastDb] below
 * the loud frames) lasting at least [minNoisyPauseSamples]; the floor is the
 * [noiseFloorPercentile]th percentile over the range and [noiseFloorContextSamples] on each side. The first catches turn-taking gaps
 * the VAD rides through after reverberant speech, where Silero stays above its threshold for
 * over a second; the second catches them in noise, which fills the gaps (#71).
 */
internal fun splitAtPauses(
    audio: FloatArray,
    from: Int,
    to: Int,
    minPauseSamples: Int,
    pauseDb: Float,
    pauseFloorDb: Float = Float.NEGATIVE_INFINITY,
    pauseMinContrastDb: Float = VadSettings.DEFAULT.pauseMinContrastDb,
    minNoisyPauseSamples: Int = minPauseSamples,
    loudWindowSamples: Int = 0,
    noiseFloorPercentile: Int = VadSettings.DEFAULT.noiseFloorPercentile,
    noiseFloorContextSamples: Int = VadSettings.DEFAULT.noiseFloorContextSamples,
): List<Pair<Int, Int>> {
    val frames = (to - from) / PAUSE_FRAME
    if (minPauseSamples <= 0 || frames < 3) return listOf(from to to)
    val energy = DoubleArray(frames) { frameEnergy(audio, from + it * PAUSE_FRAME) }
    val floor = if (pauseFloorDb == Float.NEGATIVE_INFINITY) 0.0 else noiseFloor(audio, from, to, noiseFloorPercentile, noiseFloorContextSamples) * 10.0.pow(pauseFloorDb / 10.0)
    // Loudness reference: the 95th percentile of the whole range, or of the frames within
    // [loudWindowSamples] of each 100 ms block.
    val window = loudWindowSamples / PAUSE_FRAME
    val loud = DoubleArray(frames)
    if (window <= 0) {
        loud.fill(energy.sortedArray()[frames * 95 / 100])
    } else {
        for (block in 0 until frames step 10) {
            val near = energy.copyOfRange(maxOf(0, block - window), minOf(frames, block + 10 + window)).apply { sort() }
            loud.fill(near[near.size * 95 / 100], block, minOf(frames, block + 10))
        }
    }
    val silent = DoubleArray(frames) { loud[it] * 10.0.pow(-pauseDb / 10.0) }
    val quiet = DoubleArray(frames) { maxOf(silent[it], minOf(floor, loud[it] * 10.0.pow(-pauseMinContrastDb / 10.0))) }
    val minFrames = (minPauseSamples + PAUSE_FRAME - 1) / PAUSE_FRAME
    val minNoisyFrames = (minNoisyPauseSamples + PAUSE_FRAME - 1) / PAUSE_FRAME
    val cuts = mutableListOf<Int>()
    fun cutAt(runStart: Int, runEnd: Int) {
        cuts += from + (runStart + runEnd) * PAUSE_FRAME / 2
    }
    // Runs of frames that are near-silent or near the floor; each holds at most one pause.
    var runStart = -1
    for (f in 0..frames) {
        val isQuiet = f < frames && energy[f] <= quiet[f]
        if (isQuiet && runStart < 0) runStart = f
        if (!isQuiet && runStart >= 0) {
            // Quiet runs at the edges are padding, not a gap between two utterances.
            if (runStart > 0 && f < frames) {
                if (f - runStart >= minNoisyFrames) {
                    cutAt(runStart, f)
                } else {
                    longestSilentRun(energy, runStart, f, silent)?.let { (a, b) -> if (b - a >= minFrames) cutAt(a, b) }
                }
            }
            runStart = -1
        }
    }
    return (listOf(from) + cuts + to).zipWithNext()
}

/** The longest run `[start, end)` of frames in `[from, to)` with energy at most their [limit]. */
private fun longestSilentRun(energy: DoubleArray, from: Int, to: Int, limit: DoubleArray): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var start = -1
    for (f in from..to) {
        val isSilent = f < to && energy[f] <= limit[f]
        if (isSilent && start < 0) start = f
        if (!isSilent && start >= 0) {
            if (best == null || f - start > best.second - best.first) best = start to f
            start = -1
        }
    }
    return best
}

/** Mean energy of the 10 ms frame starting at [start]. */
private fun frameEnergy(audio: FloatArray, start: Int): Double {
    var sum = 0.0
    for (i in start until start + PAUSE_FRAME) sum += audio[i].toDouble() * audio[i]
    return sum / PAUSE_FRAME
}

/**
 * Energy of the background noise around `[from, to)`: the [percentile]th percentile of the 10 ms
 * frame energies over the range and [contextSamples] on each side, which in a conversation
 * includes the gaps between utterances.
 */
internal fun noiseFloor(
    audio: FloatArray,
    from: Int,
    to: Int,
    percentile: Int = VadSettings.DEFAULT.noiseFloorPercentile,
    contextSamples: Int = VadSettings.DEFAULT.noiseFloorContextSamples,
): Double {
    val start = maxOf(0, from - contextSamples)
    val frames = (minOf(audio.size, to + contextSamples) - start) / PAUSE_FRAME
    if (frames <= 0) return 0.0
    val energy = DoubleArray(frames) { frameEnergy(audio, start + it * PAUSE_FRAME) }
    energy.sort()
    return energy[(frames - 1) * percentile / 100]
}

/** Splits `[from, to)` at its quietest points into pieces no longer than [limit]. */
private fun splitLongRange(audio: FloatArray, from: Int, to: Int, limit: Int): List<Pair<Int, Int>> {
    val pieces = mutableListOf<Pair<Int, Int>>()
    var start = from
    while (to - start > limit) {
        // Keep every piece, including the last, at least a quarter of the limit long.
        val minPiece = maxOf(1, limit / 4)
        val cut = quietestPoint(audio, start + minPiece, minOf(start + limit, to - minPiece))
        pieces += start to cut
        start = cut
    }
    pieces += start to to
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
