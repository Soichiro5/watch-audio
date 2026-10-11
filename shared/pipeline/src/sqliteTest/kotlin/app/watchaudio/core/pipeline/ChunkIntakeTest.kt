package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.platform.Sha256
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.protocol.toJson
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** #134: what happens to a chunk from the watch, the same on Android and iOS. */
class ChunkIntakeTest {
    private val dir = TempDir("intake")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val log = mutableListOf<Pair<String, String>>()
    private val diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            log += event to detail
        }
    }
    private val stored = mutableListOf<String>()
    private val intake = ChunkIntake(Ingestor(store, dir.resolve("audio")), diagnostics) { stored += it.chunkId }

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    /** A received chunk: its manifest and the audio file in the inbox. */
    private fun incoming(id: String, bytes: ByteArray = ByteArray(2048) { it.toByte() }, claimedSize: Long = bytes.size.toLong()): Pair<ChunkManifest, Path> {
        val file = dir.resolve("inbox-$id.part")
        SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = 1_791_622_800_000,
            durationMs = 300_000,
            timeZone = "Asia/Tokyo",
            audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, claimedSize, Sha256().apply { update(bytes) }.digestHex()),
            endReason = ChunkEndReason.ROTATION,
        )
        return manifest to file
    }

    private fun events(name: String) = log.filter { it.first == name }.map { it.second }

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    @Test
    fun storesAChunkAndAsksForMore() = runTest {
        val (manifest, file) = incoming("c1")

        val result = intake.receive(manifest.toJson(), file)

        assertEquals(ChunkIntake.Result.Answered(ChunkAck(chunkId = "c1", status = AckStatus.STORED)), result)
        assertEquals(listOf("c1"), stored)
        assertEquals(ChunkState.RECEIVED, store.read { chunk("c1")!!.state })
        assertFalse(exists(file), "the inbox file is moved into the archive")
        assertEquals(listOf("chunk=c1 status=STORED audio_ms=300000 bytes=2048"), events(DiagnosticEvents.CHUNK_RECEIVED))
    }

    @Test
    fun aResentChunkIsADuplicateAndItsFileIsDropped() = runTest {
        val (manifest, first) = incoming("c1")
        intake.accept(manifest, first)
        val (_, again) = incoming("c1")

        val result = intake.accept(manifest, again)

        assertEquals(AckStatus.DUPLICATE, result.ack?.status)
        assertEquals(listOf("c1"), stored, "only the first delivery asks for transcription")
        assertFalse(exists(again))
    }

    @Test
    fun aCorruptChunkIsAnsweredAndItsFileIsDropped() = runTest {
        val (manifest, file) = incoming("c1", claimedSize = 4096)

        val result = intake.accept(manifest, file)

        assertEquals(AckStatus.CORRUPT, result.ack?.status)
        assertTrue(stored.isEmpty())
        assertFalse(exists(file))
        assertNull(store.read { chunk("c1") })
    }

    @Test
    fun aSilentChunkHasNoFile() = runTest {
        val (manifest, _) = incoming("quiet")
        val result = intake.receive(manifest.copy(audio = null).toJson(), null)
        assertEquals(AckStatus.STORED, result.ack?.status)
        assertEquals(listOf("quiet"), stored)
    }

    @Test
    fun anUnreadableManifestThatNamesItsChunkIsRejected() = runTest {
        val (_, file) = incoming("c1")
        // A field this version cannot read (e.g. from a newer watch app).
        val json = """{"schemaVersion":1,"chunkId":"c1","sessionId":"s","source":"pebble"}"""

        val result = intake.receive(json, file)

        assertIs<ChunkIntake.Result.Unreadable>(result)
        assertEquals("c1", result.ack?.chunkId)
        assertEquals(AckStatus.REJECTED, result.ack?.status)
        assertFalse(AckStatus.REJECTED.isFinal, "the watch keeps the chunk")
        assertFalse(exists(file))
        assertTrue(stored.isEmpty())
        assertTrue(events(DiagnosticEvents.CHUNK_REJECTED).single().startsWith("chunk=c1 reason=malformed_manifest error="))
    }

    @Test
    fun anUnreadableManifestWithoutChunkIdIsNotAnswered() = runTest {
        for (json in listOf("not json", "[]", """{"chunkId":""}""", """{"chunkId":7}""")) {
            assertEquals(ChunkIntake.Decoded.Unreadable(null), intake.decode(json), json)
        }
        assertEquals(4, events(DiagnosticEvents.CHUNK_REJECTED).count { it.startsWith("reason=malformed_manifest") })
    }

    @Test
    fun aChunkThatCannotBeIngestedGetsNoAck() = runTest {
        val closed = LocalStore.open(dir.resolve("closed.db").toString()).also { it.close() }
        val broken = ChunkIntake(Ingestor(closed, dir.resolve("audio")), diagnostics) { stored += it.chunkId }
        val (manifest, file) = incoming("c1")

        val result = broken.accept(manifest, file)

        assertIs<ChunkIntake.Result.Failed>(result)
        assertNull(result.ack)
        assertFalse(exists(file), "the watch resends it")
        assertTrue(stored.isEmpty())
        assertTrue(events(DiagnosticEvents.CHUNK_REJECTED).single().startsWith("chunk=c1 reason=ingest_failed error="))
    }
}
