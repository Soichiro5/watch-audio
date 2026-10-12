package app.watchaudio.core.backup

import app.watchaudio.core.backup.BackupTestData.PASSPHRASE
import app.watchaudio.core.backup.BackupTestData.createdAt
import app.watchaudio.core.backup.BackupTestData.tokyo
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.pipeline.OffloadAge
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Offloading backed-up audio (#156) and getting it back from the backup: only audio of a saved
 * backup leaves the phone, transcripts and search stay, and restoring the backup returns the
 * files to the chunks that are still on the phone.
 */
class OffloadRestoreTest {
    private val dir = TempDir("offload")
    private val audioRoot = dir.resolve("audio")
    private val store = LocalStore.open(dir.resolve("phone.db").toString())
    private val offloadedAt = createdAt + 3_600_000
    private val storage = StorageManager(store, audioRoot, clock = BackupTestData.clockAt(offloadedAt))
    private val writer = BackupWriter(store, audioRoot, iterations = 1_000)
    private val restorer = BackupRestorer(store, audioRoot, BackupTestData.clockAt(offloadedAt + 3_600_000))

    /** Transcribed chunks with audio in [BackupTestData]; b3 (pending) and b4 (failed) are not. */
    private val transcribedWithAudio = listOf("a0", "b0", "b1")

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun backup(includeAudio: Boolean, saved: Boolean = true): ByteArray {
        val plan: BackupPlan = assertNotNull(writer.plan(includeAudio, tokyo, createdAt))
        val out = Buffer()
        val summary = writer.write(plan, PASSPHRASE, out, chunkSize = 1024)
        if (saved) writer.recordSaved(plan, summary)
        return out.readByteArray()
    }

    private suspend fun restore(file: ByteArray) = restorer.restore({ Buffer().apply { write(file) } }, PASSPHRASE)

    private suspend fun audioPathOf(id: String) = Path(audioRoot, assertNotNull(store.read { chunk(id) }?.audioPath))

    @Test
    fun withoutASavedBackupNothingIsOffloaded() = runTest {
        BackupTestData.seed(store, audioRoot)
        backup(includeAudio = true, saved = false) // e.g. the user cancelled the save
        backup(includeAudio = false) // a saved backup without audio

        assertEquals(0, storage.previewOffload(OffloadAge.ALL).usage.chunks)
        assertEquals(StorageManager.PurgeResult(0, 0), storage.offloadBackedUpAudio(OffloadAge.ALL))
        listOf("a0", "b0", "b1", "b3", "b4").forEach { id ->
            assertTrue(SystemFileSystem.exists(audioPathOf(id)), "$id keeps its audio")
            assertNull(store.read { chunk(id) }?.audioBackedUpAt)
        }
    }

    @Test
    fun onlyBackedUpAudioIsOffloadedAndTranscriptsStay() = runTest {
        BackupTestData.seed(store, audioRoot)
        backup(includeAudio = true)
        // A chunk that arrived and was transcribed after the backup is not in it.
        val late = BackupTestData.at(BackupTestData.oct12, 21)
        val latePath = Path(audioRoot, BackupTestData.archivePathOf("late", late))
        SystemFileSystem.createDirectories(latePath.parent!!)
        SystemFileSystem.sink(latePath).buffered().use { it.write(BackupTestData.audioBytes("late")) }
        store.transaction {
            insertChunk(testManifest("late", late), BackupTestData.archivePathOf("late", late), receivedAt = late)
            markTranscribing("late")
            saveTranscript("late", listOf(NewSegment(late + 1_000, late + 2_000, "あとの発話", "ja")), "test-engine")
        }
        val searchBefore = store.read { search("おはよう") }

        val preview = storage.previewOffload(OffloadAge.ALL)
        assertEquals(3, preview.usage.chunks)
        assertEquals(createdAt, preview.oldestBackupAt)
        assertEquals(createdAt, preview.newestBackupAt)
        val result = storage.offloadBackedUpAudio(OffloadAge.ALL)

        assertEquals(3, result.purgedChunks)
        transcribedWithAudio.forEach { id ->
            assertFalse(SystemFileSystem.exists(audioPathOf(id)), "$id offloaded")
            val chunk = assertNotNull(store.read { chunk(id) })
            assertTrue(chunk.isAudioOnlyInBackup)
            assertEquals(offloadedAt, chunk.audioPurgedAt)
            assertEquals(createdAt, chunk.audioBackedUpAt)
        }
        // Audio that is not in a backup stays, and so does untranscribed audio even though it is.
        assertTrue(SystemFileSystem.exists(latePath))
        assertTrue(assertNotNull(store.read { chunk("late") }).hasAudioFile)
        listOf("b3", "b4").forEach { id -> assertTrue(SystemFileSystem.exists(audioPathOf(id)), "$id keeps its audio") }
        // Transcripts and search are unchanged.
        assertEquals(searchBefore, store.read { search("おはよう") })
        assertEquals(1, store.read { search("了解") }.size)
        assertEquals(3, store.read { stats() }.audioInBackupChunks)
    }

    @Test
    fun restoringTheBackupBringsOffloadedAudioBack() = runTest {
        BackupTestData.seed(store, audioRoot)
        val file = backup(includeAudio = true)
        storage.offloadBackedUpAudio(OffloadAge.ALL)

        val summary = restore(file)

        assertEquals(0, summary.chunksAdded)
        assertEquals(7, summary.chunksAlreadyPresent)
        assertEquals(3, summary.audioFilesReturned)
        assertEquals(0, summary.segmentsAdded)
        transcribedWithAudio.forEach { id ->
            val chunk = assertNotNull(store.read { chunk(id) })
            assertTrue(chunk.hasAudioFile, "$id has its audio again")
            assertEquals(createdAt, chunk.audioBackedUpAt)
            assertContentEquals(BackupTestData.audioBytes(id), BackupTestData.readFile(Path(audioRoot, chunk.audioPath!!)))
        }
        // c0 was deleted by retention before the backup, so the backup cannot bring it back.
        assertNotNull(store.read { chunk("c0") }?.audioPurgedAt)
        assertEquals(0, store.read { stats() }.audioInBackupChunks)
        // Restoring again changes nothing.
        assertEquals(0, restore(file).audioFilesReturned)
    }

    @Test
    fun aNewPhoneKnowsWhichBackupHoldsOffloadedAudio() = runTest {
        BackupTestData.seed(store, audioRoot)
        val withAudio = backup(includeAudio = true)
        storage.offloadBackedUpAudio(OffloadAge.ALL)
        val textOnly = backup(includeAudio = false)

        LocalStore.open(dir.resolve("new.db").toString()).use { phone ->
            val newAudio = dir.resolve("new-audio")
            val newRestorer = BackupRestorer(phone, newAudio, BackupTestData.clockAt(offloadedAt + 7_200_000))
            newRestorer.restore({ Buffer().apply { write(textOnly) } }, PASSPHRASE)
            transcribedWithAudio.forEach { id ->
                val chunk = assertNotNull(phone.read { chunk(id) })
                assertTrue(chunk.isAudioOnlyInBackup, "$id is shown as in the backup")
                assertEquals(createdAt, chunk.audioBackedUpAt)
            }
            assertEquals(1, phone.read { search("了解") }.size)

            // The backup with audio then fills the audio in, also of b3 and b4, which the text-only
            // backup still had on the old phone but could not carry.
            val summary = newRestorer.restore({ Buffer().apply { write(withAudio) } }, PASSPHRASE)
            assertEquals(5, summary.audioFilesReturned)
            transcribedWithAudio.forEach { id -> assertTrue(assertNotNull(phone.read { chunk(id) }).hasAudioFile) }
        }
    }

    @Test
    fun theAgeLimitKeepsRecentAudio() = runTest {
        BackupTestData.seed(store, audioRoot)
        backup(includeAudio = true)
        // a0 started on Oct 9, b0/b1 on Oct 10; offloading happens on Oct 12, so 3 days is the line.
        val olderThanAWeek = StorageManager(store, audioRoot, clock = BackupTestData.clockAt(BackupTestData.at(BackupTestData.oct9, 22) + 7 * 86_400_000L + 600_000))

        assertEquals(1, olderThanAWeek.previewOffload(OffloadAge.DAYS_7).usage.chunks)
        assertEquals(1, olderThanAWeek.offloadBackedUpAudio(OffloadAge.DAYS_7).purgedChunks)
        assertFalse(SystemFileSystem.exists(audioPathOf("a0")))
        assertTrue(SystemFileSystem.exists(audioPathOf("b0")))
        assertTrue(SystemFileSystem.exists(audioPathOf("b1")))
    }
}
