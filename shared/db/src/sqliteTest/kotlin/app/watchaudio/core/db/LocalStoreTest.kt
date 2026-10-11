package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
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

    @Test
    fun insertIsIdempotent() = runTest {
        assertTrue(store.transaction { insertChunk(testManifest("a", 0, marks = listOf(1_000)), "a.m4a", 10) })
        assertFalse(store.registerChunk("a", 0, receivedAt = 20))
        val chunk = store.read { chunk("a") }!!
        assertEquals(ChunkState.RECEIVED, chunk.state)
        assertEquals(300_000, chunk.endedAt)
    }

    @Test
    fun lastReceivedAtTracksNewestArrival() = runTest {
        assertNull(store.read { lastReceivedAt() })
        store.transaction {
            registerChunk("a", 0, receivedAt = 20)
            registerChunk("b", 300_000, receivedAt = 10)
        }
        assertEquals(20, store.read { lastReceivedAt() })
    }

    @Test
    fun silentChunkIsNotQueued() = runTest {
        store.registerChunk("silent", 0, audio = false)
        assertEquals(ChunkState.NO_AUDIO, store.read { chunk("silent") }!!.state)
        assertTrue(store.read { pendingChunks(10, 3) }.isEmpty())
    }

    @Test
    fun transcriptionLifecycle() = runTest {
        store.transaction {
            registerChunk("a", 0)
            registerChunk("b", 300_000)
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
    fun neighboursStayWithinSessionAndDevice() = runTest {
        store.transaction {
            registerChunk("a", 0)
            insertChunk(testManifest("other-session", 150_000).copy(sessionId = "s2"), "o.m4a", 0)
            insertChunk(testManifest("other-device", 200_000).copy(deviceId = "phone"), "d.m4a", 0)
            registerChunk("b", 300_000)
            registerChunk("c", 600_000)
        }
        val b = store.read { chunk("b") }!!
        assertEquals("a", store.read { previousInSession(b) }?.id)
        assertEquals("c", store.read { nextInSession(b) }?.id)
        assertNull(store.read { previousInSession(chunk("a")!!) })
        assertNull(store.read { nextInSession(chunk("c")!!) })
    }

    @Test
    fun replaceSegmentsOnlyTouchesTheRange() = runTest {
        store.transaction {
            registerChunk("a", 0)
            saveTranscript(
                "a",
                listOf(NewSegment(1_000, 2_000, "一つ目", "ja"), NewSegment(298_000, 300_000, "途中で切れ", "ja")),
                "test",
            )
            replaceSegments("a", 297_000, Long.MAX_VALUE, listOf(NewSegment(298_000, 302_000, "途中で切れない発話", "ja")), "test2")
        }
        val texts = store.read { segmentsBetween(0, Long.MAX_VALUE) }.map { it.text }
        assertEquals(listOf("一つ目", "途中で切れない発話"), texts)
        assertEquals(1, store.read { search("切れない") }.size)
        assertTrue(store.read { search("途中で切れ") }.all { it.segment.text == "途中で切れない発話" })
        assertEquals(ChunkState.TRANSCRIBED, store.read { chunk("a") }!!.state)
    }

    @Test
    fun japaneseSubstringSearch() = runTest {
        store.transaction {
            registerChunk("a", 0)
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
            registerChunk("a", 0)
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
            registerChunk("a", 0)
            registerChunk("silent", 300_000, audio = false)
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
            registerChunk("a", 0, receivedAt = 10)
            registerChunk("b", 300_000, receivedAt = 20)
        }
        assertEquals("b", store.read { lastChunkBefore() }!!.id)
        assertEquals("a", store.read { lastChunkBefore(300_000) }!!.id)
        assertNull(store.read { lastChunkBefore(0) })
        assertEquals(20, store.read { stats() }.lastReceivedAt)
    }

    @Test
    fun changesCountCommittedWritesOnly() = runTest {
        assertEquals(0, store.changes.value)
        store.registerChunk("a", 0)
        assertEquals(1, store.changes.value)
        store.read { stats() }
        assertEquals(1, store.changes.value)
        runCatching { store.transaction { error("rolled back") } }
        assertEquals(1, store.changes.value)
    }

    /** Creates a database at schema [version] (the first [version] migrations) with one transcribed segment. */
    private fun databaseAtVersion(version: Int): String {
        val path = dir.resolve("v$version.db").toString()
        BundledSQLiteDriver().open(path).use { db ->
            MIGRATIONS.take(version).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = $version")
            db.execSQL(
                "INSERT INTO audio_chunk (id, session_id, device_id, source, started_at, ended_at, time_zone, end_reason, " +
                    "state, received_at, manifest_json) VALUES ('a', 's', 'w', 'WEAROS', 0, 300000, 'UTC', 'ROTATION', 'TRANSCRIBED', 0, '{}')",
            )
            db.execSQL(
                "INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, engine) VALUES ('a', 1000, 2000, '歯医者の予約', 'v1')",
            )
        }
        return path
    }

    /** user_version and the start_at indexes of transcript_segment. */
    private fun schemaOf(path: String): Pair<Int, List<String>> = BundledSQLiteDriver().open(path).use { db ->
        val version = db.prepare("PRAGMA user_version").use { it.step(); it.getInt(0) }
        val indexes = db.prepare(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'transcript_segment' " +
                "AND name LIKE 'idx_segment_start%' ORDER BY name",
        ).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
        version to indexes
    }

    @Test
    fun upgradesOlderDatabasesToTheSearchIndex() = runTest {
        store.close()
        assertEquals(5, MIGRATIONS.size)
        for (version in listOf(1, 3, 4)) {
            val path = databaseAtVersion(version)
            LocalStore.open(path).use { upgraded ->
                assertEquals("歯医者の[予約]", upgraded.read { search("予約") }.single().snippet)
                // Existing segments are in the new index, including a short query at the end of a text.
                assertEquals("歯医者の[予約]", upgraded.read { search("予約", 50, recentSegments = 0) }.single().snippet)
                assertEquals("[歯医者]の予約", upgraded.read { search("歯医者") }.single().snippet)
                // New and deleted segments keep the index in step.
                upgraded.transaction { saveTranscript("a", listOf(NewSegment(1_000, 2_000, "美容院の予約", "ja")), "v5") }
                assertEquals(listOf("美容院の予約"), upgraded.read { search("予約", 50, recentSegments = 0) }.map { it.segment.text })
                assertTrue(upgraded.read { search("歯医者") }.isEmpty())
            }
            assertEquals(5 to listOf("idx_segment_start_at"), schemaOf(path), "from version $version")
        }
    }

    @Test
    fun shortQueriesOutsideTheRecentWindowUseTheTrigramVocabulary() = runTest {
        store.transaction {
            registerChunk("a", 0)
            saveTranscript(
                "a",
                listOf(
                    NewSegment(1_000, 2_000, "明日の会議は十時からです", "ja"),
                    NewSegment(3_000, 4_000, "歯医者の予約", "ja"),
                    NewSegment(5_000, 6_000, "雨", "ja"),
                    NewSegment(7_000, 8_000, "AIの話をした", "ja"),
                    NewSegment(9_000, 10_000, "美容院の予約を取った", "ja"),
                ),
                "test",
            )
        }
        for (window in listOf(0, 1, 2)) {
            suspend fun texts(query: String, limit: Int = 50) =
                store.read { search(query, limit, recentSegments = window) }.map { it.segment.text }

            assertEquals(listOf("美容院の予約を取った", "歯医者の予約"), texts("予約"), "window $window")
            assertEquals(listOf("美容院の予約を取った"), texts("予約", limit = 1), "window $window")
            assertEquals(listOf("雨"), texts("雨"), "window $window")
            assertEquals(listOf("明日の会議は十時からです"), texts("す"), "window $window")
            assertEquals(listOf("AIの話をした"), texts("ai"), "window $window")
            assertEquals(listOf("AIの話をした"), texts("Ai"), "window $window")
            assertTrue(texts("猫舌").isEmpty(), "window $window")
            assertTrue(texts("%").isEmpty(), "window $window")
        }
        assertEquals("歯医者の[予約]", store.read { search("予約", 50, recentSegments = 0) }.last().snippet)

        // Deleted segments leave the vocabulary.
        store.transaction { saveTranscript("a", listOf(NewSegment(1_000, 2_000, "別の内容", "ja")), "test2") }
        assertTrue(store.read { search("予約", 50, recentSegments = 0) }.isEmpty())
        assertEquals(listOf("別の内容"), store.read { search("内容", 50, recentSegments = 0) }.map { it.segment.text })
    }

    @Test
    fun reopeningKeepsDataAndSchema() = runTest {
        store.registerChunk("a", 0)
        store.close()
        LocalStore.open(dbPath).use { reopened ->
            assertEquals("a", reopened.read { chunk("a") }!!.id)
        }
    }

    @Test
    fun settingsAreStoredReplacedAndRemoved() = runTest {
        assertNull(store.read { setting("k") })
        store.transaction { putSetting("k", "1") }
        store.transaction { putSetting("k", "2") }
        store.transaction { putSetting("other", "x") }
        assertEquals("2", store.read { setting("k") })
        assertEquals(mapOf("k" to "2", "other" to "x"), store.read { settings() })
        store.transaction { putSetting("k", null) }
        assertNull(store.read { setting("k") })
        store.close()
        LocalStore.open(dbPath).use { reopened ->
            assertEquals("x", reopened.read { setting("other") })
        }
    }
}
