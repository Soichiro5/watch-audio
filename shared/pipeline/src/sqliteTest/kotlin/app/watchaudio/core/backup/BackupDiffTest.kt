package app.watchaudio.core.backup

import app.watchaudio.core.backup.BackupTestData.PASSPHRASE
import app.watchaudio.core.backup.BackupTestData.at
import app.watchaudio.core.backup.BackupTestData.createdAt
import app.watchaudio.core.backup.BackupTestData.diffCreatedAt
import app.watchaudio.core.backup.BackupTestData.oct13
import app.watchaudio.core.backup.BackupTestData.tokyo
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.export.decodeExportDay
import app.watchaudio.core.export.toJson
import app.watchaudio.core.pipeline.OffloadAge
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * Diff backups (#164): a full backup and the diffs after it restore to the same data as a full
 * backup made now, in any order and any number of times, and a diff is about as large as what
 * changed. Runs on the JVM, the iOS simulator and the Android emulator (`sqliteTest`).
 */
class BackupDiffTest {
    private val dir = TempDir("backup-diff")
    private val sourceAudio = dir.resolve("source-audio")
    private val source = LocalStore.open(dir.resolve("source.db").toString())
    private val writer = BackupWriter(source, sourceAudio, iterations = 1_000)
    private val opened = mutableListOf<LocalStore>()
    private val secondDiffAt = diffCreatedAt + 86_400_000
    private val freshFullAt = secondDiffAt + 3_600_000

    @AfterTest
    fun tearDown() {
        source.close()
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    /** A backup of [kind] made at [now] and saved (as the app records it once the platform says so). */
    private suspend fun backup(now: Long, kind: BackupKind = BackupKind.FULL, includeAudio: Boolean = true, saved: Boolean = true): ByteArray {
        val plan = assertNotNull(writer.plan(includeAudio, tokyo, now, kind))
        assertEquals(kind, plan.kind)
        val out = Buffer()
        val summary = writer.write(plan, PASSPHRASE, out, chunkSize = 4096)
        if (saved) writer.recordSaved(plan, summary)
        return out.readByteArray()
    }

    private inner class Phone(name: String) {
        val audio = dir.resolve("$name-audio")
        val store = LocalStore.open(dir.resolve("$name.db").toString()).also { opened += it }
        private val restorer = BackupRestorer(store, audio, BackupTestData.clockAt(freshFullAt + 60_000))

        suspend fun restore(file: ByteArray) = restorer.restore({ Buffer().apply { write(file) } }, PASSPHRASE)

        /** Day files of the export, without when each file was backed up (that differs between files). */
        suspend fun days(): Map<String, String> = comparable(store, audio)

        suspend fun versions() = store.read { backupInfoOfChunksStartedBetween(Long.MIN_VALUE, Long.MAX_VALUE) }

        suspend fun audioOf(id: String): ByteArray? {
            val chunk = store.read { chunk(id) } ?: return null
            if (!chunk.hasAudioFile) return null
            return BackupTestData.readFile(Path(audio, chunk.audioPath!!))
        }
    }

    private suspend fun comparable(store: LocalStore, audio: Path): Map<String, String> =
        BackupTestData.exportedDays(store, audio, freshFullAt).mapValues { (_, json) ->
            val day = decodeExportDay(json)
            day.copy(chunks = day.chunks.map { it.copy(audio = it.audio?.copy(backedUpAt = null)) }).toJson()
        }

    /**
     * The source's history: [BackupTestData.seed], a full backup, then [BackupTestData.changeAfterBackup]
     * and a diff, then more changes (d0 transcribed again with the next chunk, a new chunk e0 on the
     * next day, the failed b4 retried and transcribed) and a second diff.
     */
    private suspend fun history(): List<ByteArray> {
        BackupTestData.seed(source, sourceAudio)
        val full = backup(createdAt)
        BackupTestData.changeAfterBackup(source, sourceAudio)
        val diff1 = backup(diffCreatedAt, BackupKind.DIFF)
        val d0 = at(oct13, 8)
        source.transaction {
            replaceSegments("d0", d0, Long.MAX_VALUE, listOf(NewSegment(d0 + 15_000, d0 + 17_000, "四日目の朝。言い直し", "ja")), "test-engine")
            requeueFailed(listOf("b4"))
            markTranscribing("b4")
            saveTranscript("b4", listOf(NewSegment(at(BackupTestData.oct10, 12, 5) + 1_000, at(BackupTestData.oct10, 12, 5) + 3_000, "再試行で読めた", "ja")), "test-engine")
        }
        BackupTestData.add(source, sourceAudio, "e0", at(oct13.plus(1, DateTimeUnit.DAY), 9), marks = listOf(10_000), segments = listOf(5_000L to "五日目"))
        val diff2 = backup(secondDiffAt, BackupKind.DIFF)
        return listOf(full, diff1, diff2)
    }

    private val allChunks = BackupTestData.seeded + listOf("d0", "d1", "e0")

    @Test
    fun fullAndDiffsRestoreToWhatAFreshFullBackupHolds() = runTest {
        val (full, diff1, diff2) = history()
        val fresh = backup(freshFullAt, saved = false)

        val inOrder = Phone("in-order").apply { listOf(full, diff1, diff2).forEach { restore(it) } }
        val reversed = Phone("reversed").apply { listOf(diff2, diff1, full).forEach { restore(it) } }
        val repeated = Phone("repeated").apply { listOf(diff1, full, diff2, diff1, full, diff2).forEach { restore(it) } }
        val fromFresh = Phone("fresh").apply { restore(fresh) }

        // Chunks, transcripts (the newest version of each), marks, transcription states, gaps and purged audio.
        val expected = comparable(source, sourceAudio)
        assertEquals(expected.keys, fromFresh.days().keys)
        for (phone in listOf(fromFresh, inOrder, reversed, repeated)) {
            assertEquals(expected, phone.days())
            // Receive times, manifests and record versions.
            assertEquals(source.read { backupInfoOfChunksStartedBetween(Long.MIN_VALUE, Long.MAX_VALUE) }, phone.versions())
            // Audio, byte for byte, wherever the source still has it.
            allChunks.forEach { id ->
                val expectedAudio = source.read { chunk(id) }!!.takeIf { it.hasAudioFile }?.let { BackupTestData.audioBytes(id) }
                val actual = phone.audioOf(id)
                if (expectedAudio == null) assertNull(actual, id) else assertContentEquals(expectedAudio, actual, id)
            }
        }
    }

    @Test
    fun aDiffHoldsOnlyWhatChangedAndAudioNotInASavedBackup() = runTest {
        val (_, diff1, diff2) = history()
        val first = Phone("first").apply { restore(diff1) }
        // The diff holds the changed chunks of Oct 10 and the new day, not the unchanged ones.
        assertEquals(setOf("b1", "b3", "d0", "d1"), first.store.read { chunksStartedBetween(Long.MIN_VALUE, Long.MAX_VALUE) }.map { it.id }.toSet())
        // Audio already in the full backup is not written again: b1 and b3 point at that backup.
        listOf("b1", "b3").forEach { id ->
            val chunk = assertNotNull(first.store.read { chunk(id) })
            assertTrue(chunk.isAudioOnlyInBackup, id)
            assertEquals(createdAt, chunk.audioBackedUpAt)
        }
        assertContentEquals(BackupTestData.audioBytes("d0"), first.audioOf("d0"))

        val second = Phone("second").apply { restore(diff2) }
        assertEquals(setOf("b4", "d0", "e0"), second.store.read { chunksStartedBetween(Long.MIN_VALUE, Long.MAX_VALUE) }.map { it.id }.toSet())
    }

    @Test
    fun restoringTheSameDiffAgainAddsNothing() = runTest {
        val (full, diff1, _) = history()
        val phone = Phone("phone")
        phone.restore(full)
        val first = phone.restore(diff1)
        assertEquals(BackupKind.DIFF, first.kind)
        assertEquals(2, first.chunksAdded) // d0, d1
        assertEquals(2, first.chunksAlreadyPresent) // b1, b3
        assertEquals(1, first.transcriptsUpdated) // b1 (b3 had none yet)
        val before = phone.days()
        val versions = phone.versions()

        val again = phone.restore(diff1)
        assertEquals(RestoreSummary(days = 2, chunksAdded = 0, chunksAlreadyPresent = 4, segmentsAdded = 0, marksAdded = 0, audioFilesRestored = 0, chunksWithoutAudio = 0, kind = BackupKind.DIFF), again)
        assertEquals(before, phone.days())
        assertEquals(versions, phone.versions())
    }

    @Test
    fun aDiffIsAboutAsLargeAsWhatChanged() = runTest {
        // 30 days of recordings with audio, a full backup, then one more day.
        val start = LocalDate(2026, 9, 1)
        suspend fun recordDay(date: LocalDate) = (0 until 12).forEach { i ->
            BackupTestData.add(source, sourceAudio, "$date-$i", at(date, 8, i * 5), segments = listOf(10_000L to "${date}の${i}番目の発話", 70_000L to "もう少し長い発話をここに書いておきます"))
        }
        (0 until 30).forEach { recordDay(start.plus(it, DateTimeUnit.DAY)) }
        val full = backup(at(start.plus(29, DateTimeUnit.DAY), 23))
        val newDay = start.plus(30, DateTimeUnit.DAY)
        recordDay(newDay)
        val now = at(newDay, 23)
        val preview = writer.preview(includeAudio = true)
        val diff = backup(now, BackupKind.DIFF)

        println("BackupDiffTest sizes: full=${full.size} bytes (30 days), diff=${diff.size} bytes (1 new day), preview diff=${preview.diff?.bytes} full=${preview.fullBytes}")
        assertTrue(diff.size * 15 < full.size, "diff ${diff.size} bytes, full ${full.size} bytes")
        // Each day is about the same size, so the diff is about 1/30 of the full backup (headers aside).
        assertTrue(diff.size * 40 > full.size, "diff ${diff.size} bytes, full ${full.size} bytes")
        assertEquals(DiffPreview(chunks = 12, audioFiles = 12, bytes = preview.diff!!.bytes), preview.diff)
        // The estimate is in the right range.
        assertTrue(preview.diff!!.bytes in diff.size / 3..diff.size * 3L, "estimate ${preview.diff!!.bytes}, file ${diff.size}")
        assertTrue(preview.fullBytes in full.size / 3..full.size * 3L, "estimate ${preview.fullBytes}, file ${full.size}")

        val phone = Phone("phone")
        phone.restore(full)
        val summary = phone.restore(diff)
        assertEquals(1, summary.days)
        assertEquals(12, summary.chunksAdded)
        assertEquals(comparable(source, sourceAudio), phone.days())
    }

    @Test
    fun nothingChangedMeansNoDiff() = runTest {
        BackupTestData.seed(source, sourceAudio)
        backup(createdAt)
        assertEquals(DiffPreview(0, 0, 0), writer.preview(includeAudio = true).diff)
        assertNull(writer.plan(true, tokyo, diffCreatedAt, BackupKind.DIFF))
    }

    @Test
    fun aDiffNeedsASavedBackupAndNoDeletionSinceTheFullOne() = runTest {
        BackupTestData.seed(source, sourceAudio)
        assertNull(writer.preview(includeAudio = true).diff)
        assertFailsWith<IllegalStateException> { writer.plan(true, tokyo, createdAt, BackupKind.DIFF) }
        // A backup the platform did not report saved does not count.
        backup(createdAt, saved = false)
        assertNull(writer.preview(includeAudio = true).diff)

        backup(createdAt)
        assertNotNull(writer.preview(includeAudio = true).diff)
        // The user deletes a chunk: a diff could not take it away from the full backup.
        source.transaction { deleteChunks(listOf("b2")) }
        val preview = writer.preview(includeAudio = true)
        assertTrue(preview.saved.deletedSinceFull)
        assertNull(preview.diff)
        assertFailsWith<IllegalStateException> { writer.plan(true, tokyo, diffCreatedAt, BackupKind.DIFF) }
        backup(diffCreatedAt)
        assertNotNull(writer.preview(includeAudio = true).diff)
    }

    @Test
    fun offloadedAudioIsNeitherInADiffNorMarkedByIt() = runTest {
        BackupTestData.seed(source, sourceAudio)
        backup(createdAt) // with audio, saved
        val storage = StorageManager(source, sourceAudio, clock = BackupTestData.clockAt(createdAt + 1))
        storage.offloadBackedUpAudio(OffloadAge.ALL) // a0, b0, b1 leave the phone
        BackupTestData.changeAfterBackup(source, sourceAudio)

        val plan = assertNotNull(writer.plan(true, tokyo, diffCreatedAt, BackupKind.DIFF))
        // b1 changed but its audio is only in the full backup; d0 and d1 are new.
        assertEquals(setOf("d0", "d1"), plan.export.selection!!.audio)
        val out = Buffer()
        val summary = writer.write(plan, PASSPHRASE, out)
        assertEquals(listOf("d0", "d1"), summary.audioChunkIds)
        writer.recordSaved(plan, summary)
        assertEquals(diffCreatedAt, source.read { chunk("d0") }!!.audioBackedUpAt)
        // The offloaded audio still points at the full backup; nothing else changed its record.
        assertEquals(createdAt, source.read { chunk("b1") }!!.audioBackedUpAt)
        assertTrue(source.read { chunk("b1") }!!.isAudioOnlyInBackup)
    }

    @Test
    fun aTextOnlyFullBackupIsFollowedByADiffWithTheAudio() = runTest {
        BackupTestData.seed(source, sourceAudio)
        backup(createdAt, includeAudio = false)
        // Nothing changed, but the audio on the phone is in no saved backup yet.
        val plan = assertNotNull(writer.plan(true, tokyo, diffCreatedAt, BackupKind.DIFF))
        assertEquals(setOf("a0", "b0", "b1", "b3", "b4"), plan.export.selection!!.audio)
        // Without audio there is nothing to add.
        assertNull(writer.plan(false, tokyo, diffCreatedAt, BackupKind.DIFF))
    }
}
