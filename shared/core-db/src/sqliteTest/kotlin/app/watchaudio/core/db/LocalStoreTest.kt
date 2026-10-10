package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalStoreTest {
    private val dir = TempDir("local-store")
    private val dbPath = dir.resolve(LocalStore.FILE_NAME).toString()
    private val store = LocalStore.open(dbPath)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun manifest(id: String, start: Long, audio: Boolean = true, marks: List<Long> = emptyList()) = ChunkManifest(
        chunkId = id,
        sessionId = "s1",
        deviceId = "watch",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = start,
        durationMs = 300_000,
        timeZone = "Asia/Tokyo",
        audio = if (audio) AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00") else null,
        markOffsetsMs = marks,
        endReason = ChunkEndReason.ROTATION,
    )

    @Test
    fun insertIsIdempotent() = runTest {
        assertTrue(store.transaction { insertChunk(manifest("a", 0, marks = listOf(1_000)), "a.m4a", 10) })
        assertFalse(store.transaction { insertChunk(manifest("a", 0), "a.m4a", 20) })
        val chunk = store.read { chunk("a") }!!
        assertEquals(ChunkState.RECEIVED, chunk.state)
        assertEquals(300_000, chunk.endedAt)
    }

    @Test
    fun lastReceivedAtTracksNewestArrival() = runTest {
        assertNull(store.read { lastReceivedAt() })
        store.transaction {
            insertChunk(manifest("a", 0), "a.m4a", 20)
            insertChunk(manifest("b", 300_000), "b.m4a", 10)
        }
        assertEquals(20, store.read { lastReceivedAt() })
    }

    @Test
    fun silentChunkIsNotQueued() = runTest {
        store.transaction { insertChunk(manifest("silent", 0, audio = false), null, 0) }
        assertEquals(ChunkState.NO_AUDIO, store.read { chunk("silent") }!!.state)
        assertTrue(store.read { pendingChunks(10, 3) }.isEmpty())
    }

    @Test
    fun transcriptionLifecycle() = runTest {
        store.transaction {
            insertChunk(manifest("a", 0), "a.m4a", 0)
            insertChunk(manifest("b", 300_000), "b.m4a", 0)
        }
        assertEquals(listOf("a", "b"), store.read { pendingChunks(10, 3) }.map { it.id })

        store.transaction { markTranscribing("a") }
        // A crash while transcribing puts the chunk back in the queue.
        assertEquals(1, store.transaction { resetInterrupted() })

        store.transaction {
            markTranscribing("a")
            saveTranscript("a", listOf(NewSegment(1_000, 4_000, "明日の会議は十時からです", "ja")), "test")
        }
        assertEquals(ChunkState.TRANSCRIBED, store.read { chunk("a") }!!.state)
        assertEquals(listOf("b"), store.read { pendingChunks(10, 3) }.map { it.id })

        store.transaction {
            markTranscribing("b")
            markFailed("b", "boom")
        }
        assertEquals(listOf("b"), store.read { pendingChunks(10, 3) }.map { it.id })
        assertTrue(store.read { pendingChunks(10, 1) }.isEmpty())

        // A user retry resets the attempt budget; only failed chunks are touched.
        assertEquals(1, store.transaction { requeueFailed(listOf("a", "b")) })
        assertEquals(ChunkState.RECEIVED, store.read { chunk("b") }!!.state)
        assertEquals(ChunkState.TRANSCRIBED, store.read { chunk("a") }!!.state)
        assertEquals(listOf("b"), store.read { pendingChunks(10, 1) }.map { it.id })
    }

    @Test
    fun japaneseSubstringSearch() = runTest {
        store.transaction {
            insertChunk(manifest("a", 0), "a.m4a", 0)
            saveTranscript(
                "a",
                listOf(
                    NewSegment(1_000, 4_000, "明日の会議は十時からです", "ja"),
                    NewSegment(5_000, 8_000, "歯医者の予約を来週に変更した", "ja"),
                ),
                "test",
            )
        }
        val hits = store.read { search("会議は") }
        assertEquals(1, hits.size)
        assertEquals("明日の[会議は]十時からです", hits.single().snippet)

        // Two characters: LIKE fallback.
        assertEquals("歯医者の予約を来週に変更した", store.read { search("予約") }.single().segment.text)
        assertTrue(store.read { search("存在しない言葉") }.isEmpty())

        // Re-transcribing replaces old segments, including in the FTS index.
        store.transaction { saveTranscript("a", listOf(NewSegment(1_000, 4_000, "別の内容", "ja")), "test2") }
        assertTrue(store.read { search("会議は") }.isEmpty())
    }

    @Test
    fun issueExamplesMatchSubstrings() = runTest {
        store.transaction {
            insertChunk(manifest("a", 0), "a.m4a", 0)
            saveTranscript(
                "a",
                listOf(
                    NewSegment(1_000, 2_000, "午後の定例会議で予算を確認", "ja"),
                    NewSegment(3_000, 4_000, "朝のミーティングは中止です", "ja"),
                    NewSegment(5_000, 6_000, "オンラインミーティングのリンクを送った", "ja"),
                ),
                "test",
            )
        }
        // "会議" (two characters, LIKE) matches inside a longer word.
        assertEquals(listOf("午後の定例[会議]で予算を確認"), store.read { search("会議") }.map { it.snippet })
        // "ミーティング" (trigram FTS) matches both segments, newest first.
        assertEquals(
            listOf("オンライン[ミーティング]のリンクを送った", "朝の[ミーティング]は中止です"),
            store.read { search("ミーティング") }.map { it.snippet },
        )
    }

    @Test
    fun statsAndRanges() = runTest {
        store.transaction {
            insertChunk(manifest("a", 0), "a.m4a", 0)
            insertChunk(manifest("silent", 300_000, audio = false), null, 0)
        }
        val stats = store.read { stats() }
        assertEquals(2, stats.chunks)
        assertEquals(1, stats.pendingChunks)
        assertEquals(300_000, stats.recordedMs)
        assertEquals(0, stats.lastReceivedAt)
        assertEquals(listOf("silent"), store.read { chunksBetween(400_000, 500_000) }.map { it.id })
        assertNull(store.read { chunk("missing") })
    }

    @Test
    fun emptyStats() = runTest {
        val stats = store.read { stats() }
        assertEquals(0, stats.chunks)
        assertNull(stats.lastReceivedAt)
        assertNull(store.read { lastChunkBefore() })
    }

    @Test
    fun lastChunkBefore() = runTest {
        store.transaction {
            insertChunk(manifest("a", 0), "a.m4a", 10)
            insertChunk(manifest("b", 300_000), "b.m4a", 20)
        }
        assertEquals("b", store.read { lastChunkBefore() }!!.id)
        assertEquals("a", store.read { lastChunkBefore(300_000) }!!.id)
        assertNull(store.read { lastChunkBefore(0) })
        assertEquals(20, store.read { stats() }.lastReceivedAt)
    }

    @Test
    fun changesCountCommittedWritesOnly() = runTest {
        assertEquals(0, store.changes.value)
        store.transaction { insertChunk(manifest("a", 0), "a.m4a", 0) }
        assertEquals(1, store.changes.value)
        store.read { stats() }
        assertEquals(1, store.changes.value)
        runCatching { store.transaction { error("rolled back") } }
        assertEquals(1, store.changes.value)
    }

    @Test
    fun upgradesAVersion1Database() = runTest {
        store.close()
        val path = dir.resolve("v1.db").toString()
        BundledSQLiteDriver().open(path).use { db ->
            MIGRATIONS[0].forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 1")
            db.execSQL(
                "INSERT INTO audio_chunk (id, session_id, device_id, source, started_at, ended_at, time_zone, end_reason, " +
                    "state, received_at, manifest_json) VALUES ('a', 's', 'w', 'WEAROS', 0, 300000, 'UTC', 'ROTATION', 'TRANSCRIBED', 0, '{}')",
            )
            db.execSQL(
                "INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, engine) VALUES ('a', 1000, 2000, '歯医者の予約', 'v1')",
            )
        }
        LocalStore.open(path).use { upgraded ->
            assertEquals("歯医者の[予約]", upgraded.read { search("予約") }.single().snippet)
            assertEquals("[歯医者]の予約", upgraded.read { search("歯医者") }.single().snippet)
        }
    }

    @Test
    fun reopeningKeepsDataAndSchema() = runTest {
        store.transaction { insertChunk(manifest("a", 0), "a.m4a", 0) }
        store.close()
        LocalStore.open(dbPath).use { reopened ->
            assertEquals("a", reopened.read { chunk("a") }!!.id)
        }
    }
}
