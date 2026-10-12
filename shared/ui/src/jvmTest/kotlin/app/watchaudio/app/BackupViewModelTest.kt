package app.watchaudio.app

import app.watchaudio.app.logic.PassphraseStrength
import app.watchaudio.app.state.BackupViewModel
import app.watchaudio.app.state.NowProvider
import app.watchaudio.core.backup.BackupKind
import app.watchaudio.core.backup.BackupRestorer
import app.watchaudio.core.backup.DiffPreview
import app.watchaudio.core.backup.BackupWriter
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.timeline.dayWindow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

/** 「暗号化バックアップ」 (#40): create on one phone, restore on another, only on the user's taps. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val window = dayWindow(LocalDate(2026, 10, 10), tokyo)
    private val now = window.start + 15 * 3_600_000L
    private val dirs = mutableListOf<File>()
    private val stores = mutableListOf<LocalStore>()
    private val old = phone()
    private val new = phone()

    @AfterTest
    fun tearDown() {
        stores.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun phone(): FakeAppServices {
        val dir = Files.createTempDirectory("watch-audio-backup").toFile().also { dirs += it }
        val store = LocalStore.open(File(dir, "watch-audio.db").path, dispatcher = dispatcher).also { stores += it }
        val audio = Path(File(dir, "audio").path)
        return FakeAppServices(
            store,
            audio.toString(),
            backupWriter = BackupWriter(store, audio, dispatcher, iterations = 1_000),
            backupRestorer = BackupRestorer(store, audio, dispatcher = dispatcher),
        )
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.viewModel(services: FakeAppServices) = BackupViewModel(services, tokyo, NowProvider { now }, scope = backgroundScope)

    private suspend fun addChunk(services: FakeAppServices, id: String, start: Long, text: String) {
        val bytes = ByteArray(64) { it.toByte() }
        val relative = "2026/10/10/$id.m4a"
        File(services.audioRoot, relative).apply { parentFile.mkdirs() }.writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WATCHOS,
            startedAtEpochMs = start,
            durationMs = 300_000,
            timeZone = "Asia/Tokyo",
            audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 64, sha),
            markOffsetsMs = listOf(30_000),
            endReason = ChunkEndReason.ROTATION,
        )
        services.store.transaction {
            insertChunk(manifest, relative, start + 300_000)
            markTranscribing(id)
            saveTranscript(id, listOf(NewSegment(start + 10_000, start + 12_000, text, "ja")), "test")
        }
    }

    private suspend fun TestScope.createBackup(passphrase: String = PASS, includeAudio: Boolean = true): ByteArray {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        addChunk(old, "b", window.start + 9 * 3_600_000L + 300_000, "会議の資料を送ります")
        val vm = viewModel(old)
        vm.setPassphrase(passphrase)
        vm.setConfirmation(passphrase)
        vm.setIncludeAudio(includeAudio)
        vm.create()
        runCurrent()
        return old.savedExports.single().second
    }

    @Test
    fun aBackupNeedsALongEnoughConfirmedPassphrase() = runVmTest {
        val vm = viewModel(old)
        assertFalse(vm.state.value.canCreate)
        vm.setPassphrase("short")
        assertEquals(PassphraseStrength.TOO_SHORT, vm.state.value.strength)
        vm.setConfirmation("short")
        assertFalse(vm.state.value.canCreate)
        vm.setPassphrase(PASS)
        assertTrue(vm.state.value.confirmationMismatch)
        assertFalse(vm.state.value.canCreate)
        vm.setConfirmation(PASS)
        assertTrue(vm.state.value.canCreate)
        assertFalse(vm.state.value.canRestore)
        runCurrent()
        assertTrue(old.exportRequests.isEmpty()) // never on its own
    }

    @Test
    fun aBackupMadeOnOnePhoneRestoresOnAnother() = runVmTest {
        val file = createBackup()

        val created = viewModel(old).state.value // a fresh card: nothing kept
        assertEquals("", created.passphrase)
        assertEquals(listOf("watch-audio-backup-2026-10-10.wabackup"), old.exportRequests)
        assertTrue(old.diagnosticsLines.single().startsWith("backup_created kind=full days=1 chunks=2 audio_files=2"))

        new.backupToOpen = file
        val vm = viewModel(new)
        vm.setRestorePassphrase(PASS)
        vm.restore()
        assertTrue(vm.state.value.restoring)
        runCurrent()

        val state = vm.state.value
        assertFalse(state.restoring)
        assertEquals(Strings.restored(added = 2, segments = 2, audioFiles = 2, alreadyPresent = 0), state.restoreMessage)
        assertEquals("", state.restorePassphrase)
        assertEquals(listOf("おはようございます", "会議の資料を送ります"), new.store.read { segmentsBetween(Long.MIN_VALUE, Long.MAX_VALUE) }.map { it.text })
        assertEquals(listOf("backup_restored days=1 added=2 present=0 audio_files=2 without_audio=0 audio_returned=0 updated=0 kind=full"), new.diagnosticsLines)
        // Nothing in the logs reveals the passphrase or the content.
        (old.diagnosticsLines + new.diagnosticsLines).forEach { line ->
            assertFalse(PASS in line || "会議" in line, line)
        }
    }

    @Test
    fun aWrongPassphraseSaysSoAndChangesNothing() = runVmTest {
        new.backupToOpen = createBackup()
        val vm = viewModel(new)
        vm.setRestorePassphrase("not the passphrase")
        vm.restore()
        runCurrent()

        assertEquals(Strings.RESTORE_WRONG_PASSPHRASE, vm.state.value.restoreMessage)
        assertEquals("not the passphrase", vm.state.value.restorePassphrase) // to correct it
        assertEquals(0, new.store.read { stats() }.chunks)
        assertEquals(
            listOf("backup_restore_failed phase=verify error=app.watchaudio.core.backup.BackupException.WrongPassphrase"),
            new.diagnosticsLines,
        )
    }

    @Test
    fun aDamagedFileSaysSoAndChangesNothing() = runVmTest {
        val file = createBackup()
        new.backupToOpen = file.copyOf(file.size - 100)
        val vm = viewModel(new)
        vm.setRestorePassphrase(PASS)
        vm.restore()
        runCurrent()

        assertEquals(Strings.RESTORE_CORRUPTED, vm.state.value.restoreMessage)
        assertEquals(0, new.store.read { stats() }.chunks)
    }

    @Test
    fun cancellingThePickerShowsNothing() = runVmTest {
        val vm = viewModel(new)
        vm.setRestorePassphrase(PASS)
        vm.restore()
        runCurrent()

        assertEquals(1, new.backupOpenRequests)
        assertNull(vm.state.value.restoreMessage)
        assertFalse(vm.state.value.restoring)
    }

    @Test
    fun aFailedSaveShowsAFixedTextAndKeepsThePassphrase() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        old.exportDestinationFailure = IOException("ENOSPC (No space left on device)")
        val vm = viewModel(old)
        vm.setPassphrase(PASS)
        vm.setConfirmation(PASS)
        vm.create()
        runCurrent()

        assertEquals(Strings.BACKUP_FAILED, vm.state.value.createMessage)
        assertEquals(PASS, vm.state.value.passphrase)
        assertEquals(listOf("backup_failed error=java.io.IOException"), old.diagnosticsLines)
    }

    @Test
    fun aSavedBackupWithAudioMarksTheAudioAsBackedUp() = runVmTest {
        createBackup()
        // #156: the audio of both chunks is in the saved file, so it may now be offloaded.
        assertEquals(listOf(now, now), listOf("a", "b").map { old.store.read { chunk(it) }?.audioBackedUpAt })
        assertEquals(2, old.store.read { stats() }.offloadableAudio.chunks)
    }

    @Test
    fun aBackupWithoutAudioMarksNothing() = runVmTest {
        createBackup(includeAudio = false)
        assertEquals(0, old.store.read { stats() }.offloadableAudio.chunks)
        assertNull(old.store.read { chunk("a") }?.audioBackedUpAt)
    }

    @Test
    fun aBackupThatWasNotSavedMarksNothing() = runVmTest {
        // iOS: the file was written, then the share sheet was closed without a destination.
        old.exportDismissedAfterWrite = true
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        val vm = viewModel(old)
        vm.setPassphrase(PASS)
        vm.setConfirmation(PASS)
        vm.setIncludeAudio(true)
        vm.create()
        runCurrent()

        assertNull(vm.state.value.createMessage)
        assertEquals(PASS, vm.state.value.passphrase)
        assertNull(old.store.read { chunk("a") }?.audioBackedUpAt)
        assertEquals(0, old.store.read { stats() }.offloadableAudio.chunks)
        assertTrue(old.diagnosticsLines.isEmpty())
    }

    @Test
    fun aFailedSaveMarksNothing() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        old.exportDestinationFailure = IOException("ENOSPC (No space left on device)")
        val vm = viewModel(old)
        vm.setPassphrase(PASS)
        vm.setConfirmation(PASS)
        vm.setIncludeAudio(true)
        vm.create()
        runCurrent()

        assertEquals(Strings.BACKUP_FAILED, vm.state.value.createMessage)
        assertNull(old.store.read { chunk("a") }?.audioBackedUpAt)
    }

    @Test
    fun nothingToBackUpSaysSo() = runVmTest {
        val vm = viewModel(old)
        vm.setPassphrase(PASS)
        vm.setConfirmation(PASS)
        vm.create()
        runCurrent()

        assertEquals(Strings.BACKUP_NOTHING, vm.state.value.createMessage)
        assertTrue(old.exportRequests.isEmpty())
    }

    /** Creates a backup on [services] with the card as the user left it; @return the saved file. */
    private fun TestScope.create(vm: BackupViewModel, services: FakeAppServices): ByteArray {
        val before = services.savedExports.size
        vm.setPassphrase(PASS)
        vm.setConfirmation(PASS)
        vm.create()
        runCurrent()
        assertEquals(before + 1, services.savedExports.size, vm.state.value.createMessage)
        return services.savedExports.last().second
    }

    @Test
    fun aDiffIsOfferedOnceAFullBackupWasSaved() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        val vm = viewModel(old)
        vm.setIncludeAudio(true)
        runCurrent()
        val first = vm.state.value
        assertNull(first.preview!!.saved.lastAt)
        assertFalse(first.diffPossible)
        assertEquals(BackupKind.FULL, first.kind)
        assertEquals(1, first.preview!!.fullChunks)
        vm.setKind(BackupKind.DIFF) // not possible yet: still a full backup
        assertEquals(BackupKind.FULL, vm.state.value.kind)

        create(vm, old)
        val saved = vm.state.value
        assertEquals(now, saved.preview!!.saved.lastAt)
        assertTrue(saved.diffPossible)
        assertEquals(DiffPreview(0, 0, 0), saved.preview!!.diff)

        // A new recording: the diff would hold it and its audio.
        addChunk(old, "b", window.start + 10 * 3_600_000L, "会議の資料を送ります")
        runCurrent()
        assertEquals(1, vm.state.value.preview!!.diff!!.chunks)
        assertEquals(1, vm.state.value.preview!!.diff!!.audioFiles)
        vm.setIncludeAudio(false)
        runCurrent()
        assertEquals(0, vm.state.value.preview!!.diff!!.audioFiles)
    }

    @Test
    fun aDiffAfterTheFullBackupRestoresOnTopOfIt() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        val vm = viewModel(old)
        vm.setIncludeAudio(true)
        runCurrent()
        val full = create(vm, old)
        addChunk(old, "b", window.start + 10 * 3_600_000L, "会議の資料を送ります")
        runCurrent()
        assertEquals(BackupKind.DIFF, vm.state.value.kind) // offered first once possible
        val diff = create(vm, old)

        assertEquals(listOf("watch-audio-backup-2026-10-10.wabackup", "watch-audio-backup-2026-10-10-diff.wabackup"), old.exportRequests)
        assertEquals(Strings.backedUpDiff(days = 1, chunks = 1, audioFiles = 1), vm.state.value.createMessage)
        assertEquals("backup_created kind=diff days=1 chunks=1 audio_files=1 missing_audio=0", old.diagnosticsLines.last())
        assertEquals(1, vm.state.value.preview!!.saved.diffsSinceFull)

        // Restore, diff first: one flow takes either kind.
        val restoring = viewModel(new)
        for (file in listOf(diff, full)) {
            new.backupToOpen = file
            restoring.setRestorePassphrase(PASS)
            restoring.restore()
            runCurrent()
        }
        assertEquals(listOf("おはようございます", "会議の資料を送ります"), new.store.read { segmentsBetween(Long.MIN_VALUE, Long.MAX_VALUE) }.map { it.text })
        assertTrue(new.diagnosticsLines.first().endsWith("kind=diff"), new.diagnosticsLines.first())
        assertTrue(listOf("a", "b").all { new.store.read { chunk(it) }!!.hasAudioFile })
    }

    @Test
    fun theUserCanStillPickAFullBackup() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        val vm = viewModel(old)
        runCurrent()
        create(vm, old)
        addChunk(old, "b", window.start + 10 * 3_600_000L, "会議の資料を送ります")
        vm.setKind(BackupKind.FULL)
        runCurrent()
        assertEquals(BackupKind.FULL, vm.state.value.kind)
        create(vm, old)
        assertEquals("watch-audio-backup-2026-10-10.wabackup", old.exportRequests.last())
        assertEquals(Strings.backedUp(days = 1, chunks = 2, audioFiles = 0), vm.state.value.createMessage)
    }

    @Test
    fun nothingChangedSinceTheLastBackupSaysSo() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        val vm = viewModel(old)
        runCurrent()
        create(vm, old)
        vm.setPassphrase(PASS)
        vm.setConfirmation(PASS)
        vm.create()
        runCurrent()
        assertEquals(Strings.BACKUP_NOTHING_CHANGED, vm.state.value.createMessage)
        assertEquals(1, old.exportRequests.size)
    }

    @Test
    fun aDeletionMakesTheNextBackupFull() = runVmTest {
        addChunk(old, "a", window.start + 9 * 3_600_000L, "おはようございます")
        addChunk(old, "b", window.start + 10 * 3_600_000L, "会議の資料を送ります")
        val vm = viewModel(old)
        runCurrent()
        create(vm, old)
        assertTrue(vm.state.value.diffPossible)
        old.store.transaction { deleteChunks(listOf("b")) }
        runCurrent()
        assertFalse(vm.state.value.diffPossible)
        assertTrue(vm.state.value.preview!!.saved.deletedSinceFull)
        assertEquals(BackupKind.FULL, vm.state.value.kind)
    }

    private companion object {
        const val PASS = "とけいの記録 backup 2026"
    }
}
