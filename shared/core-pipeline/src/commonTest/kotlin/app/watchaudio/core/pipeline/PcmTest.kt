package app.watchaudio.core.pipeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmTest {
    @Test
    fun downmixAveragesChannels() {
        val stereo = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f, 9f, 9f)
        assertContentEquals(floatArrayOf(0.5f, 0.5f, 0f), downmixToMono(stereo, frames = 3, channels = 2))
        assertContentEquals(floatArrayOf(1f, 0f), downmixToMono(stereo, frames = 2, channels = 1))
    }

    @Test
    fun pcm16Scale() {
        assertEquals(-1f, pcm16ToFloat(Short.MIN_VALUE))
        assertEquals(0f, pcm16ToFloat(0))
        assertTrue(pcm16ToFloat(Short.MAX_VALUE) < 1f)
    }

    @Test
    fun sameRateIsPassthrough() {
        val input = floatArrayOf(1f, 2f, 3f)
        assertContentEquals(floatArrayOf(1f, 2f), LinearResampler(16_000, 16_000).process(input, 2))
    }

    @Test
    fun upsamplingInterpolatesMidpoints() {
        val out = LinearResampler(8_000, 16_000).process(floatArrayOf(0f, 1f, 2f, 3f))
        assertContentEquals(floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f), out)
    }

    @Test
    fun downsamplingKeepsEveryNthSample() {
        val out = LinearResampler(48_000, 16_000).process(FloatArray(12) { it.toFloat() })
        assertContentEquals(floatArrayOf(0f, 3f, 6f, 9f), out)
    }

    @Test
    fun blocksGiveTheSameResultAsOneBuffer() {
        val input = FloatArray(10_000) { kotlin.math.sin(it / 7.0).toFloat() }
        for ((from, to) in listOf(44_100 to 16_000, 8_000 to 16_000, 22_050 to 16_000)) {
            val whole = LinearResampler(from, to).process(input)
            val streaming = LinearResampler(from, to)
            val pieces = mutableListOf<Float>()
            var offset = 0
            for (size in generateSequence(1) { it % 997 + 13 }) {
                if (offset >= input.size) break
                val end = minOf(input.size, offset + size)
                pieces += streaming.process(input.copyOfRange(offset, end)).toList()
                offset = end
            }
            assertEquals(whole.size, pieces.size, "$from -> $to")
            assertTrue(whole.indices.all { abs(whole[it] - pieces[it]) < 1e-4f }, "$from -> $to")
            // Output length follows the rate ratio.
            val expected = (input.size - 1).toLong() * to / from + 1
            assertTrue(abs(whole.size - expected) <= 1, "$from -> $to: ${whole.size} vs $expected")
        }
    }
}
