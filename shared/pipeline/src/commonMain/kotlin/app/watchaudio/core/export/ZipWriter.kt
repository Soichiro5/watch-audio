package app.watchaudio.core.export

import kotlinx.datetime.LocalDateTime
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
import kotlinx.io.writeShortLe

/**
 * Writes a ZIP archive of uncompressed (STORED) entries to a stream, front to back, without
 * seeking and without holding any entry in memory: a file entry is read twice, once for its
 * CRC-32 and once to copy it. Audio is already compressed, so deflating would gain little.
 *
 * Archives past 4 GiB or 65,535 entries (a year of audio) use the ZIP64 extensions; a single
 * entry must stay below 4 GiB. Entry names are UTF-8 (general purpose flag bit 11).
 *
 * Call [finish] once after the last entry to write the central directory; it does not close the
 * sink. After an exception the archive is incomplete and must be discarded.
 */
class ZipWriter internal constructor(
    sink: RawSink,
    modifiedAt: LocalDateTime,
    /** Offsets and sizes from this value on are written as ZIP64 (lowered by tests only). */
    private val zip64Limit: Long,
    /** Entry counts from this value on are written as ZIP64 (lowered by tests only). */
    private val zip64CountLimit: Int,
) {
    /** @param modifiedAt the local time stamped on every entry (ZIP stores local time, 2 s precision). */
    constructor(sink: RawSink, modifiedAt: LocalDateTime) : this(sink, modifiedAt, MAX_32, MAX_16)

    private class Entry(val name: ByteArray, val crc: Long, val size: Long, val offset: Long)

    private val out: Sink = sink.buffered()
    private val entries = mutableListOf<Entry>()
    private val names = mutableSetOf<String>()
    private val dosTime = with(modifiedAt) { (hour shl 11) or (minute shl 5) or (second / 2) }
    private val dosDate = with(modifiedAt) { ((year - 1980).coerceIn(0, 127) shl 9) or (month.ordinal + 1 shl 5) or day }

    /** Bytes written so far: the offset of the next record. */
    private var position = 0L
    private var finished = false

    /** Adds [name] (a `/`-separated relative path) with [content]. */
    fun addFile(name: String, content: ByteArray) {
        val crc = Crc32().apply { update(content) }.value
        writeEntry(name, crc, content.size.toLong()) {
            out.write(content)
        }
    }

    /**
     * Adds [name] with the bytes of [open], which is called twice and must return the same bytes
     * both times (e.g. a file that is not being written). Fails if they differ.
     */
    fun addFile(name: String, open: () -> RawSource) {
        val first = Crc32()
        val size = open().buffered().use { source -> copy(source, first) { _, _ -> } }
        writeEntry(name, first.value, size) {
            val second = Crc32()
            val copied = open().buffered().use { source -> copy(source, second) { buffer, n -> out.write(buffer, 0, n) } }
            check(copied == size && second.value == first.value) { "$name changed while it was being archived" }
        }
    }

    /** Writes the central directory and flushes. No entries can be added afterwards. */
    fun finish() {
        checkOpen()
        finished = true
        val directoryOffset = position
        entries.forEach(::writeCentralHeader)
        val directorySize = position - directoryOffset
        val zip64 = entries.size >= zip64CountLimit || directoryOffset >= zip64Limit || directorySize >= zip64Limit
        if (zip64) {
            val recordOffset = position
            // ZIP64 end of central directory record, then its locator.
            out.writeIntLe(0x06064b50)
            out.writeLongLe(44) // size of the rest of this record
            out.writeShortLe(VERSION_ZIP64)
            out.writeShortLe(VERSION_ZIP64)
            out.writeIntLe(0) // this disk
            out.writeIntLe(0) // disk with the central directory
            out.writeLongLe(entries.size.toLong())
            out.writeLongLe(entries.size.toLong())
            out.writeLongLe(directorySize)
            out.writeLongLe(directoryOffset)
            out.writeIntLe(0x07064b50)
            out.writeIntLe(0) // disk with the ZIP64 record
            out.writeLongLe(recordOffset)
            out.writeIntLe(1) // total disks
            position += 56 + 20
        }
        out.writeIntLe(0x06054b50)
        out.writeShortLe(0) // this disk
        out.writeShortLe(0) // disk with the central directory
        val count = if (zip64) MAX_16 else entries.size
        out.writeShortLe(count.toShort())
        out.writeShortLe(count.toShort())
        out.writeIntLe(if (zip64) MAX_32.toInt() else directorySize.toInt())
        out.writeIntLe(if (zip64) MAX_32.toInt() else directoryOffset.toInt())
        out.writeShortLe(0) // comment length
        position += 22
        out.flush()
    }

    private inline fun writeEntry(name: String, crc: Long, size: Long, writeData: () -> Unit) {
        checkOpen()
        require(name.isNotEmpty() && !name.startsWith("/") && "\\" !in name && name.split('/').none { it == ".." }) {
            "Invalid entry name $name"
        }
        require(names.add(name)) { "Duplicate entry $name" }
        require(size < MAX_32) { "$name is too large for one entry" }
        val nameBytes = name.encodeToByteArray()
        val offset = position
        out.writeIntLe(0x04034b50)
        out.writeShortLe(VERSION_DEFAULT)
        out.writeShortLe(FLAG_UTF8)
        out.writeShortLe(0) // method: stored
        out.writeShortLe(dosTime.toShort())
        out.writeShortLe(dosDate.toShort())
        out.writeIntLe(crc.toInt())
        out.writeIntLe(size.toInt()) // compressed size
        out.writeIntLe(size.toInt()) // uncompressed size
        out.writeShortLe(nameBytes.size.toShort())
        out.writeShortLe(0) // extra length
        out.write(nameBytes)
        writeData()
        position += 30 + nameBytes.size + size
        entries += Entry(nameBytes, crc, size, offset)
    }

    private fun writeCentralHeader(entry: Entry) {
        val zip64 = entry.offset >= zip64Limit
        out.writeIntLe(0x02014b50)
        out.writeShortLe(if (zip64) VERSION_ZIP64 else VERSION_DEFAULT) // made by
        out.writeShortLe(if (zip64) VERSION_ZIP64 else VERSION_DEFAULT) // needed to extract
        out.writeShortLe(FLAG_UTF8)
        out.writeShortLe(0) // method: stored
        out.writeShortLe(dosTime.toShort())
        out.writeShortLe(dosDate.toShort())
        out.writeIntLe(entry.crc.toInt())
        out.writeIntLe(entry.size.toInt())
        out.writeIntLe(entry.size.toInt())
        out.writeShortLe(entry.name.size.toShort())
        out.writeShortLe(if (zip64) 12 else 0) // extra length
        out.writeShortLe(0) // comment length
        out.writeShortLe(0) // disk number
        out.writeShortLe(0) // internal attributes
        out.writeIntLe(0) // external attributes
        out.writeIntLe(if (zip64) MAX_32.toInt() else entry.offset.toInt())
        out.write(entry.name)
        if (zip64) {
            out.writeShortLe(0x0001) // ZIP64 extended information
            out.writeShortLe(8)
            out.writeLongLe(entry.offset)
        }
        position += 46 + entry.name.size + if (zip64) 12 else 0
    }

    private fun checkOpen() = check(!finished) { "The archive is already finished" }

    /** Reads [source] to the end through [crc], handing each block to [block]; @return bytes read. */
    private inline fun copy(source: Source, crc: Crc32, block: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(BLOCK_SIZE)
        var total = 0L
        while (true) {
            val n = source.readAtMostTo(buffer)
            if (n == -1) break
            crc.update(buffer, 0, n)
            block(buffer, n)
            total += n
        }
        return total
    }

    private companion object {
        const val MAX_32 = 0xFFFFFFFFL
        const val MAX_16 = 0xFFFF
        const val VERSION_DEFAULT: Short = 20
        const val VERSION_ZIP64: Short = 45
        const val FLAG_UTF8: Short = 0x0800
        const val BLOCK_SIZE = 64 * 1024
    }
}
