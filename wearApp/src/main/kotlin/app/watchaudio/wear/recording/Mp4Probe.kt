package app.watchaudio.wear.recording

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal ISO-BMFF (MP4) inspection. `MediaRecorder` writes the `moov` box (the index needed to
 * decode the file) only when it stops cleanly, so a file left behind by a killed process has
 * audio data but cannot be decoded by standard players.
 */
object Mp4Probe {
    /** True when the file has a top-level `moov` box, i.e. it was finalized. */
    fun isFinalized(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                var offset = 0L
                val header = ByteArray(8)
                while (offset + 8 <= length) {
                    raf.seek(offset)
                    raf.readFully(header)
                    var size = readUInt32(header, 0)
                    val type = String(header, 4, 4, Charsets.ISO_8859_1)
                    var headerSize = 8L
                    when (size) {
                        0L -> size = length - offset // box extends to end of file
                        1L -> {
                            if (offset + 16 > length) return false
                            val large = ByteArray(8)
                            raf.readFully(large)
                            size = (readUInt32(large, 0) shl 32) or readUInt32(large, 4)
                            headerSize = 16
                        }
                    }
                    if (size < headerSize) return false
                    if (type == "moov") return offset + size <= length
                    offset += size
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun readUInt32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xff) shl 24) or
            ((bytes[at + 1].toLong() and 0xff) shl 16) or
            ((bytes[at + 2].toLong() and 0xff) shl 8) or
            (bytes[at + 3].toLong() and 0xff)
}
