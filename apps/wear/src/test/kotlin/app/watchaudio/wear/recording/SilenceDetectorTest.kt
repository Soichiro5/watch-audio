package app.watchaudio.wear.recording

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private val fiveMinutes = 300_000L

    private fun detector(vararg amplitudes: Int) = SilenceDetector().apply { amplitudes.forEach(::add) }

    @Test
    fun `quiet chunk with full coverage is silent`() {
        val d = detector(*IntArray(300) { 120 })
        assertTrue(d.isSilent(fiveMinutes))
    }

    @Test
    fun `a single loud poll keeps the audio`() {
        val amplitudes = IntArray(300) { 120 }.also { it[150] = SilenceDetector.SILENCE_PEAK_THRESHOLD }
        assertFalse(detector(*amplitudes).isSilent(fiveMinutes))
    }

    @Test
    fun `too few polls keep the audio`() {
        // The poller stalled (e.g. process starved): 100 of 300 expected polls.
        assertFalse(detector(*IntArray(100)).isSilent(fiveMinutes))
        assertTrue(detector(*IntArray(240)).isSilent(fiveMinutes))
    }

    @Test
    fun `very short chunks are never silent`() {
        assertFalse(detector(*IntArray(3)).isSilent(3_000))
        assertTrue(detector(*IntArray(SilenceDetector.MIN_SAMPLES)).isSilent(10_000))
    }

    @Test
    fun `state survives a round trip through the journal`() {
        val d = detector(10, 20, 5000)
        val restored = ChunkJournal("c", "s", "d", 0, "UTC").withSilence(d).silenceDetector()
        assertTrue(restored.samples == 3 && restored.loudSamples == 1 && restored.peak == 5000)
    }
}
