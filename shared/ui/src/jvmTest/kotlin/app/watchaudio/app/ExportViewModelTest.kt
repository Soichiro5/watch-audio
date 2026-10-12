package app.watchaudio.app

import app.watchaudio.app.state.ExportRangeMode
import app.watchaudio.app.state.ExportViewModel
import app.watchaudio.app.state.NowProvider
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.export.ExportRange
import app.watchaudio.core.export.decodeExportDay
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.timeline.dayWindow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream
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

/** 「データの書き出し」 (#155): only on the user's tap, through the platform's save dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dbFile = File.createTempFile("watch-audio-export", ".db").also { it.delete() }
    private val store = LocalStore.open(dbFile.absolutePath, dispatcher = dispatcher)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val day = LocalDate(2026, 10, 10)
    private val window = dayWindow(day, tokyo)
    private val now = at(15)
    private val audioDir = Files.createTempDirectory("watch-audio-export-audio").toFile()
    private val services = FakeAppServices(
        store,
        audioDir.path,
        exporter = DataExporter(store, Path(audioDir.path), dispatcher),
    )

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
        audioDir.deleteRecursively()
    }

    private fun at(hour: Int, minute: Int = 0) = window.start + hour * 3_600_000L + minute * 60_000L

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.viewModel() = ExportViewModel(services, tokyo, NowProvider { now }, scope = backgroundScope)

    private suspend fun addChunk(id: String, start: Long, text: String, audio: Boolean = true) {
        if (audio) File(audioDir, "2026/10/10/$id.m4a").apply { parentFile.mkdirs() }.writeBytes(ByteArray(64) { it.toByte() })
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = start,
            durationMs = 300_000,
            timeZone = "Asia/Tokyo",
            audio = if (audio) AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 64, "00") else null,
            markOffsetsMs = listOf(30_000),
            endReason = ChunkEndReason.ROTATION,
        )
        store.transaction {
            insertChunk(manifest, if (audio) "2026/10/10/$id.m4a" else null, start + 300_000)
            markTranscribing(id)
            saveTranscript(id, listOf(NewSegment(start + 10_000, start + 12_000, text, "ja")), "test")
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes() }
    }

    @Test
    fun choicesStartWithEverythingAndTheLastWeekAndKeepTheRangeInOrder() = runVmTest {
        val vm = viewModel()
        val initial = vm.state.value
        assertEquals(ExportRangeMode.ALL, initial.mode)
        assertEquals(ExportRange.ALL, initial.range)
        assertEquals(LocalDate(2026, 10, 4), initial.from)
        assertEquals(day, initial.to)
        assertFalse(initial.includeAudio)

        vm.setMode(ExportRangeMode.DATES)
        vm.setFrom(LocalDate(2026, 10, 12)) // after today: today, and the end follows
        assertEquals(ExportRange(day, day), vm.state.value.range)
        vm.setTo(LocalDate(2026, 9, 30)) // before the start: the start follows
        assertEquals(ExportRange(LocalDate(2026, 9, 30), LocalDate(2026, 9, 30)), vm.state.value.range)
        vm.setIncludeAudio(true)
        assertTrue(vm.state.value.includeAudio)
        runCurrent()
        assertTrue(services.exportRequests.isEmpty()) // never on its own
    }

    @Test
    fun exportsTheChosenRangeToWhereTheUserSaves() = runVmTest {
        addChunk("a", at(9), "おはようございます")
        addChunk("b", at(9, 5), "会議の資料を送ります")
        val vm = viewModel()
        vm.setIncludeAudio(true)

        vm.export()
        assertTrue(vm.state.value.busy)
        runCurrent()

        val state = vm.state.value
        assertFalse(state.busy)
        assertEquals(Strings.exported(days = 1, segments = 2, audioFiles = 2), state.message)
        val (name, bytes) = services.savedExports.single()
        assertEquals("watch-audio-2026-10-10.zip", name)
        val files = unzip(bytes)
        assertEquals(64, files.getValue("watch-audio-2026-10-10/audio/2026-10-10/090000_a.m4a").size)
        val exported = decodeExportDay(files.getValue("watch-audio-2026-10-10/json/2026-10-10.json").decodeToString())
        assertEquals(listOf("おはようございます", "会議の資料を送ります"), exported.segments.map { it.text })
        assertEquals(2, exported.marks.size)
        assertTrue(files.getValue("watch-audio-2026-10-10/markdown/2026-10-10.md").decodeToString().contains("会議の資料を送ります"))
        // Counts only in the diagnostics log, never content.
        assertEquals(listOf("data_exported days=1 chunks=2 audio_files=2 missing_audio=0"), services.diagnosticsLines)
    }

    @Test
    fun anEmptyRangeSaysSoWithoutAskingWhereToSave() = runVmTest {
        addChunk("a", at(9), "おはようございます")
        val vm = viewModel()
        vm.setMode(ExportRangeMode.DATES)
        vm.setFrom(LocalDate(2026, 10, 1))
        vm.setTo(LocalDate(2026, 10, 3))

        vm.export()
        runCurrent()

        assertEquals(Strings.EXPORT_NOTHING, vm.state.value.message)
        assertTrue(services.exportRequests.isEmpty())
    }

    @Test
    fun cancellingTheSaveDialogShowsNothing() = runVmTest {
        addChunk("a", at(9), "おはようございます")
        services.exportCancelled = true
        val vm = viewModel()

        vm.export()
        runCurrent()

        assertEquals(listOf("watch-audio-2026-10-10.zip"), services.exportRequests)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
        assertTrue(services.savedExports.isEmpty())
    }

    @Test
    fun aFailureShowsAFixedTextAndLogsOnlyTheErrorType() = runVmTest {
        addChunk("a", at(9), "おはようございます")
        services.exportDestinationFailure = IOException("ENOSPC (No space left on device): /storage/emulated/0/Download")
        val vm = viewModel()

        vm.export()
        runCurrent()

        // A fixed Japanese text, not the exception message (#135); the log gets the type only.
        assertEquals(Strings.EXPORT_FAILED, vm.state.value.message)
        assertEquals(listOf("data_export_failed error=java.io.IOException"), services.diagnosticsLines)
        assertFalse(vm.state.value.busy)
    }
}
