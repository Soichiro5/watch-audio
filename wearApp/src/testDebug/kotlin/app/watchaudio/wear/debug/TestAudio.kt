package app.watchaudio.wear.debug

import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Synthetic test audio: tones and near-silence, written as 16-bit PCM WAV. */
object TestAudio {
    /** A 440 Hz tone at about -12 dBFS (peak 8000): well above the silence threshold. */
    fun tone(sampleIndex: Long, sampleRate: Int, amplitude: Int = 8_000): Short =
        (amplitude * sin(2 * PI * 440 * sampleIndex / sampleRate)).roundToInt().toShort()

    /** A quiet room: deterministic noise with a peak of 100 (below the threshold of 300). */
    fun quiet(sampleIndex: Long): Short = (((sampleIndex * 7_919) % 201) - 100).toShort()

    /** Writes [frames] frames of [channels]-channel audio produced by [sample] (frame, channel). */
    fun writeWav(file: File, sampleRate: Int, channels: Int, frames: Long, sample: (Long, Int) -> Short) {
        val dataBytes = frames * channels * 2
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLe((36 + dataBytes).toInt())
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(channels)
            out.writeIntLe(sampleRate)
            out.writeIntLe(sampleRate * channels * 2)
            out.writeShortLe(channels * 2)
            out.writeShortLe(16)
            // An unrelated chunk before the data, as many tools write.
            out.writeBytes("LIST")
            out.writeIntLe(4)
            out.writeBytes("INFO")
            out.writeBytes("data")
            out.writeIntLe(dataBytes.toInt())
            for (frame in 0 until frames) {
                for (channel in 0 until channels) out.writeShortLe(sample(frame, channel).toInt())
            }
        }
    }

    private fun DataOutputStream.writeIntLe(value: Int) = writeInt(Integer.reverseBytes(value))

    private fun DataOutputStream.writeShortLe(value: Int) = writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
}

/** Stands in for the AAC encoder on the JVM: writes the PCM as-is (little-endian). */
class RawPcmSink : ChunkSink {
    private var out: OutputStream? = null
    private var bytes = 0L
    val files = mutableListOf<File>()

    override fun open(file: File) {
        check(out == null) { "previous file not closed" }
        out = file.outputStream().buffered(1 shl 16)
        bytes = 0
        files += file
    }

    override fun write(samples: ShortArray, offset: Int, count: Int) {
        val o = checkNotNull(out) { "not open" }
        for (i in offset until offset + count) {
            val s = samples[i].toInt()
            o.write(s and 0xff)
            o.write((s shr 8) and 0xff)
        }
        bytes += count * 2
    }

    override fun close(): Boolean {
        val o = out ?: return false
        out = null
        o.close()
        return bytes > 0
    }
}
