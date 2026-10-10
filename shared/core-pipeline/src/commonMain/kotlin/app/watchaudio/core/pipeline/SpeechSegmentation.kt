package app.watchaudio.core.pipeline

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

    const val JA_MODEL_NAME = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
    const val JA_ENGINE_ID = "sherpa-zipformer-ja-reazonspeech-int8"
}

/** A detected utterance: offset into the chunk (in samples) and its samples. */
class SpeechSpan(val startSample: Int, val samples: FloatArray) {
    val startMs: Long get() = startSample * 1000L / AsrSettings.SAMPLE_RATE
    val endMs: Long get() = (startSample + samples.size) * 1000L / AsrSettings.SAMPLE_RATE
}

/**
 * Widens VAD segments by [padSamples] on both sides, re-cutting them from [audio] so the padding
 * holds real audio, and merges segments that overlap after padding.
 */
fun padSpeechSpans(
    vadSegments: List<Pair<Int, Int>>,
    audio: FloatArray,
    padSamples: Int = (AsrSettings.SEGMENT_PAD_SECONDS * AsrSettings.SAMPLE_RATE).toInt(),
): List<SpeechSpan> {
    val ranges = mutableListOf<IntArray>()
    for ((start, length) in vadSegments.sortedBy { it.first }) {
        val from = maxOf(0, start - padSamples)
        val to = minOf(audio.size, start + length + padSamples)
        val last = ranges.lastOrNull()
        if (last != null && from <= last[1]) last[1] = maxOf(last[1], to) else ranges += intArrayOf(from, to)
    }
    return ranges.filter { it[1] > it[0] }.map { SpeechSpan(it[0], audio.copyOfRange(it[0], it[1])) }
}
