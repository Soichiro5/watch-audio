package app.watchaudio.core.pipeline

/**
 * Turns Silero VAD speech probabilities, one per window of [windowSize] samples, into speech
 * segments (start, length in samples) — the same hysteresis as Silero / sherpa-onnx, with its
 * end threshold exposed (#71):
 *
 * - speech starts at the first window whose probability reaches [threshold] (backdated by
 *   [startBackWindows] windows, as the probability rises late on word onsets);
 * - it ends where the probability stays below [negThreshold] for [minSilenceSeconds] (a window
 *   at or above [threshold] resets that silence; one in between neither starts nor resets it);
 * - segments shorter than [minSpeechSeconds] are dropped. Long ones are not cut here:
 *   [padSpeechSpans] splits them at pauses or their quietest points, whereas a cut at a fixed
 *   length lands in the middle of a word (#71).
 *
 * sherpa-onnx fixes the end threshold at `threshold - 0.15`, so a threshold low enough to hear
 * quiet voices in noise (0.15) would only end speech below 0.01 and merge whole conversations.
 */
fun speechSegments(
    probabilities: FloatArray,
    windowSize: Int = VadSettings.DEFAULT.windowSize,
    threshold: Float = VadSettings.DEFAULT.threshold,
    negThreshold: Float = VadSettings.DEFAULT.negThreshold,
    minSilenceSeconds: Float = VadSettings.DEFAULT.minSilenceSeconds,
    minSpeechSeconds: Float = VadSettings.DEFAULT.minSpeechSeconds,
    startBackWindows: Int = VadSettings.DEFAULT.startBackWindows,
): List<Pair<Int, Int>> {
    val rate = AsrSettings.SAMPLE_RATE
    val minSilenceWindows = maxOf(1, (minSilenceSeconds * rate / windowSize).toInt())
    val minSpeechSamples = (minSpeechSeconds * rate).toInt()
    val segments = mutableListOf<Pair<Int, Int>>()
    var start = -1 // first speech window of the open segment, or -1
    var silence = -1 // first window of the silence that may end it, or -1

    fun close(endWindow: Int) {
        val from = maxOf(0, start - startBackWindows) * windowSize
        val length = endWindow * windowSize - from
        if (length >= minSpeechSamples) segments += from to length
    }

    for (window in probabilities.indices) {
        val probability = probabilities[window]
        if (start < 0) {
            if (probability >= threshold) {
                start = window
                silence = -1
            }
            continue
        }
        if (probability >= threshold) {
            silence = -1
        } else if (probability < negThreshold && silence < 0) {
            silence = window
        }
        if (silence >= 0 && window - silence + 1 >= minSilenceWindows) {
            close(silence)
            start = -1
            silence = -1
        }
    }
    if (start >= 0) close(if (silence >= 0) silence else probabilities.size)
    return segments
}

/** [speechSegments] with the window size and thresholds of [settings]. */
fun speechSegments(probabilities: FloatArray, settings: VadSettings): List<Pair<Int, Int>> =
    speechSegments(
        probabilities,
        windowSize = settings.windowSize,
        threshold = settings.threshold,
        negThreshold = settings.negThreshold,
        minSilenceSeconds = settings.minSilenceSeconds,
        minSpeechSeconds = settings.minSpeechSeconds,
        startBackWindows = settings.startBackWindows,
    )
