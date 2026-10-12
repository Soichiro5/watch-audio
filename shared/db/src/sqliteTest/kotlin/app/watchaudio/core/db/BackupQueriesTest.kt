package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Change tracking behind diff backups (#164): what changed after a backup is found whatever wrote
 * it, versions only grow, and a deletion forces the next backup to be full.
 */
class BackupQueriesTest {
    private val dir = TempDir("backup-queries")
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

    private suspend fun version(id: String): Long? = store.read {
        val start = chunk(id)!!.startedAt
        backupInfoOfChunksStartedBetween(start, start + 1).getValue(id).updatedAt
    }

    /** A backup planned and saved at [createdAt]; @return the epoch it covers. */
    private suspend fun savedBackup(createdAt: Long, diff: Boolean = false): Long = store.transaction {
        beginBackup().also { recordBackupSaved(createdAt, diff, it) }
    }

    private suspend fun diff(after: Long, withAudio: Boolean = false) = store.read { diffChunks(after, withAudio) }.map { it.id }

    @Test
    fun everyKindOfChangeAfterABackupIsInTheDiff() = runTest {
        store.registerChunk("a", 0)
        store.registerChunk("b", 300_000)
        store.registerChunk("c", 600_000)
        store.registerChunk("d", 900_000)
        transcribe("c")
        val epoch = savedBackup(1_000)
        assertEquals(emptyList(), diff(epoch))

        transcribe("a") // state and segments
        store.transaction { addMarks("b", listOf(310_000)) } // a mark
        store.transaction { replaceSegments("c", 600_000, 900_000, listOf(NewSegment(601_000, 602_000, "言い直し", "ja")), "test") }
        store.registerChunk("e", 1_200_000) // a new chunk
        // Where the audio file is does not change the record.
        store.transaction { markAudioBackedUp(listOf("d"), 1_000) }
        store.transaction { markAudioPurged(listOf("a"), 2_000) }
        assertEquals(listOf("a", "b", "c", "e"), diff(epoch))
        // An older epoch's diff holds everything changed since; a newer backup none of it.
        assertEquals(listOf("a", "b", "c", "d", "e"), diff(0))
        assertEquals(emptyList(), diff(savedBackup(3_000, diff = true)))
    }

    @Test
    fun aChangeWhileTheBackupIsWrittenIsInTheNextDiff() = runTest {
        store.registerChunk("a", 0)
        val epoch = store.transaction { beginBackup() }
        // The backup reads the day after this change (so it holds it), but the next diff holds it too.
        transcribe("a")
        store.transaction { recordBackupSaved(1_000, diff = false, epoch = epoch) }
        assertEquals(listOf("a"), diff(store.read { savedBackups() }.lastEpoch!!))
    }

    @Test
    fun withAudioTheDiffHoldsAudioInNoSavedBackup() = runTest {
        store.registerChunk("inBackup", 0)
        store.registerChunk("notInBackup", 300_000)
        store.registerChunk("silent", 600_000, audio = false)
        store.registerChunk("purged", 900_000)
        transcribe("inBackup")
        transcribe("purged")
        store.transaction { markAudioPurged(listOf("purged"), 5) }
        val epoch = savedBackup(1_000)
        store.transaction { markAudioBackedUp(listOf("inBackup"), 1_000) }

        // Unchanged chunks whose audio is in no saved backup (and on the phone) come with their audio.
        assertEquals(listOf("notInBackup"), diff(epoch, withAudio = true))
        assertEquals(1_000L, store.read { diffChunks(epoch, true) }.single().audioBytes)
        // A changed chunk whose audio is already in a backup comes without it.
        transcribe("inBackup", "追記")
        val chunks = store.read { diffChunks(epoch, true) }.associateBy { it.id }
        assertEquals(setOf("inBackup", "notInBackup"), chunks.keys)
        assertNull(chunks.getValue("inBackup").audioBytes)
        assertEquals(1, chunks.getValue("inBackup").segments)
        assertEquals(listOf("inBackup"), diff(epoch, withAudio = false))
    }

    @Test
    fun versionsOnlyGrowAndARestoreSetsThem() = runTest {
        store.registerChunk("a", 0)
        val inserted = assertNotNull(version("a"))
        transcribe("a")
        val transcribed = assertNotNull(version("a"))
        assertTrue(transcribed > inserted)
        store.transaction { replaceSegments("a", 0, 300_000, listOf(NewSegment(1_000, 2_000, "言い直し", "ja")), "test") }
        assertTrue(version("a")!! > transcribed)

        store.transaction { setChunkVersion("a", 42) }
        assertEquals(42L, version("a"))
        store.transaction { setChunkVersion("a", null) }
        assertNull(version("a"))
        store.transaction { markFailed("a", "x") }
        assertTrue(version("a")!! > 0)
    }

    @Test
    fun aDeletionMakesTheNextBackupFull() = runTest {
        store.registerChunk("a", 0)
        store.registerChunk("b", 300_000)
        assertFalse(store.read { savedBackups() }.diffPossible) // nothing saved yet
        savedBackup(1_000)
        assertTrue(store.read { savedBackups() }.diffPossible)

        store.transaction { deleteChunks(listOf("b")) }
        val afterDelete = store.read { savedBackups() }
        assertTrue(afterDelete.deletedSinceFull)
        assertFalse(afterDelete.diffPossible)
        // A diff saved meanwhile does not help; a full backup does.
        savedBackup(2_000, diff = true)
        assertFalse(store.read { savedBackups() }.diffPossible)
        savedBackup(3_000)
        assertTrue(store.read { savedBackups() }.diffPossible)
    }

    @Test
    fun theSavedBackupsFormAChain() = runTest {
        savedBackup(1_000)
        assertEquals(SavedBackups(lastAt = 1_000, lastIsDiff = false, lastEpoch = 1, fullAt = 1_000, diffsSinceFull = 0), store.read { savedBackups() })
        savedBackup(2_000, diff = true)
        savedBackup(3_000, diff = true)
        assertEquals(SavedBackups(lastAt = 3_000, lastIsDiff = true, lastEpoch = 3, fullAt = 1_000, diffsSinceFull = 2), store.read { savedBackups() })
        // A backup planned earlier but saved later does not replace a newer one.
        store.transaction { recordBackupSaved(2_500, diff = false, epoch = 2) }
        assertEquals(3_000L, store.read { savedBackups() }.lastAt)
        savedBackup(4_000)
        assertEquals(SavedBackups(lastAt = 4_000, lastIsDiff = false, lastEpoch = 4, fullAt = 4_000, diffsSinceFull = 0), store.read { savedBackups() })
    }

    @Test
    fun aVersion7DatabaseStartsWithoutABackupToDiffFrom() = runTest {
        store.close()
        val path = dir.resolve("v7.db").toString()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            MIGRATIONS.take(7).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 7")
            db.execSQL(
                """
                INSERT INTO audio_chunk (uuid, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, codec,
                    size_bytes, sha256, end_reason, state, attempts, last_error, received_at, manifest_json, audio_backed_up_at)
                VALUES ('old', 's1', 'watch', 'WEAROS', 0, 300000, 'UTC', '1970/01/01/old.m4a', 'AAC',
                    1000, 'aa', 'ROTATION', 'RECEIVED', 0, NULL, 10, '{}', 5)
                """.trimIndent(),
            )
        }
        LocalStore.open(path).use { migrated ->
            assertEquals(SavedBackups(), migrated.read { savedBackups() })
            assertNull(migrated.read { backupInfoOfChunksStartedBetween(0, 1) }.getValue("old").updatedAt)
            // The chunks from before are in the first backup after the upgrade (a full one), not in a diff after it.
            val epoch = migrated.transaction { beginBackup().also { recordBackupSaved(1_000, false, it) } }
            assertTrue(migrated.read { diffChunks(epoch, true) }.isEmpty())
            migrated.transaction {
                markTranscribing("old")
                saveTranscript("old", listOf(NewSegment(1_000, 2_000, "移行後", "ja")), "test")
            }
            assertEquals(listOf("old"), migrated.read { diffChunks(epoch, false) }.map { it.id })
            assertNotNull(migrated.read { backupInfoOfChunksStartedBetween(0, 1) }.getValue("old").updatedAt)
        }
    }
}
