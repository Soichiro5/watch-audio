package app.watchaudio.android.asr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

class ModelArchiveTest {
    private val dir = Files.createTempDirectory("models").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun tarBz2(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        // 100 KB bzip2 blocks, so that stopping early can skip whole blocks.
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes, 1)).use { tar ->
            for ((name, content) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun extractsOnlyWantedEntriesAndStopsEarly() {
        val model = "m/encoder.int8.onnx" to ByteArray(100_000) { (it % 251).toByte() }
        val tokens = "m/tokens.txt" to "a 0\nb 1\n".encodeToByteArray()
        val archive = tarBz2(
            "m/encoder.onnx" to ByteArray(5_000),
            model,
            "./m/tokens.txt" to tokens.second,
            // Incompressible, so it spans many read buffers after the wanted entries.
            "m/test_wavs/1.wav" to Random(1).nextBytes(500_000),
        )
        var read = 0L
        val input = CountingInputStream(ByteArrayInputStream(archive)) { read = it }
        ModelArchive.extract(input, mapOf(model.first to File(dir, "enc"), tokens.first to File(dir, "tok")))

        assertContentEquals(model.second, File(dir, "enc").readBytes())
        assertContentEquals(tokens.second, File(dir, "tok").readBytes())
        assertEquals(setOf("enc", "tok"), dir.list()!!.toSet())
        assertTrue(read < archive.size, "should stop before the trailing test_wavs entry")
    }

    @Test
    fun missingEntryFails() {
        val archive = tarBz2("m/tokens.txt" to ByteArray(10))
        assertFailsWith<IOException> {
            ModelArchive.extract(ByteArrayInputStream(archive), mapOf("m/encoder.int8.onnx" to File(dir, "enc")))
        }
        assertFalse(File(dir, "enc").exists())
    }

    @Test
    fun abortsWhenCancelled() {
        val archive = tarBz2("m/a" to ByteArray(10))
        assertFailsWith<IllegalStateException> {
            ModelArchive.extract(ByteArrayInputStream(archive), mapOf("m/a" to File(dir, "a"))) { error("cancelled") }
        }
    }
}
