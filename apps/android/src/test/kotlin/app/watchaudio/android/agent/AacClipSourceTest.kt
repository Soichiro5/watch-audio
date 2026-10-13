package app.watchaudio.android.agent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** `get_audio_clip` (#184) cuts 16 kHz samples by time, clamped to the decoded chunk. */
class AacClipSourceTest {
    private val samples = FloatArray(16_000 * 3) { it.toFloat() } // 3 s

    @Test
    fun cutsTheAskedForPart() {
        val clip = AacClipSource.clip(samples, offsetMs = 1_000, durationMs = 500)
        assertEquals(8_000, clip.size)
        assertEquals(16_000f, clip.first())
    }

    @Test
    fun clampsToTheChunk() {
        assertEquals(16_000, AacClipSource.clip(samples, offsetMs = 2_000, durationMs = 5_000).size)
        assertContentEquals(FloatArray(0), AacClipSource.clip(samples, offsetMs = 4_000, durationMs = 1_000))
        assertEquals(1_600, AacClipSource.clip(samples, offsetMs = -100, durationMs = 200).size)
    }
}
