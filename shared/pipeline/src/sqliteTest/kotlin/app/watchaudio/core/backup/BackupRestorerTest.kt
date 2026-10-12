package app.watchaudio.core.backup

import app.watchaudio.core.backup.BackupTestData.PASSPHRASE
import app.watchaudio.core.backup.BackupTestData.at
import app.watchaudio.core.backup.BackupTestData.createdAt
import app.watchaudio.core.backup.BackupTestData.oct10
import app.watchaudio.core.backup.BackupTestData.tokyo
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Backups (#40) written from one database and restored into another, as when moving to a new
 * phone: nothing is lost, failures change nothing, and restoring is idempotent.
 */
class BackupRestorerTest {
    private val dir = TempDir("backup")
    private val sourceAudio = dir.resolve("source-audio")
    private val source = LocalStore.open(dir.resolve("source.db").toString())
    private val targetAudio = dir.resolve("target-audio")
    private val target = LocalStore.open(dir.resolve("target.db").toString())
    private val restoredAt = createdAt + 86_400_000
    private val restorer = BackupRestorer(target, targetAudio, BackupTestData.clockAt(restoredAt))
    private val progress = mutableListOf<RestoreProgress>()

    @AfterTest
    fun tearDown() {
        source.close()
        target.close()
        dir.deleteRecursively()
    }

    private suspend fun backup(includeAudio: Boolean, chunkSize: Int = 1024): ByteArray {
        BackupTestData.seed(source, sourceAudio)
        val writer = BackupWriter(source, sourceAudio, iterations = 1_000)
        val plan = assertNotNull(writer.plan(includeAudio, tokyo, createdAt))
        val out = Buffer()
        writer.write(plan, PASSPHRASE, out, chunkSize = chunkSize)
        return out.readByteArray()
    }

    private suspend fun restore(file: ByteArray, passphrase: String = PASSPHRASE) =
        restorer.restore({ Buffer().apply { write(file) } }, passphrase) { progress += it }

    private suspend fun targetIsEmpty() {
        assertEquals(0, target.read { stats() }.chunks)
        assertTrue(target.read { segmentsBetween(Long.MIN_VALUE, Long.MAX_VALUE) }.isEmpty())
        assertTrue(!SystemFileSystem.exists(targetAudio) || SystemFileSystem.list(targetAudio).isEmpty(), "no audio files left")
    }

    @Test
    fun withAudioEverythingComesBack() = runTest {
        val summary = restore(backup(includeAudio = true))

        assertEquals(RestoreSummary(days = 3, chunksAdded = 7, chunksAlreadyPresent = 0, segmentsAdded = 6, marksAdded = 3, audioFilesRestored = 5, chunksWithoutAudio = 1), summary)
        assertEquals(RestoreProgress.Verifying, progress.first())
        assertEquals(RestoreProgress.Applying(3, 3), progress.last())
        // Chunks, transcripts (with their engines), marks, transcription states, purged audio
        // and the gaps of each day, as the export of each store describes them.
        assertEquals(BackupTestData.exportedDays(source, sourceAudio), BackupTestData.exportedDays(target, targetAudio))
        // The audio is where the ingest would put it, byte for byte.
        listOf("a0", "b0", "b1", "b3", "b4").forEach { id ->
            val chunk = assertNotNull(target.read { chunk(id) })
            assertEquals(BackupTestData.archivePathOf(id, chunk.startedAt), chunk.audioPath)
            assertContentEquals(BackupTestData.audioBytes(id), BackupTestData.readFile(Path(targetAudio, chunk.audioPath!!)))
            assertEquals(source.read { backupInfoOfChunksStartedBetween(chunk.startedAt, chunk.startedAt + 1) }, target.read { backupInfoOfChunksStartedBetween(chunk.startedAt, chunk.startedAt + 1) })
        }
        assertEquals(source.read { lastReceivedAt() }, target.read { lastReceivedAt() })
        // The pending chunk is transcribed on the new phone; the failed one only on request.
        assertEquals(ChunkState.RECEIVED, target.read { chunk("b3") }?.state)
        assertEquals(ChunkState.FAILED, target.read { chunk("b4") }?.state)
        assertTrue(target.read { pendingChunks(10, 3) }.map { it.id } == listOf("b3"))
        assertEquals(at(BackupTestData.oct12, 9), target.read { chunk("c0") }?.audioPurgedAt)
        assertTrue(!SystemFileSystem.exists(Path(targetAudio, BackupRestorer.STAGING_DIR)), "staging folder removed")
    }

    @Test
    fun textOnlyKeepsTranscriptsMarksAndGaps() = runTest {
        val summary = restore(backup(includeAudio = false))

        assertEquals(0, summary.audioFilesRestored)
        assertEquals(6, summary.chunksWithoutAudio) // a0, b0, b1, b3, b4, and c0 (purged before the backup)
        val before = BackupTestData.exportedDays(source, sourceAudio).mapValues { app.watchaudio.core.export.decodeExportDay(it.value) }
        val after = BackupTestData.exportedDays(target, targetAudio).mapValues { app.watchaudio.core.export.decodeExportDay(it.value) }
        assertEquals(before.keys, after.keys)
        before.forEach { (name, day) ->
            val restored = after.getValue(name)
            assertEquals(day.segments, restored.segments)
            assertEquals(day.marks, restored.marks)
            assertEquals(day.gaps, restored.gaps)
            assertEquals(day.chunks.map { it.copy(audio = null, transcription = app.watchaudio.core.export.ExportTranscription.DONE) }, restored.chunks.map { it.copy(audio = null, transcription = app.watchaudio.core.export.ExportTranscription.DONE) })
        }
        // No audio on this device: nothing to play, and the pending chunk cannot be transcribed.
        val b3 = assertNotNull(target.read { chunk("b3") })
        assertEquals(restoredAt, b3.audioPurgedAt)
        assertEquals(ChunkState.FAILED, b3.state)
        assertEquals(BackupRestorer.ERROR_AUDIO_NOT_RESTORED, b3.lastError)
        assertTrue(target.read { pendingChunks(10, 3) }.isEmpty())
        assertEquals(0L, target.read { stats() }.audioBytes)
    }

    @Test
    fun wrongPassphraseChangesNothing() = runTest {
        val file = backup(includeAudio = true)
        assertFailsWith<BackupException.WrongPassphrase> { restore(file, "とけいの記録 2026 backuP") }
        targetIsEmpty()
        assertEquals(listOf<RestoreProgress>(RestoreProgress.Verifying), progress)
    }

    @Test
    fun damageNearTheEndChangesNothing() = runTest {
        val file = backup(includeAudio = true)
        // The first days come first in the file: they must not be added before the end is checked.
        val damaged = file.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 0x40).toByte() }
        assertFailsWith<BackupException.Corrupted> { restore(damaged) }
        targetIsEmpty()
        assertFailsWith<BackupException.Corrupted> { restore(file.copyOf(file.size * 2 / 3)) }
        targetIsEmpty()
        assertFailsWith<BackupException.Corrupted> { restore(file.copyOf(file.size - 1)) }
        targetIsEmpty()
        assertFailsWith<BackupException.NotABackup> { restore(ByteArray(0)) }
        targetIsEmpty()
    }

    @Test
    fun restoringAgainAddsNothing() = runTest {
        val file = backup(includeAudio = true)
        restore(file)
        val first = BackupTestData.exportedDays(target, targetAudio)

        val again = restore(file)

        assertEquals(RestoreSummary(days = 3, chunksAdded = 0, chunksAlreadyPresent = 7, segmentsAdded = 0, marksAdded = 0, audioFilesRestored = 0, chunksWithoutAudio = 0), again)
        assertEquals(first, BackupTestData.exportedDays(target, targetAudio))
    }

    @Test
    fun storedChunksAreKeptAndOnlyGainWhatTheyLack() = runTest {
        val file = backup(includeAudio = false)
        // The new phone already has b0 with its own (newer) transcript and no marks, and b1
        // still waiting for transcription.
        target.transaction {
            insertChunk(testManifest("b0", at(oct10, 7), audio = null, timeZone = "Asia/Tokyo"), "local/b0.m4a", receivedAt = 1)
            markTranscribing("b0")
            saveTranscript("b0", listOf(NewSegment(at(oct10, 7) + 1_000, at(oct10, 7) + 3_000, "新しいモデルの文字起こし", "ja")), "new-engine")
            insertChunk(testManifest("b1", at(oct10, 7, 5), audio = null, timeZone = "Asia/Tokyo"), "local/b1.m4a", receivedAt = 2)
        }

        val summary = restore(file)

        assertEquals(5, summary.chunksAdded)
        assertEquals(2, summary.chunksAlreadyPresent)
        val b0 = target.read { segmentsBetween(at(oct10, 7), at(oct10, 7, 5)) }.filter { it.chunkId == "b0" }
        assertEquals(listOf("新しいモデルの文字起こし"), b0.map { it.text })
        assertEquals("local/b0.m4a", target.read { chunk("b0") }?.audioPath)
        assertNull(target.read { chunk("b0") }?.audioPurgedAt)
        assertEquals(listOf(at(oct10, 7, 2), at(oct10, 7, 3)), target.read { marksOfChunksStartedBetween(at(oct10, 7), at(oct10, 7, 1)) }.map { it.at })
        // b1 had no transcript yet: it takes the backup's.
        assertEquals(ChunkState.TRANSCRIBED, target.read { chunk("b1") }?.state)
        assertEquals(listOf("了解です"), target.read { segmentsBetween(at(oct10, 7, 5), at(oct10, 7, 10)) }.filter { it.chunkId == "b1" }.map { it.text })
        assertEquals(2L, target.read { chunk("b1") }?.let { target.read { backupInfoOfChunksStartedBetween(it.startedAt, it.startedAt + 1) }["b1"]?.receivedAt })
    }
}
