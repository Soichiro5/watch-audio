package app.watchaudio.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechDetectionTest {
    private val window = VadSettings.DEFAULT.windowSize

    /** Probabilities built from runs of (value, windows). */
    private fun probs(vararg runs: Pair<Float, Int>): FloatArray =
        runs.flatMap { (value, count) -> List(count) { value } }.toFloatArray()

    private fun detect(probabilities: FloatArray, threshold: Float = 0.3f, negThreshold: Float = 0.15f) =
        speechSegments(probabilities, threshold = threshold, negThreshold = negThreshold, minSilenceSeconds = 0.5f, minSpeechSeconds = 0.25f)

    @Test
    fun speechStartsAtTheThresholdBackdatedAndEndsWhereSilenceBegins() {
        val segments = detect(probs(0f to 10, 0.9f to 40, 0f to 30))
        assertEquals(listOf(8 * window to 42 * window), segments)
    }

    @Test
    fun shortSilenceDoesNotEndSpeech() {
        // 10 windows (0.32 s) below the end threshold: less than the minimum silence of 0.5 s.
        val segments = detect(probs(0.9f to 20, 0f to 10, 0.9f to 20, 0f to 30))
        assertEquals(1, segments.size)
        assertEquals(0 to 50 * window, segments.single())
    }

    @Test
    fun probabilitiesBetweenTheThresholdsNeitherStartNorEndSpeech() {
        assertTrue(detect(probs(0.2f to 100)).isEmpty())
        // 0.2 is below the start threshold but above the end threshold: the segment goes on.
        assertEquals(listOf(0 to 120 * window), detect(probs(0.9f to 20, 0.2f to 80, 0.9f to 20)))
    }

    @Test
    fun aLowThresholdStillEndsSpeechAtItsOwnEndThreshold() {
        // A quiet voice (0.2) in noise is found with threshold 0.15, and the gaps (0.1) still
        // end it. sherpa-onnx would end speech only below 0.01 here.
        val p = probs(0.05f to 10, 0.2f to 40, 0.1f to 20, 0.2f to 40, 0.05f to 20)
        assertEquals(listOf(8 * window to 42 * window, 68 * window to 42 * window), detect(p, threshold = 0.15f, negThreshold = 0.15f))
        assertEquals(1, detect(p, threshold = 0.15f, negThreshold = 0.01f).size)
    }

    @Test
    fun shortBlipsAreDropped() {
        assertTrue(detect(probs(0f to 20, 0.9f to 3, 0f to 40)).isEmpty())
    }

    @Test
    fun longSpeechIsNotCutAtAFixedLength() {
        // 60 s of speech stays one segment; padSpeechSpans splits it at quiet points.
        val windows = (60f * AsrSettings.SAMPLE_RATE / window).toInt()
        assertEquals(listOf(0 to windows * window), detect(probs(0.9f to windows, 0f to 30)))
    }

    @Test
    fun speechAtTheEndIsClosed() {
        assertEquals(listOf(8 * window to 32 * window), detect(probs(0f to 10, 0.9f to 30)))
    }
}
