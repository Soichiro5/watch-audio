package app.watchaudio.core.db

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Writes behind restoring a backup (#40): by chunk UUID, never twice, never over stored data. */
class RestoreQueriesTest {
    private val dir = TempDir("restore-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun restored(id: String, startedAt: Long, state: ChunkState = ChunkState.TRANSCRIBED, purgedAt: Long? = null) = RestoredChunk(
        id = id,
        sessionId = "s",
        deviceId = "watch",
        source = SourcePlatform.WATCHOS,
        startedAt = startedAt,
        endedAt = startedAt + 300_000,
        timeZone = "Asia/Tokyo",
        audioPath = "2026/10/10/$id.m4a",
        codec = AudioCodec.AAC,
        sizeBytes = 1_000,
        sha256 = "ab",
        endReason = ChunkEndReason.CHARGING,
        state = state,
        attempts = 3,
        lastError = "failed before the backup".takeIf { state == ChunkState.FAILED },
        receivedAt = startedAt + 400_000,
        manifestJson = "{\"chunkId\":\"$id\"}",
        audioPurgedAt = purgedAt,
    )

    @Test
    fun aRestoredChunkIsStoredAsItWas() = runTest {
        val chunk = restored("r1", 1_000_000, ChunkState.FAILED, purgedAt = 5_000_000)
        assertTrue(store.transaction { insertRestoredChunk(chunk) })

        val stored = store.read { chunk("r1") }!!
        assertEquals(
            ChunkRecord(
                id = "r1", sessionId = "s", deviceId = "watch", source = SourcePlatform.WATCHOS, startedAt = 1_000_000,
                endedAt = 1_300_000, timeZone = "Asia/Tokyo", audioPath = "2026/10/10/r1.m4a", endReason = ChunkEndReason.CHARGING,
                state = ChunkState.FAILED, attempts = 3, lastError = "failed before the backup", sizeBytes = 1_000, audioPurgedAt = 5_000_000,
            ),
            stored,
        )
        assertEquals(ChunkBackupInfo(1_400_000, "{\"chunkId\":\"r1\"}"), store.read { backupInfoOfChunksStartedBetween(0, 2_000_000) }["r1"])
        assertEquals(AudioFileInfo(AudioCodec.AAC, "ab"), store.read { audioFilesOfChunksStartedBetween(0, 2_000_000) }["r1"])
    }

    @Test
    fun aStoredChunkIsNotReplaced() = runTest {
        store.registerChunk("r1", 1_000_000)
        assertFalse(store.transaction { insertRestoredChunk(restored("r1", 9_000_000)) })
        assertEquals(1_000_000, store.read { chunk("r1") }?.startedAt)
        assertEquals(ChunkState.RECEIVED, store.read { chunk("r1") }?.state)
    }

    @Test
    fun marksAreAddedOnce() = runTest {
        store.registerChunk("r1", 1_000_000, marks = listOf(10_000))
        assertEquals(1, store.transaction { addMarks("r1", listOf(1_010_000, 1_020_000)) })
        assertEquals(0, store.transaction { addMarks("r1", listOf(1_010_000, 1_020_000)) })
        assertEquals(0, store.transaction { addMarks("missing", listOf(1)) })
        assertEquals(listOf(1_010_000L, 1_020_000L), store.read { marksOfChunksStartedBetween(0, 2_000_000) }.map { it.at })
    }

    @Test
    fun segmentsKeepTheirEnginesAndText() = runTest {
        store.transaction { insertRestoredChunk(restored("r1", 1_000_000)) }
        val segments = listOf(
            RestoredSegment(1_001_000, 1_002_000, "一つ目", "ja", "engine-a"),
            RestoredSegment(1_003_000, 1_004_000, "two", null, "engine-b"),
        )
        store.transaction { addSegments("r1", segments) }
        assertEquals(
            segments,
            store.read { segmentsBetween(0, 2_000_000) }.map { RestoredSegment(it.startAt, it.endAt, it.text, it.lang, it.engine) },
        )
        // Search finds restored text like any other.
        assertEquals(listOf("一つ目"), store.read { search("一つ目", 10) }.map { it.segment.text })
    }

    @Test
    fun replacingATranscriptMarksTheChunkTranscribed() = runTest {
        store.registerChunk("r1", 1_000_000)
        store.transaction { replaceTranscript("r1", listOf(RestoredSegment(1_001_000, 1_002_000, "復元した発話", "ja", "engine-a"))) }
        assertEquals(ChunkState.TRANSCRIBED, store.read { chunk("r1") }?.state)
        assertEquals(listOf("復元した発話"), store.read { segmentsBetween(0, 2_000_000) }.map { it.text })
        store.transaction { replaceTranscript("r1", emptyList()) }
        assertTrue(store.read { segmentsBetween(0, 2_000_000) }.isEmpty())
    }
}
