package app.watchaudio.wear.recording

import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.RecordingFormat
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.wear.outbox.Outbox
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkFinalizerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val recordingDir by lazy { RecordingDir(tmp.newFolder("recording")) }
    private val outbox by lazy { Outbox(tmp.newFolder("outbox")) { 0L } }
    private val finalizer by lazy { ChunkFinalizer(recordingDir, outbox) }

    private fun journal(id: String = "chunk-1", loud: Boolean = true, marks: List<Long> = emptyList()): ChunkJournal {
        val silence = SilenceDetector().apply { repeat(300) { add(if (loud && it == 10) 9000 else 50) } }
        return ChunkJournal(
            chunkId = id,
            sessionId = "session-1",
            deviceId = "device-1",
            startedAtEpochMs = 1_791_622_800_000,
            timeZone = "Asia/Tokyo",
            markOffsetsMs = marks,
            lastAliveAtEpochMs = 1_791_622_800_000 + 120_000,
        ).withSilence(silence)
    }

    private fun writeAudio(id: String, bytes: ByteArray = finalizedMp4()): File =
        recordingDir.audioFile(id).apply { writeBytes(bytes) }

    @Test
    fun `finalizes a regular chunk with hash, size and marks`() {
        val j = journal(marks = listOf(250_000, 12_000, 300_050))
        recordingDir.write(j)
        val audio = writeAudio(j.chunkId)
        val expectedHash = sha256Of(Path(audio.path))
        val expectedSize = audio.length()

        val result = finalizer.finalize(j, 300_000, ChunkEndReason.ROTATION)

        val m = result.manifest
        assertEquals(SourcePlatform.WEAROS, m.source)
        assertEquals(300_000, m.durationMs)
        assertEquals(listOf(12_000L, 250_000, 300_000), m.markOffsetsMs, "sorted and clamped to the chunk")
        val a = assertNotNull(m.audio)
        assertEquals("chunk-1.m4a", a.fileName)
        assertEquals(expectedHash, a.sha256)
        assertEquals(expectedSize, a.sizeBytes)
        assertEquals(24_000, a.bitrateBps)
        assertEquals(16_000, a.sampleRateHz)

        val entry = outbox.entries().single()
        assertEquals(m, entry.manifest)
        assertTrue(entry.audioFile!!.isFile)
        assertTrue(recordingDir.journals().isEmpty())
        assertFalse(recordingDir.audioFile("chunk-1").exists())
    }

    @Test
    fun `silent chunk keeps the span but drops the audio`() {
        val j = journal(loud = false)
        recordingDir.write(j)
        writeAudio(j.chunkId)

        val result = finalizer.finalize(j, 300_000, ChunkEndReason.ROTATION)

        assertTrue(result.silent)
        assertNull(result.manifest.audio)
        assertEquals(300_000, result.manifest.durationMs)
        assertEquals(listOf("chunk-1.json"), outbox.dir.list()!!.toList())
    }

    @Test
    fun `unusable audio becomes a zero-length gap marker`() {
        val j = journal()
        recordingDir.write(j)
        writeAudio(j.chunkId)

        val result = finalizer.finalize(j, 4_000, ChunkEndReason.INTERRUPTION, audioUsable = false)

        assertTrue(result.audioDropped)
        assertNull(result.manifest.audio)
        assertEquals(0, result.manifest.durationMs)
        assertEquals(ChunkEndReason.INTERRUPTION, result.manifest.endReason)
    }

    @Test
    fun `recovery finalizes leftovers as terminated and cleans orphans`() {
        val good = journal(id = "good")
        recordingDir.write(good)
        writeAudio("good")
        val broken = journal(id = "broken")
        recordingDir.write(broken)
        writeAudio("broken", unfinalizedMp4())
        writeAudio("orphan") // next file prepared but never started: no journal

        val results = finalizer.recoverLeftovers().associateBy { it.manifest.chunkId }

        assertEquals(setOf("good", "broken"), results.keys)
        results.values.forEach { assertEquals(ChunkEndReason.TERMINATED, it.manifest.endReason) }
        assertEquals(120_000, results.getValue("good").manifest.durationMs, "up to the last heartbeat")
        assertNotNull(results.getValue("good").manifest.audio)
        assertNull(results.getValue("broken").manifest.audio)
        assertEquals(0, results.getValue("broken").manifest.durationMs)
        assertTrue(recordingDir.dir.list()!!.isEmpty())
        assertEquals(2, outbox.count())
    }

    @Test
    fun `describes audio in the shared recording format with a lowercase hex sha256`() {
        val file = tmp.newFile("test.bin").apply { writeText("test") }
        val expected = RecordingFormat.CURRENT.audioFile("x.m4a", 4, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        assertEquals(expected, ChunkFinalizer.describe(file, "x.m4a"))
        assertEquals(900_000L, ChunkRecorder.CHUNK_BYTES, "5 minutes at 24 kbps")
    }

    @Test
    fun `mp4 probe detects the moov box`() {
        assertTrue(Mp4Probe.isFinalized(writeAudio("a", finalizedMp4())))
        assertFalse(Mp4Probe.isFinalized(writeAudio("b", unfinalizedMp4())))
        assertFalse(Mp4Probe.isFinalized(writeAudio("c", ByteArray(3))))
        assertFalse(Mp4Probe.isFinalized(File(tmp.root, "missing.m4a")))
        // Truncated moov (process died while writing it).
        assertFalse(Mp4Probe.isFinalized(writeAudio("d", finalizedMp4().copyOf(finalizedMp4().size - 4))))
    }

    companion object {
        private fun box(type: String, payload: ByteArray): ByteArray {
            val size = 8 + payload.size
            return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
                type.toByteArray(Charsets.ISO_8859_1) + payload
        }

        fun unfinalizedMp4() = box("ftyp", "M4A ".toByteArray()) + box("mdat", ByteArray(2048) { it.toByte() })

        fun finalizedMp4() = unfinalizedMp4() + box("moov", ByteArray(64))
    }
}
