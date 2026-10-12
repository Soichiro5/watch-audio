package app.watchaudio.core.export

/**
 * Reads back an archive written by [ZipWriter] for tests on every target: walks the local file
 * headers front to back, checks each entry's CRC-32 and that the central directory and its end
 * record follow. Returns entry name to content, in archive order. (The JVM tests also read the
 * archives with `java.util.zip`, an independent reader.)
 */
internal fun readStoredZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    var at = 0
    while (bytes.intLe(at) == 0x04034b50) {
        check(bytes.shortLe(at + 8) == 0) { "entry at $at is not stored" }
        val crc = bytes.intLe(at + 14).toLong() and 0xFFFFFFFFL
        val size = bytes.intLe(at + 18)
        check(size == bytes.intLe(at + 22)) { "sizes differ at $at" }
        val nameLength = bytes.shortLe(at + 26)
        val extraLength = bytes.shortLe(at + 28)
        val name = bytes.decodeToString(at + 30, at + 30 + nameLength)
        val start = at + 30 + nameLength + extraLength
        val content = bytes.copyOfRange(start, start + size)
        check(Crc32().apply { update(content) }.value == crc) { "CRC of $name differs" }
        check(entries.put(name, content) == null) { "duplicate $name" }
        at = start + size
    }
    check(bytes.intLe(at) == 0x02014b50 || entries.isEmpty()) { "no central directory at $at" }
    check(bytes.intLe(bytes.size - 22) == 0x06054b50) { "no end of central directory record" }
    return entries
}

private fun ByteArray.intLe(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLe(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
