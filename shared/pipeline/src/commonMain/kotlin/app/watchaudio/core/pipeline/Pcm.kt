package app.watchaudio.core.pipeline

/**
 * Averages interleaved [channels]-channel float PCM into mono. Reads the first
 * `frames * channels` values of [interleaved].
 */
fun downmixToMono(interleaved: FloatArray, frames: Int, channels: Int): FloatArray {
    require(channels >= 1) { "channels must be >= 1" }
    require(frames * channels <= interleaved.size) { "need ${frames * channels} samples, got ${interleaved.size}" }
    if (channels == 1) return interleaved.copyOf(frames)
    return FloatArray(frames) { frame ->
        var sum = 0f
        val base = frame * channels
        for (c in 0 until channels) sum += interleaved[base + c]
        sum / channels
    }
}

/** Converts signed 16-bit PCM samples to floats in [-1, 1). */
fun pcm16ToFloat(sample: Short): Float = sample / 32768f

/**
 * Streaming linear-interpolation resampler. Feed consecutive blocks to [process]; the output is
 * the same as resampling the concatenated input in one go.
 *
 * There is no low-pass filter, so downsampling aliases. Watches record at 16 kHz already; this
 * only covers the occasional other-rate file, where linear interpolation is good enough for ASR.
 */
class LinearResampler(fromRate: Int, toRate: Int) {
    init {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
    }

    private val passthrough = fromRate == toRate

    /** Input samples per output sample. */
    private val step = fromRate.toDouble() / toRate

    /** Position of the next output sample in `[carry] + block` coordinates. */
    private var position = 0.0
    private var carry: Float? = null

    fun process(block: FloatArray, length: Int = block.size): FloatArray {
        require(length in 0..block.size) { "invalid length $length" }
        if (passthrough) return block.copyOf(length)
        if (length == 0) return FloatArray(0)
        val offset = if (carry != null) 1 else 0
        val size = length + offset
        fun at(i: Int): Float = if (i < offset) carry!! else block[i - offset]

        val last = size - 1
        // Exact count is floor((last - position) / step) + 1; one extra slot absorbs rounding.
        val out = FloatArray(((last - position) / step).toInt().coerceAtLeast(0) + 2)
        var n = 0
        while (position <= last) {
            val i = position.toInt()
            val frac = (position - i).toFloat()
            val a = at(i)
            out[n++] = if (i < last) a + (at(i + 1) - a) * frac else a
            position += step
        }
        position -= last
        carry = block[length - 1]
        return if (n == out.size) out else out.copyOf(n)
    }
}

/**
 * Builds the input for [Transcriber.transcribe]: decodes each part with [decode] (16 kHz mono
 * by default), cuts out its range and concatenates the ranges. A range running past the end of
 * its file is padded with silence, so every part contributes exactly its declared length.
 */
fun concatenateParts(
    parts: List<AudioPart>,
    sampleRate: Int = AsrSettings.SAMPLE_RATE,
    decode: (AudioPart) -> FloatArray,
): FloatArray {
    fun samples(ms: Long): Int = (ms * sampleRate / 1000).toInt()
    val output = FloatArray(parts.sumOf { samples(it.durationMs) })
    var position = 0
    for (part in parts) {
        val length = samples(part.durationMs)
        val decoded = decode(part)
        val from = samples(part.fromMs).coerceAtMost(decoded.size)
        val to = minOf(from + length, decoded.size)
        decoded.copyInto(output, destinationOffset = position, startIndex = from, endIndex = to)
        position += length
    }
    return output
}
