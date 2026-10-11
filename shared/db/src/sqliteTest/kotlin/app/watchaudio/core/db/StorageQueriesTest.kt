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

/** Queries behind audio retention, per-day / full deletion and app settings (#33). */
class StorageQueriesTest {
    private val dir = TempDir("storage-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun manifest(id: String, start: Long, audio: Boolean = true) = ChunkManifest(
        chunkId = id,
        sessionId = "s1",
        deviceId = "watch",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = start,
        durationMs = 300_000,
        timeZone = "Asia/Tokyo",
        audio = if (audio) AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00") else null,
        markOffsetsMs = listOf(1_000),
        endReason = ChunkEndReason.ROTATION,
    )

    private suspend fun insert(id: String, start: Long, audio: Boolean = true) =
        store.transaction { insertChunk(manifest(id, start, audio), if (audio) "$id.m4a" else null, 0) }

    private suspend fun transcribe(id: String, text: String = "会議の資料を送ります") = store.transaction {
        val start = chunk(id)!!.startedAt
        markTranscribing(id)
        saveTranscript(id, listOf(NewSegment(start + 1_000, start + 2_000, text, "ja")), "test")
    }

    @Test
    fun onlyTranscribedAudioOldEnoughIsPurgeable() = runTest {
        insert("old", 0)
        insert("oldPending", 300_000)
        insert("oldFailed", 600_000)
        insert("silent", 900_000, audio = false)
        insert("recent", 10_000_000)
        transcribe("old")
        transcribe("recent", "最近の話")
        store.transaction {
            markTranscribing("oldFailed")
            markFailed("oldFailed", "boom")
        }

        val cutoff = 5_000_000L
        assertEquals(listOf("old"), store.read { purgeableChunks(cutoff, 10) }.map { it.id })
        assertEquals(AudioUsage(chunks = 1, bytes = 1_000), store.read { purgeableUsage(cutoff) })

        // Marking refuses untranscribed chunks even when asked directly.
        assertEquals(1, store.transaction { markAudioPurged(listOf("old", "oldPending", "oldFailed"), 42) })
        val old = store.read { chunk("old") }!!
        assertEquals(42, old.audioPurgedAt)
        assertFalse(old.hasAudioFile)
        assertEquals("old.m4a", old.audioPath) // kept: the timeline still shows it as recorded audio
        assertTrue(store.read { chunk("oldPending") }!!.hasAudioFile)
        assertTrue(store.read { purgeableChunks(cutoff, 10) }.isEmpty())

        // The transcript stays searchable; the stats no longer count the audio.
        assertEquals(1, store.read { search("会議の資料") }.size)
        val stats = store.read { stats() }
        assertEquals(1, stats.audioPurgedChunks)
        assertEquals(3_000, stats.audioBytes)
        assertEquals(4 * 300_000L, stats.recordedMs)
        assertEquals(setOf("oldPending.m4a", "oldFailed.m4a", "recent.m4a"), store.read { audioPaths() })
    }

    @Test
    fun deletingChunksRemovesTranscriptsMarksAndIndex() = runTest {
        insert("a", 0)
        insert("b", 300_000)
        transcribe("a", "明日の会議は十時から")
        transcribe("b", "歯医者の予約")

        val day = store.read { chunksStartedBetween(0, 300_000) }
        assertEquals(listOf("a"), day.map { it.id })
        assertEquals(1, store.transaction { deleteChunks(day.map { it.id }) })

        assertNull(store.read { chunk("a") })
        assertTrue(store.read { search("会議は") }.isEmpty())
        assertTrue(store.read { segmentsBetween(0, 300_000) }.isEmpty())
        assertEquals(1, store.read { search("歯医者") }.size)
        val stats = store.read { stats() }
        assertEquals(1, stats.chunks)
        assertEquals(1, stats.segments)
    }

    @Test
    fun deleteAllKeepsSettingsAndCompacts() = runTest {
        repeat(200) { i ->
            insert("c$i", i * 300_000L)
            transcribe("c$i", "長い文字起こし ".repeat(50) + i)
        }
        store.transaction { putSetting("audio_retention", "DAYS_30") }
        val before = store.sizeOnDisk()
        assertTrue(before > 0)

        assertEquals(200, store.transaction { deleteAllChunks() })
        store.compact()

        assertEquals(0, store.read { stats() }.chunks)
        assertTrue(store.read { search("文字起こし") }.isEmpty())
        assertEquals("DAYS_30", store.read { setting("audio_retention") })
        assertTrue(store.sizeOnDisk() < before, "compact should shrink the file (${store.sizeOnDisk()} >= $before)")
    }

    @Test
    fun settingsRoundTrip() = runTest {
        assertNull(store.read { setting("k") })
        store.transaction { putSetting("k", "1") }
        store.transaction { putSetting("k", "2") }
        assertEquals("2", store.read { setting("k") })
        store.transaction { putSetting("k", null) }
        assertNull(store.read { setting("k") })
    }

    @Test
    fun migrationToleratesSettingsTableFromAnotherBranch() = runTest {
        store.close()
        val path = dir.resolve("v1-with-settings.db").toString()
        // A database at version 1 that already has app_setting (e.g. created by a parallel migration).
        BundledSQLiteDriver().open(path).let { connection ->
            MIGRATIONS[0].forEach { connection.execSQL(it.trimIndent()) }
            connection.execSQL("CREATE TABLE app_setting (key TEXT PRIMARY KEY, value TEXT)")
            connection.execSQL("INSERT INTO app_setting (key, value) VALUES ('onboarded', 'true')")
            connection.execSQL("PRAGMA user_version = 1")
            connection.close()
        }
        LocalStore.open(path).use { migrated ->
            assertEquals("true", migrated.read { setting("onboarded") })
            migrated.transaction { insertChunk(manifest("a", 0), "a.m4a", 0) }
            assertNull(migrated.read { chunk("a") }!!.audioPurgedAt)
        }
    }
}
