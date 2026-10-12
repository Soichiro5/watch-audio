package app.watchaudio.core.export

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** Archives from [ZipWriter] read with an independent implementation (`java.util.zip`). */
class ZipWriterJvmTest {
    private val time = LocalDateTime(2026, 10, 11, 9, 30, 15)
    private val file = File.createTempFile("zip-writer", ".zip")

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    private val contents = linkedMapOf(
        "root/README.md" to "# 書き出し".encodeToByteArray(),
        "root/json/2026-10-10.json" to "{\"a\": 1}".encodeToByteArray(),
        "root/audio/2026-10-10/070000_c1.m4a" to Random(1).nextBytes(150_000),
    )

    private fun write(zip64Limit: Long = 0xFFFFFFFFL, zip64CountLimit: Int = 0xFFFF): ByteArray {
        val buffer = Buffer()
        ZipWriter(buffer, time, zip64Limit, zip64CountLimit).apply {
            contents.forEach { (name, bytes) ->
                if (name.endsWith(".m4a")) addFile(name) { Buffer().apply { write(bytes) } } else addFile(name, bytes)
            }
            finish()
        }
        return buffer.readByteArray()
    }

    @Test
    fun crc32AgreesWithTheJdk() {
        val bytes = Random(7).nextBytes(100_003)
        val jdk = CRC32().apply { update(bytes) }.value
        assertEquals(jdk, Crc32().apply { update(bytes) }.value)
    }

    @Test
    fun readableByZipFileAndZipInputStream() = assertReadable(write())

    /**
     * ZIP64 is needed past 4 GiB or 65,535 entries (a year of audio). Lowered limits make every
     * offset and the entry count take the ZIP64 path on a small archive.
     */
    @Test
    fun zip64RecordsAreReadable() = assertReadable(write(zip64Limit = 1, zip64CountLimit = 1))

    private fun assertReadable(bytes: ByteArray) {
        file.writeBytes(bytes)
        ZipFile(file).use { zip ->
            assertEquals(contents.keys.toList(), zip.entries().toList().map { it.name })
            contents.forEach { (name, expected) ->
                val entry = zip.getEntry(name)
                assertEquals(expected.size.toLong(), entry.size)
                assertEquals(java.time.LocalDateTime.of(2026, 10, 11, 9, 30, 14), entry.timeLocal) // 2 s precision
                assertEquals(expected.toList(), zip.getInputStream(entry).readBytes().toList())
            }
        }
        // Streaming readers rely on the sizes in the local headers (no data descriptors).
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val read = generateSequence { stream.nextEntry }.map { it.name to stream.readBytes() }.toList()
            assertEquals(contents.keys.toList(), read.map { it.first })
            read.forEach { (name, data) -> assertEquals(contents.getValue(name).toList(), data.toList()) }
        }
        assertEquals(contents.keys.toList(), readStoredZip(bytes).keys.toList())
    }
}
