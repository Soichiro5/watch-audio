package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Queries behind offloading backed-up audio (#156): only audio that was written into a saved
 * backup is ever offered or marked as offloaded, and the transcript stays searchable.
 */
class OffloadQueriesTest {
    private val dir = TempDir("offload-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun transcribe(id: String, text: String = "会議の資料を送ります") = store.transaction {
        val start = chunk(id)!!.startedAt
        markTranscribing(id)
        saveTranscript(id, listOf(NewSegment(start + 1_000, start + 2_000, text, "ja")), "test")
    }

    @Test
    fun onlyBackedUpTranscribedAudioIsOffloadable() = runTest {
        store.registerChunk("backedUp", 0)
        store.registerChunk("notBackedUp", 300_000)
        store.registerChunk("backedUpPending", 600_000)
        store.registerChunk("silent", 900_000, audio = false)
        store.registerChunk("recent", 10_000_000)
        transcribe("backedUp")
        transcribe("notBackedUp", "歯医者の予約")
        transcribe("recent", "最近の話")

        // The backup held every chunk with audio except notBackedUp; silent chunks are skipped.
        assertEquals(3, store.transaction { markAudioBackedUp(listOf("backedUp", "backedUpPending", "silent", "recent", "missing"), 5_000) })
        assertNull(store.read { chunk("silent") }!!.audioBackedUpAt)
        assertNull(store.read { chunk("notBackedUp") }!!.audioBackedUpAt)

        assertEquals(listOf("backedUp", "recent"), store.read { offloadableChunks(Long.MAX_VALUE, 10) }.map { it.id })
        assertEquals(listOf("backedUp"), store.read { offloadableChunks(5_000_000, 10) }.map { it.id })
        assertEquals(AudioUsage(chunks = 2, bytes = 2_000), store.read { offloadableUsage(Long.MAX_VALUE) })
        assertEquals(5_000L to 5_000L, store.read { offloadableBackupTimes(Long.MAX_VALUE) })
        assertNull(store.read { offloadableBackupTimes(-1) })

        // Marking refuses audio that is not in a backup or not transcribed, even when asked directly.
        assertEquals(1, store.transaction { markAudioOffloaded(listOf("backedUp", "notBackedUp", "backedUpPending", "silent"), 42) })
        val offloaded = store.read { chunk("backedUp") }!!
        assertEquals(42, offloaded.audioPurgedAt)
        assertFalse(offloaded.hasAudioFile)
        assertTrue(offloaded.isAudioOnlyInBackup)
        assertTrue(store.read { chunk("notBackedUp") }!!.hasAudioFile)
        assertTrue(store.read { chunk("backedUpPending") }!!.hasAudioFile)
        assertEquals(listOf("recent"), store.read { offloadableChunks(Long.MAX_VALUE, 10) }.map { it.id })

        // The transcript stays searchable; the stats tell offloaded audio from purged audio.
        assertEquals(listOf("backedUp"), store.read { search("会議の資料") }.map { it.segment.chunkId })
        val stats = store.read { stats() }
        assertEquals(0, stats.audioPurgedChunks)
        assertEquals(1, stats.audioInBackupChunks)
        assertEquals(AudioUsage(chunks = 1, bytes = 1_000), stats.offloadableAudio)
        assertEquals(5_000L, stats.lastAudioBackupAt)
        assertEquals(3_000, stats.audioBytes)
        assertEquals(setOf("notBackedUp.m4a", "backedUpPending.m4a", "recent.m4a"), store.read { audioPaths() })
    }

    @Test
    fun aChunkKeepsItsNewestBackupTime() = runTest {
        store.registerChunk("a", 0)
        store.transaction { markAudioBackedUp(listOf("a"), 2_000) }
        store.transaction { markAudioBackedUp(listOf("a"), 1_000) }
        assertEquals(2_000L, store.read { chunk("a") }!!.audioBackedUpAt)
        store.transaction { markAudioBackedUp(listOf("a"), 3_000) }
        assertEquals(3_000L, store.read { chunk("a") }!!.audioBackedUpAt)
    }

    @Test
    fun retentionPurgedAudioThatWasBackedUpCountsAsInTheBackup() = runTest {
        store.registerChunk("a", 0)
        store.registerChunk("b", 300_000)
        transcribe("a")
        transcribe("b")
        store.transaction { markAudioBackedUp(listOf("a"), 7_000) }
        store.transaction { markAudioPurged(listOf("a", "b"), 9_000) }
        assertTrue(store.read { chunk("a") }!!.isAudioOnlyInBackup)
        assertFalse(store.read { chunk("b") }!!.isAudioOnlyInBackup)
        val stats = store.read { stats() }
        assertEquals(1, stats.audioPurgedChunks)
        assertEquals(1, stats.audioInBackupChunks)
    }

    @Test
    fun restoringAudioClearsTheOffload() = runTest {
        store.registerChunk("a", 0)
        store.registerChunk("b", 300_000)
        transcribe("a")
        store.transaction { markAudioBackedUp(listOf("a"), 1_000) }
        store.transaction { markAudioOffloaded(listOf("a"), 2_000) }

        assertTrue(store.transaction { markAudioRestored("a", 3_000) })
        val restored = store.read { chunk("a") }!!
        assertTrue(restored.hasAudioFile)
        assertEquals(3_000L, restored.audioBackedUpAt)
        // Only a chunk that was missing its audio is marked.
        assertFalse(store.transaction { markAudioRestored("a", 4_000) })
        assertFalse(store.transaction { markAudioRestored("b", 4_000) })
        assertNull(store.read { chunk("b") }!!.audioBackedUpAt)
    }

    @Test
    fun aVersion6DatabaseGainsTheBackupColumn() = runTest {
        store.close()
        val path = dir.resolve("v6.db").toString()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            MIGRATIONS.take(6).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 6")
            db.execSQL(
                """
                INSERT INTO audio_chunk (uuid, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, codec,
                    size_bytes, sha256, end_reason, state, attempts, last_error, received_at, manifest_json, audio_purged_at)
                VALUES
                    ('purged', 's1', 'watch', 'WEAROS', 0, 300000, 'UTC', '1970/01/01/purged.m4a', 'AAC',
                        1000, 'aa', 'ROTATION', 'TRANSCRIBED', 1, NULL, 10, '{}', 99),
                    ('kept', 's1', 'watch', 'WEAROS', 300000, 600000, 'UTC', '1970/01/01/kept.m4a', 'AAC',
                        2000, 'bb', 'ROTATION', 'TRANSCRIBED', 1, NULL, 20, '{}', NULL)
                """.trimIndent(),
            )
        }
        LocalStore.open(path).use { migrated ->
            val purged = migrated.read { chunk("purged") }!!
            assertNull(purged.audioBackedUpAt)
            assertFalse(purged.isAudioOnlyInBackup)
            // Nothing was backed up before the migration, so nothing can be offloaded.
            assertTrue(migrated.read { offloadableChunks(Long.MAX_VALUE, 10) }.isEmpty())
            val stats = migrated.read { stats() }
            assertEquals(1, stats.audioPurgedChunks)
            assertEquals(0, stats.audioInBackupChunks)
            assertNull(stats.lastAudioBackupAt)
            assertEquals(1, migrated.transaction { markAudioBackedUp(listOf("kept"), 5) })
            assertEquals(listOf("kept"), migrated.read { offloadableChunks(Long.MAX_VALUE, 10) }.map { it.id })
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.prepare("PRAGMA user_version").use { st ->
                st.step()
                assertEquals(MIGRATIONS.size, st.getInt(0))
            }
        }
    }
}
