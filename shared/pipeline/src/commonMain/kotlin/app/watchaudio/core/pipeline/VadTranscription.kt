package app.watchaudio.core.pipeline

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What a recognizer made of one speech span: its [text] (blank when it heard nothing), with an
 * optional [confidence] (0..1) and, for models that detect it, the [lang] of the text.
 */
data class Recognition(val text: String, val confidence: Float? = null, val lang: String? = null)

/**
 * Speech probability of each window of [windowSize] samples of [samples], as computed by
 * [compute] (sherpa-onnx's Silero `Vad.compute`). A shorter tail is not scored. Checks for
 * cancellation every 1024 windows (~33 s of audio).
 */
suspend fun speechProbabilities(samples: FloatArray, windowSize: Int, compute: (FloatArray) -> Float): FloatArray {
    val probabilities = FloatArray(samples.size / windowSize)
    for (index in probabilities.indices) {
        if (index % 1024 == 0) currentCoroutineContext().ensureActive()
        probabilities[index] = compute(samples.copyOfRange(index * windowSize, (index + 1) * windowSize))
    }
    return probabilities
}

/**
 * The sherpa-onnx transcription shared by Android and the JVM harness, from the VAD onwards:
 * finds speech in [probabilities] (one per window of [VadSettings.windowSize] samples of
 * [samples], see [speechProbabilities]) with [speechSegments], cuts recognizer inputs with
 * [padSpeechSpans], and recognizes each one with [recognize]. Only creating the recognizer and
 * the VAD differs per platform.
 *
 * Each non-blank result becomes a [TimedText] (times relative to the start of [samples]) in
 * [Recognition.lang] or else [language], with its [TimedText.speechProbability]
 * ([meanSpeechProbability]) and [TimedText.levelAboveNoiseDb] ([levelAboveNoiseFloorDb]) for a
 * [SegmentFilter]. Checks for cancellation before each input.
 */
suspend fun transcribeSpeech(
    samples: FloatArray,
    probabilities: FloatArray,
    settings: VadSettings,
    language: String?,
    recognize: (FloatArray) -> Recognition,
): List<TimedText> =
    padSpeechSpans(speechSegments(probabilities, settings), samples, settings).mapNotNull { span ->
        currentCoroutineContext().ensureActive()
        val result = recognize(span.samples)
        val text = result.text.trim()
        if (text.isEmpty()) {
            null
        } else {
            TimedText(
                startMs = span.startMs,
                endMs = span.endMs,
                text = text,
                lang = result.lang ?: language,
                confidence = result.confidence,
                speechProbability = meanSpeechProbability(probabilities, settings.windowSize, span),
                levelAboveNoiseDb = levelAboveNoiseFloorDb(
                    samples,
                    span.startSample,
                    span.startSample + span.samples.size,
                    settings.noiseFloorPercentile,
                    settings.noiseFloorContextSamples,
                ),
            )
        }
    }

/**
 * Mean of the [probabilities] (one per window of [windowSize] samples) of the windows that
 * overlap [span], or null if none does (a span in the unscored tail).
 */
fun meanSpeechProbability(probabilities: FloatArray, windowSize: Int, span: SpeechSpan): Float? {
    val first = span.startSample / windowSize
    val end = minOf(probabilities.size, (span.startSample + span.samples.size + windowSize - 1) / windowSize)
    if (end <= first) return null
    var sum = 0.0
    for (window in first until end) sum += probabilities[window]
    return (sum / (end - first)).toFloat()
}
