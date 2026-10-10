package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.platform.Sha256
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class IngestorTest {
    private val dir = Files.createTempDirectory("ingest").toFile()
    private val store = LocalStore.open(File(dir, "db.sqlite").path)
    private val ingestor = Ingestor(store, Path(File(dir, "audio").path))

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun incoming(id: String, bytes: ByteArray): Pair<ChunkManifest, Path> {
        val file = File(dir, "incoming-$id.m4a").apply { writeBytes(bytes) }
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WATCHOS,
            startedAtEpochMs = 1_791_622_800_000, // 2026-10-10T09:00:00Z
            durationMs = 300_000,
            timeZone = "Asia/Tokyo",
            audio = AudioFile(file.name, AudioCodec.AAC, 16_000, 1, 24_000, bytes.size.toLong(), Sha256().apply { update(bytes) }.digestHex()),
            endReason = ChunkEndReason.ROTATION,
        )
        return manifest to Path(file.path)
    }

    @Test
    fun storesVerifiedChunkInDatedFolder() = runTest {
        val (manifest, file) = incoming("c1", ByteArray(4096) { it.toByte() })
        assertEquals(AckStatus.STORED, ingestor.ingest(manifest, file).status)

        val chunk = store.read { chunk("c1") }!!
        assertEquals("2026/10/10/c1.m4a", chunk.audioPath)
        assertEquals(ChunkState.RECEIVED, chunk.state)
        assertTrue(File(dir, "audio/2026/10/10/c1.m4a").exists())
        assertFalse(File(file.toString()).exists())
    }

    @Test
    fun resendIsDuplicate() = runTest {
        val (manifest, file) = incoming("c1", ByteArray(10) { 1 })
        ingestor.ingest(manifest, file)
        val again = File(dir, "again.m4a").apply { writeBytes(ByteArray(10) { 1 }) }
        assertEquals(AckStatus.DUPLICATE, ingestor.ingest(manifest, Path(again.path)).status)
        assertFalse(again.exists())
    }

    @Test
    fun corruptFileIsNotStored() = runTest {
        val (manifest, file) = incoming("c1", ByteArray(10) { 1 })
        File(file.toString()).writeBytes(ByteArray(10) { 2 })
        val ack = ingestor.ingest(manifest, file)
        assertEquals(AckStatus.CORRUPT, ack.status)
        assertFalse(ack.status.isFinal)
        assertEquals(null, store.read { chunk("c1") })
    }

    @Test
    fun silentChunkNeedsNoFile() = runTest {
        val (manifest, _) = incoming("quiet", ByteArray(1))
        val silent = manifest.copy(audio = null, endReason = ChunkEndReason.CHARGING)
        assertEquals(AckStatus.STORED, ingestor.ingest(silent, null).status)
        assertEquals(ChunkState.NO_AUDIO, store.read { chunk("quiet") }!!.state)
    }

    @Test
    fun newerSchemaIsRejected() = runTest {
        val (manifest, file) = incoming("future", ByteArray(1))
        val ack = ingestor.ingest(manifest.copy(schemaVersion = 99), file)
        assertEquals(AckStatus.REJECTED, ack.status)
    }
}
