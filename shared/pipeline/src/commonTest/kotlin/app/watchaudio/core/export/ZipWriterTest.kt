package app.watchaudio.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.LocalDateTime
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

class ZipWriterTest {
    private val time = LocalDateTime(2026, 10, 11, 9, 30, 15)

    @Test
    fun crc32MatchesTheStandardCheckValue() {
        assertEquals(0xCBF43926L, Crc32().apply { update("123456789".encodeToByteArray()) }.value)
        assertEquals(0L, Crc32().value)
        // Incremental updates give the same result as one.
        val split = Crc32().apply {
            update("12345".encodeToByteArray())
            update("x6789x".encodeToByteArray(), 1, 4)
        }
        assertEquals(0xCBF43926L, split.value)
    }

    @Test
    fun writesStoredEntriesFromBytesAndFromStreams() {
        val buffer = Buffer()
        val audio = ByteArray(200_000) { (it * 31).toByte() }
        ZipWriter(buffer, time).apply {
            addFile("root/日本語.md", "# こんにちは".encodeToByteArray())
            addFile("root/audio/a.m4a") { Buffer().apply { write(audio) } }
            addFile("root/empty.json", ByteArray(0))
            finish()
        }

        val entries = readStoredZip(buffer.readByteArray())
        assertEquals(listOf("root/日本語.md", "root/audio/a.m4a", "root/empty.json"), entries.keys.toList())
        assertEquals("# こんにちは", entries.getValue("root/日本語.md").decodeToString())
        assertEquals(audio.toList(), entries.getValue("root/audio/a.m4a").toList())
        assertEquals(0, entries.getValue("root/empty.json").size)
    }

    @Test
    fun refusesUnsafeOrDuplicateNamesAndChangingStreams() {
        val zip = ZipWriter(Buffer(), time)
        listOf("", "/abs", "a/../b", "a\\b").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { zip.addFile(name, ByteArray(1)) }
        }
        zip.addFile("a", ByteArray(1))
        assertFailsWith<IllegalArgumentException> { zip.addFile("a", ByteArray(1)) }

        // A file that grows between the checksum pass and the copy would make a corrupt entry.
        var reads = 0
        assertFailsWith<IllegalStateException> {
            zip.addFile("b") { Buffer().apply { write(ByteArray(10 + reads++)) } }
        }

        val finished = ZipWriter(Buffer(), time).apply { finish() }
        assertFailsWith<IllegalStateException> { finished.addFile("c", ByteArray(1)) }
    }
}
