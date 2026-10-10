package app.watchaudio.core.pipeline

import app.watchaudio.core.db.AudioUsage
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.pipeline.AudioRetentionPolicy.DAY_MS
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class StorageManagerTest {
    private val dir = Files.createTempDirectory("storage").toFile()
    private val audioDir = File(dir, "audio")
    private val store = LocalStore.open(File(dir, LocalStore.FILE_NAME).path)
    private val now = 1_791_622_800_000L // 2026-10-10T09:00:00Z
    private val environment = object : StorageEnvironment {
        var free: Long? = 5L shl 30
        override fun modelBytes(): Long = 300_000_000
        override fun freeBytes(): Long? = free
    }
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }
    private val storage = StorageManager(store, Path(audioDir.path), environment, clock)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    /** Stores a chunk that ended [daysAgo] days ago, with a real audio file of [size] bytes. */
    private suspend fun addChunk(id: String, daysAgo: Int, size: Int = 1_000, start: Long = now - daysAgo * DAY_MS - 300_000): String {
        val relative = "2026/10/${id.hashCode().mod(28) + 1}/$id.m4a"
        File(audioDir, relative).apply { parentFile.mkdirs() }.writeBytes(ByteArray(size))
        store.transaction {
            insertChunk(
                ChunkManifest(
                    chunkId = id, sessionId = "s", deviceId = "w", source = SourcePlatform.WEAROS,
                    startedAtEpochMs = start, durationMs = 300_000, timeZone = "UTC",
                    audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, size.toLong(), "00"),
                    endReason = ChunkEndReason.ROTATION,
                ),
                relative,
                0,
            )
        }
        return relative
    }

    private suspend fun transcribe(id: String, text: String = "おはようございます") = store.transaction {
        val chunk = chunk(id)!!
        markTranscribing(id)
        saveTranscript(id, listOf(NewSegment(chunk.startedAt + 1_000, chunk.startedAt + 2_000, text, "ja")), "test")
    }

    private fun exists(relative: String) = File(audioDir, relative).exists()

    @Test
    fun defaultKeepsAllAudio() = runTest {
        val path = addChunk("old", daysAgo = 400)
        transcribe("old")
        assertEquals(AudioRetention.UNLIMITED, storage.retention())
        assertEquals(StorageManager.PurgeResult(0, 0), storage.applyRetention())
        assertTrue(exists(path))
    }

    @Test
    fun retentionDeletesOnlyOldTranscribedAudioAndKeepsTranscripts() = runTest {
        val old = addChunk("old", daysAgo = 10, size = 2_000)
        val oldPending = addChunk("oldPending", daysAgo = 10)
        val oldFailed = addChunk("oldFailed", daysAgo = 10)
        val recent = addChunk("recent", daysAgo = 2)
        transcribe("old", "十日前の会議")
        transcribe("recent")
        store.transaction {
            markTranscribing("oldFailed")
            markFailed("oldFailed", "boom")
        }

        storage.setRetention(AudioRetention.DAYS_7)
        assertEquals(AudioUsage(chunks = 1, bytes = 2_000), storage.previewRetention(AudioRetention.DAYS_7))
        assertEquals(StorageManager.PurgeResult(purgedChunks = 1, freedBytes = 2_000), storage.applyRetention())

        assertFalse(exists(old))
        assertTrue(exists(oldPending))
        assertTrue(exists(oldFailed))
        assertTrue(exists(recent))
        val purged = store.read { chunk("old") }!!
        assertEquals(now, purged.audioPurgedAt)
        assertEquals(ChunkState.TRANSCRIBED, purged.state)
        assertEquals("十日前の会議", store.read { search("十日前の") }.single().segment.text)

        // Idempotent; nothing left to do.
        assertEquals(StorageManager.PurgeResult(0, 0), storage.applyRetention())
        // Once the pending chunk is transcribed, it goes too.
        transcribe("oldPending")
        assertEquals(1, storage.applyRetention().purgedChunks)
        assertFalse(exists(oldPending))
    }

    @Test
    fun applyRetentionStopsWhenAsked() = runTest {
        repeat(3) {
            addChunk("c$it", daysAgo = 100)
            transcribe("c$it")
        }
        storage.setRetention(AudioRetention.DAYS_90)
        assertEquals(0, storage.applyRetention(shouldContinue = { false }).purgedChunks)
        assertEquals(3, storage.applyRetention().purgedChunks)
    }

    @Test
    fun aMissingFileStillCountsAsPurged() = runTest {
        val path = addChunk("gone", daysAgo = 40)
        transcribe("gone")
        File(audioDir, path).delete() // e.g. a previous run died after deleting the file
        storage.setRetention(AudioRetention.DAYS_30)
        assertEquals(1, storage.applyRetention().purgedChunks)
        assertNotNull(store.read { chunk("gone") }!!.audioPurgedAt)
    }

    @Test
    fun breakdownReportsAudioDatabaseModelsAndFreeSpace() = runTest {
        addChunk("a", daysAgo = 40, size = 3_000)
        addChunk("b", daysAgo = 1, size = 5_000)
        transcribe("a")
        var breakdown = storage.breakdown()
        assertEquals(8_000, breakdown.audioBytes)
        assertTrue(breakdown.databaseBytes > 0)
        assertEquals(300_000_000, breakdown.modelBytes)
        assertEquals(8_000 + breakdown.databaseBytes + 300_000_000, breakdown.totalBytes)
        assertFalse(breakdown.isLowOnSpace)

        storage.setRetention(AudioRetention.DAYS_30)
        storage.applyRetention()
        environment.free = 500L shl 20
        breakdown = storage.breakdown()
        assertEquals(5_000, breakdown.audioBytes)
        assertEquals(1, breakdown.audioPurgedChunks)
        assertTrue(breakdown.isLowOnSpace)

        environment.free = null
        assertFalse(storage.breakdown().isLowOnSpace)
    }

    @Test
    fun deleteRangeRemovesOnlyChunksStartingInIt() = runTest {
        val dayStart = now - 3 * DAY_MS
        val inside = addChunk("inside", daysAgo = 0, start = dayStart + 3_600_000)
        val before = addChunk("before", daysAgo = 0, start = dayStart - 60_000) // started the previous day
        val after = addChunk("after", daysAgo = 0, start = dayStart + DAY_MS)
        transcribe("inside", "削除される発話")
        transcribe("before", "残る発話")

        assertEquals(1, storage.deleteRange(dayStart, dayStart + DAY_MS))

        assertNull(store.read { chunk("inside") })
        assertFalse(exists(inside))
        assertTrue(exists(before))
        assertTrue(exists(after))
        assertTrue(store.read { search("削除される") }.isEmpty())
        assertEquals(1, store.read { search("残る発話") }.size)
        assertEquals(0, storage.deleteRange(dayStart, dayStart + DAY_MS))
    }

    @Test
    fun deleteAllRemovesRecordingsAudioAndOrphansButKeepsSettings() = runTest {
        val a = addChunk("a", daysAgo = 1)
        addChunk("b", daysAgo = 2)
        transcribe("a")
        val orphan = File(audioDir, "2025/01/01/orphan.m4a").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        val unrelated = File(audioDir, "notes.txt").apply { writeText("keep") }
        storage.setRetention(AudioRetention.DAYS_90)

        storage.deleteAll()

        val stats = store.read { stats() }
        assertEquals(0, stats.chunks)
        assertEquals(0, stats.segments)
        assertFalse(exists(a))
        assertFalse(orphan.exists())
        assertTrue(audioDir.listFiles()!!.none { it.isDirectory }, "date folders are removed")
        assertTrue(unrelated.exists())
        assertEquals(AudioRetention.DAYS_90, storage.retention())
        assertEquals(0, storage.breakdown().audioBytes)
    }
}
