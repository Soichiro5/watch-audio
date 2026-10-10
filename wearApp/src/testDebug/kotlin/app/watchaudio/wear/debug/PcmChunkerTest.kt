package app.watchaudio.wear.debug

import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmChunkerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `rotates exactly at the chunk length and reports the boundary`() {
        val sink = RawPcmSink()
        val rotations = mutableListOf<Long>()
        var next = 0
        val chunker = PcmChunker(
            sink,
            object : PcmChunker.Events {
                override fun nextFile() = File(tmp.root, "next-${next++}")
                override fun rotated(positionMs: Long) {
                    rotations += positionMs
                }
            },
            chunkDurationMs = 1_000,
        )
        chunker.start(File(tmp.root, "first"))
        // 2.5 s in blocks that do not line up with the boundary.
        val block = ShortArray(3_000) { 1 }
        repeat(13) { assertTrue(chunker.write(block)) }
        assertEquals(2_437, chunker.positionMs)
        assertTrue(chunker.finish())

        assertEquals(listOf(1_000L, 2_000L), rotations)
        assertEquals(listOf(32_000L, 32_000L, 14_000L), sink.files.map { it.length() }, "16 000 samples per full chunk")
    }

    @Test
    fun `stops when no next file is available`() {
        val chunker = PcmChunker(
            RawPcmSink(),
            object : PcmChunker.Events {
                override fun nextFile(): File? = null
                override fun rotated(positionMs: Long) = error("must not rotate")
            },
            chunkDurationMs = 100,
        )
        chunker.start(File(tmp.root, "only"))
        assertFalse(chunker.write(ShortArray(2_000)))
        assertEquals(100, chunker.positionMs, "filled the chunk, then stopped")
    }

    @Test
    fun `peak is held until polled`() {
        val chunker = PcmChunker(RawPcmSink(), NoRotation)
        chunker.start(File(tmp.root, "peak"))
        chunker.write(shortArrayOf(10, -2_000, 300))
        chunker.write(shortArrayOf(Short.MIN_VALUE))
        assertEquals(32_767, chunker.pollPeak())
        assertEquals(0, chunker.pollPeak())
        chunker.write(shortArrayOf(-5, 7))
        assertEquals(7, chunker.pollPeak())
    }

    @Test
    fun `converter output does not depend on block boundaries`() {
        val input = ShortArray(44_100 * 2) { ((it / 2) * 37 % 20_000 - 10_000).toShort() }
        val whole = PcmConverter(44_100, 2).convert(input)
        val streamed = PcmConverter(44_100, 2).let { c ->
            input.toList().chunked(2 * 333).flatMap { c.convert(it.toShortArray()).toList() }.toShortArray()
        }
        assertEquals(16_000, whole.size, "1 s at 44.1 kHz stereo -> 16 000 mono samples")
        assertEquals(whole.size, streamed.size)
        // Equal up to floating-point rounding of the interpolation position.
        assertTrue(whole.indices.all { abs(whole[it] - streamed[it]) <= 1 })
    }

    @Test
    fun `converter averages channels and passes 16 kHz mono through`() {
        assertContentEquals(shortArrayOf(150, -5), PcmConverter(16_000, 2).convert(shortArrayOf(100, 200, -10, 0)))
        val mono = shortArrayOf(1, 2, 3, Short.MAX_VALUE, Short.MIN_VALUE)
        assertContentEquals(mono, PcmConverter(16_000, 1).convert(mono))
    }

    @Test
    fun `rejects WAV that is not 16-bit PCM`() {
        val wav = tmp.newFile("float.wav")
        TestAudio.writeWav(wav, 16_000, 1, 10) { _, _ -> 0 }
        val bytes = wav.readBytes()
        bytes[34] = 32 // bits per sample
        wav.writeBytes(bytes)
        assertFailsWith<IOException> { WavPcmSource(wav.inputStream()) }
        assertFailsWith<IOException> { WavPcmSource("not a wav file at all".byteInputStream()) }
    }

    @Test
    fun `reads 16 kHz mono WAV sample for sample and skips to a resume point`() {
        val wav = tmp.newFile("mono.wav")
        TestAudio.writeWav(wav, 16_000, 1, 20_000) { frame, _ -> (frame % 1_000).toShort() }
        val all = mutableListOf<Short>()
        WavPcmSource(wav.inputStream()).use { source ->
            val buffer = ShortArray(777)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                all += buffer.copyOf(n).toList()
            }
        }
        assertEquals(List(20_000) { (it % 1_000).toShort() }, all)

        WavPcmSource(wav.inputStream()).use { source ->
            source.skip(12_345)
            val buffer = ShortArray(1)
            source.read(buffer)
            assertEquals(345, buffer[0].toInt())
        }
    }

    private object NoRotation : PcmChunker.Events {
        override fun nextFile(): File? = null
        override fun rotated(positionMs: Long) = Unit
    }
}
