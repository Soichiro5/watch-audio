package app.watchaudio.wear.debug

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads a 16-bit PCM WAV file (any sample rate and channel count; converted to 16 kHz mono).
 * Pure JVM, so unit tests and the device use the same reader.
 */
class WavPcmSource(input: InputStream) : ConvertingPcmSource() {
    private val data = DataInputStream(input.buffered())
    private val header = readHeader(data)
    val sampleRate: Int get() = header.sampleRate
    val channels: Int get() = header.channels

    /** Bytes left in the `data` chunk. */
    private var remaining = header.dataBytes

    override fun readBlock(): Block? {
        val frameBytes = 2 * channels
        val want = minOf(BLOCK_FRAMES.toLong() * frameBytes, remaining).toInt()
        if (want < frameBytes) return null
        val bytes = ByteArray(want)
        var filled = 0
        while (filled < want) {
            val n = data.read(bytes, filled, want - filled)
            if (n < 0) break
            filled += n
        }
        remaining -= filled
        val usable = filled - filled % frameBytes
        if (usable == 0) {
            remaining = 0
            return null
        }
        if (filled < want) remaining = 0 // truncated file
        val samples = ShortArray(usable / 2) { i ->
            ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return Block(samples, samples.size, sampleRate, channels)
    }

    override fun close() = data.close()

    private class Header(val sampleRate: Int, val channels: Int, val dataBytes: Long)

    private companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_EXTENSIBLE = 0xfffe
        const val BLOCK_FRAMES = 8_192

        fun readHeader(data: DataInputStream): Header {
            if (data.readTag() != "RIFF") throw IOException("not a RIFF file")
            data.readIntLe() // RIFF size
            if (data.readTag() != "WAVE") throw IOException("not a WAVE file")
            var format: Pair<Int, Int>? = null
            while (true) {
                val tag = data.readTag()
                val size = data.readIntLe().toLong() and 0xffff_ffffL
                when (tag) {
                    "fmt " -> {
                        val audioFormat = data.readShortLe()
                        val channels = data.readShortLe()
                        val rate = data.readIntLe()
                        data.readIntLe() // byte rate
                        data.readShortLe() // block align
                        val bits = data.readShortLe()
                        data.skipFully(size - 16 + (size and 1))
                        if (audioFormat != FORMAT_PCM && audioFormat != FORMAT_EXTENSIBLE) throw IOException("WAV format $audioFormat is not PCM")
                        if (bits != 16) throw IOException("only 16-bit WAV is supported (got $bits-bit)")
                        format = rate to channels
                    }
                    "data" -> {
                        val (rate, channels) = format ?: throw IOException("WAV data chunk before fmt")
                        // 0 / 0xFFFFFFFF: written while streaming, size unknown; read to the end.
                        val bytes = if (size == 0L || size == 0xffff_ffffL) Long.MAX_VALUE else size
                        return Header(rate, channels, bytes)
                    }
                    else -> data.skipFully(size + (size and 1))
                }
            }
        }

        fun DataInputStream.readTag(): String = ByteArray(4).also { readFully(it) }.toString(Charsets.US_ASCII)

        fun DataInputStream.readIntLe(): Int = Integer.reverseBytes(readInt())

        fun DataInputStream.readShortLe(): Int = java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff

        fun DataInputStream.skipFully(count: Long) {
            var left = count
            while (left > 0) {
                val skipped = skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (read() < 0) throw EOFException("truncated WAV header")
                    left--
                }
            }
        }
    }
}
