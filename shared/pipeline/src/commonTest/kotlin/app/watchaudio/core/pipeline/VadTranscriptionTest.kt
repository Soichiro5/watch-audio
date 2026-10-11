package app.watchaudio.core.pipeline

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class VadTranscriptionTest {
    private val rate = AsrSettings.SAMPLE_RATE
    private val window = VadSettings.DEFAULT.windowSize

    /** Two tones of [seconds] each, separated by 4 s of silence, in 12 s of audio. */
    private fun twoUtterances(seconds: Int = 2): FloatArray {
        val audio = FloatArray(12 * rate)
        for (start in listOf(1 * rate, (5 + seconds) * rate)) {
            for (i in 0 until seconds * rate) audio[start + i] = 0.3f * sin(2 * PI * 220 * i / rate).toFloat()
        }
        return audio
    }

    /** A stand-in for Silero: speech where the window is loud. */
    private fun loudness(window: FloatArray): Float = if (window.maxOf { it } > 0.1f) 0.9f else 0.02f

    @Test
    fun defaultsAreTheSettingsChosenOnTheTestAudioSet() {
        // #68 / #71 (docs/dev/test-audio.md); changing one changes every transcript.
        val d = VadSettings.DEFAULT
        assertEquals(512, d.windowSize)
        assertEquals(0.15f, d.threshold)
        assertEquals(0.15f, d.negThreshold)
        assertEquals(2f, d.minSilenceSeconds)
        assertEquals(0.25f, d.minSpeechSeconds)
        assertEquals(2, d.startBackWindows)
        assertEquals(4_800, d.padSamples)
        assertEquals(320_000, d.maxSamples)
        assertEquals(3_520, d.minPauseSamples)
        assertEquals(30f, d.pauseDb)
        assertEquals(9f, d.pauseFloorDb)
        assertEquals(4_800, d.minNoisyPauseSamples)
        assertEquals(6f, d.pauseMinContrastDb)
        assertEquals(16_000, d.pauseLoudWindowSamples)
        assertEquals(10, d.noiseFloorPercentile)
        assertEquals(80_000, d.noiseFloorContextSamples)
    }

    @Test
    fun probabilitiesAreOnePerFullWindow() = runTest {
        val samples = FloatArray(3 * window + 100) { if (it in window until 2 * window) 1f else 0f }
        val seen = mutableListOf<Int>()
        val probabilities = speechProbabilities(samples, window) { seen += it.size; it.sum() / it.size }
        assertContentEquals(floatArrayOf(0f, 1f, 0f), probabilities)
        assertEquals(listOf(window, window, window), seen)
    }

    @Test
    fun transcribesTheSpansOfTheSharedSegmentation() = runTest {
        val audio = twoUtterances()
        val probabilities = speechProbabilities(audio, window, ::loudness)
        val expected = padSpeechSpans(speechSegments(probabilities), audio)
        val inputs = mutableListOf<FloatArray>()

        val texts = transcribeSpeech(audio, probabilities, VadSettings.DEFAULT, "ja") {
            inputs += it
            Recognition("  発話${inputs.size} ")
        }

        assertEquals(2, expected.size)
        assertEquals(expected.map { it.samples.toList() }, inputs.map { it.toList() })
        assertEquals(expected.map { it.startMs to it.endMs }, texts.map { it.startMs to it.endMs })
        assertEquals(listOf("発話1", "発話2"), texts.map { it.text })
        assertEquals(listOf("ja", "ja"), texts.map { it.lang })
        assertEquals(listOf(null, null), texts.map { it.confidence })
        // Mostly speech (0.9), plus the padding on both sides (0.02).
        texts.forEach { assertTrue(it.speechProbability!! in 0.5f..0.9f, "${it.speechProbability}") }
    }

    @Test
    fun blankResultsAreDroppedAndRecognizerSignalsKept() = runTest {
        val audio = twoUtterances()
        val probabilities = speechProbabilities(audio, window, ::loudness)
        var call = 0
        val texts = transcribeSpeech(audio, probabilities, VadSettings.DEFAULT, "ja") {
            if (call++ == 0) Recognition(" ") else Recognition("hello", confidence = 0.8f, lang = "en")
        }
        assertEquals(listOf(TimedText(texts.single().startMs, texts.single().endMs, "hello", "en", 0.8f, texts.single().speechProbability)), texts)
    }

    @Test
    fun settingsChangeTheSegmentation() = runTest {
        val audio = twoUtterances()
        val probabilities = speechProbabilities(audio, window, ::loudness)
        // Speech shorter than the minimum is dropped.
        val strict = VadSettings(minSpeechSeconds = 3f)
        assertEquals(emptyList(), transcribeSpeech(audio, probabilities, strict, "ja") { Recognition("x") })
        // Silence of 4 s does not end speech when 5 s are required; the pause still splits it.
        val joined = speechSegments(probabilities, VadSettings(minSilenceSeconds = 5f))
        assertEquals(1, joined.size)
        assertNotEquals(speechSegments(probabilities), joined)
        assertEquals(speechSegments(probabilities), speechSegments(probabilities, VadSettings.DEFAULT))
    }

    @Test
    fun meanProbabilityCoversTheWindowsTheSpanOverlaps() {
        val probabilities = floatArrayOf(0f, 1f, 0.5f, 0f)
        assertEquals(0.75f, meanSpeechProbability(probabilities, 10, SpeechSpan(10, FloatArray(20))))
        // Partly overlapped windows count.
        assertEquals(0.5f, meanSpeechProbability(probabilities, 10, SpeechSpan(5, FloatArray(10))))
        // Only the unscored tail.
        assertNull(meanSpeechProbability(probabilities, 10, SpeechSpan(40, FloatArray(5))))
    }

    @Test
    fun invalidSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { VadSettings(windowSize = 0) }
        assertFailsWith<IllegalArgumentException> { VadSettings(noiseFloorPercentile = 101) }
    }
}
