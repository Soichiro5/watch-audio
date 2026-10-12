package app.watchaudio.core.export

import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readTo
import kotlinx.io.readIntLe
import kotlinx.io.readShortLe

/**
 * Reads an archive written by [ZipWriter] front to back, without seeking: each entry's local
 * header holds its size (STORED, no data descriptor), so entries can be read in archive order
 * straight from a stream such as a decrypted backup (#40). Stops at the central directory.
 *
 * Throws [ZipFormatException] for anything [ZipWriter] does not write (compressed entries, data
 * descriptors, a CRC-32 that does not match), and [kotlinx.io.EOFException] if the stream ends
 * inside an entry.
 */
class ZipStreamReader(private val source: Source) {
    /** One entry; its content is read with [read] or [readBytes] before the next [next]. */
    class Entry internal constructor(val name: String, val size: Long, internal val crc: Long)

    private var current: Entry? = null
    private var unread = 0L
    private var atDirectory = false

    /** The next entry, or null once the central directory is reached. Skips what is left of the previous one. */
    fun next(): Entry? {
        current?.let { skipRest() }
        current = null
        if (atDirectory) return null
        when (val signature = source.readIntLe()) {
            LOCAL_HEADER -> Unit
            CENTRAL_HEADER, END_OF_DIRECTORY -> {
                atDirectory = true
                return null
            }
            else -> throw ZipFormatException("unexpected signature ${signature.toUInt().toString(16)}")
        }
        source.readShortLe() // version needed
        val flags = source.readShortLe().toInt()
        val method = source.readShortLe().toInt()
        source.readIntLe() // time and date
        val crc = source.readIntLe().toLong() and 0xFFFFFFFFL
        val compressed = source.readIntLe().toLong() and 0xFFFFFFFFL
        val size = source.readIntLe().toLong() and 0xFFFFFFFFL
        val nameLength = source.readShortLe().toInt() and 0xFFFF
        val extraLength = source.readShortLe().toInt() and 0xFFFF
        if (method != 0) throw ZipFormatException("compressed entry")
        if (flags and FLAG_DATA_DESCRIPTOR != 0) throw ZipFormatException("data descriptor")
        if (compressed != size) throw ZipFormatException("sizes differ")
        val name = source.readByteArray(nameLength).decodeToString()
        source.skip(extraLength.toLong())
        unread = size
        return Entry(name, size, crc).also { current = it }
    }

    /** Reads the current entry's content, handing each block to [block], and checks its CRC-32. */
    fun read(block: (ByteArray, Int) -> Unit) {
        val entry = checkNotNull(current) { "no current entry" }
        val crc = Crc32()
        val buffer = ByteArray(BLOCK_SIZE)
        while (unread > 0) {
            val n = minOf(unread, BLOCK_SIZE.toLong()).toInt()
            source.readTo(buffer, 0, n)
            crc.update(buffer, 0, n)
            block(buffer, n)
            unread -= n
        }
        if (crc.value != entry.crc) throw ZipFormatException("CRC-32 of ${entry.name} differs")
        current = null
    }

    /** The current entry's content; fails if it is larger than [maxSize] bytes. */
    fun readBytes(maxSize: Int): ByteArray {
        val entry = checkNotNull(current) { "no current entry" }
        if (entry.size > maxSize) throw ZipFormatException("${entry.name} is too large")
        val out = ByteArray(entry.size.toInt())
        var at = 0
        read { buffer, n ->
            buffer.copyInto(out, at, 0, n)
            at += n
        }
        return out
    }

    /** Reads everything after the last entry (the central directory) to the end of the stream. */
    fun drain() {
        current?.let { skipRest() }
        current = null
        val buffer = ByteArray(BLOCK_SIZE)
        while (source.readAtMostTo(buffer) != -1) {
            // Read to the end, so that a decrypting source authenticates its last chunk.
        }
    }

    private fun skipRest() {
        source.skip(unread)
        unread = 0
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val CENTRAL_HEADER = 0x02014b50
        const val END_OF_DIRECTORY = 0x06054b50
        const val FLAG_DATA_DESCRIPTOR = 0x0008
        const val BLOCK_SIZE = 64 * 1024
    }
}

/** An archive [ZipStreamReader] cannot read. */
class ZipFormatException(message: String) : Exception(message)
