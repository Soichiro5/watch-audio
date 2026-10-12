package app.watchaudio.core.export

/** Incremental CRC-32 (IEEE 802.3, as in ZIP and gzip). */
class Crc32 {
    private var crc = 0xFFFFFFFF.toInt()

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    /** The checksum of everything passed to [update] so far, as an unsigned 32-bit value. */
    val value: Long get() = (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
            c
        }
    }
}
