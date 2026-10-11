package app.watchaudio.wear.debug

import app.watchaudio.core.protocol.RecordingFormat
import java.io.Closeable
import kotlin.math.roundToInt

/** 16-bit PCM at [RecordingFormat.CURRENT.sampleRateHz], mono: what the microphone would capture. */
interface MonoPcmSource : Closeable {
    /** Fills [buffer] with up to `buffer.size` samples; returns the count, or -1 at the end. */
    fun read(buffer: ShortArray): Int

    /** Discards [samples] samples (resuming playback); stops early at the end. */
    fun skip(samples: Long) {
        val scratch = ShortArray(4_096)
        var left = samples
        while (left > 0) {
            val n = read(if (left >= scratch.size) scratch else ShortArray(left.toInt()))
            if (n < 0) return
            left -= n
        }
    }
}

/**
 * Converts interleaved 16-bit PCM of any rate and channel count to [RecordingFormat.CURRENT.sampleRateHz]
 * mono: channels are averaged and the rate is changed by streaming linear interpolation (no
 * low-pass filter; good enough to drive the recorder pipeline). Feed consecutive blocks; the
 * output equals converting the concatenation in one go.
 */
class PcmConverter(val sampleRate: Int, val channels: Int, private val targetRate: Int = RecordingFormat.CURRENT.sampleRateHz) {
    init {
        require(sampleRate > 0 && channels > 0) { "invalid PCM format $sampleRate Hz x $channels" }
    }

    private val passthrough = sampleRate == targetRate

    /** Input frames per output sample. */
    private val step = sampleRate.toDouble() / targetRate

    /** Position of the next output sample, in `[carry] + block` frame coordinates. */
    private var position = 0.0
    private var carry: Float? = null

    /** Converts the first [count] samples of [interleaved] (whole frames). */
    fun convert(interleaved: ShortArray, count: Int = interleaved.size): ShortArray {
        val frames = count / channels
        val mono = FloatArray(frames) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum.toFloat() / channels
        }
        if (passthrough) return ShortArray(frames) { toPcm16(mono[it]) }
        if (frames == 0) return ShortArray(0)

        val offset = if (carry != null) 1 else 0
        val last = frames + offset - 1
        fun at(i: Int): Float = if (i < offset) carry!! else mono[i - offset]

        val out = ShortArray(((last - position) / step).toInt().coerceAtLeast(0) + 2)
        var n = 0
        while (position <= last) {
            val i = position.toInt()
            val frac = (position - i).toFloat()
            val a = at(i)
            out[n++] = toPcm16(if (i < last) a + (at(i + 1) - a) * frac else a)
            position += step
        }
        position -= last
        carry = mono[frames - 1]
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun toPcm16(value: Float): Short = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/** Base for sources that decode blocks in their own format and convert them with [PcmConverter]. */
abstract class ConvertingPcmSource : MonoPcmSource {
    /** A decoded block of interleaved PCM. */
    protected class Block(val samples: ShortArray, val count: Int, val sampleRate: Int, val channels: Int)

    private var converter: PcmConverter? = null
    private var pending = ShortArray(0)
    private var pendingPosition = 0

    /** The next decoded block, or null at the end of the input. */
    protected abstract fun readBlock(): Block?

    override fun read(buffer: ShortArray): Int {
        while (pendingPosition >= pending.size) {
            val block = readBlock() ?: return -1
            val current = converter
            val c = if (current != null && current.sampleRate == block.sampleRate && current.channels == block.channels) {
                current
            } else {
                PcmConverter(block.sampleRate, block.channels).also { converter = it }
            }
            pending = c.convert(block.samples, block.count)
            pendingPosition = 0
        }
        val n = minOf(buffer.size, pending.size - pendingPosition)
        pending.copyInto(buffer, 0, pendingPosition, pendingPosition + n)
        pendingPosition += n
        return n
    }
}
