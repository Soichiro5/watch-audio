package app.watchaudio.android

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.watchaudio.app.Strings
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkFrame
import app.watchaudio.core.protocol.TransferPaths
import app.watchaudio.core.settings.updateSettings
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #77: the phone's diagnostics log on a real process lifecycle. Driven by
 * `.github/scripts/phone-scenarios.sh` in three steps, since the force-stop kills the process the
 * instrumentation runs in:
 *
 * 1. [receive] (`-e scenario diagnostics-receive`): one chunk of real speech arrives as a watch
 *    frame through the production [app.watchaudio.android.transfer.ChunkReceiver.store] (the part
 *    of a transfer after the Wearable channel), and the app's worker transcribes it.
 * 2. The script opens the app (`am start`) and runs `adb shell am force-stop app.watchaudio`,
 *    noting the device time around the stop.
 * 3. [verify] (`-e scenario diagnostics-verify -e stoppedFrom <ms> -e stoppedUntil <ms>`): in the
 *    next process the log holds the chunk's events, this start's `app_start` and a `previous_exit`
 *    for the force-stop (whatever reason the platform reports); the Status tab's export button
 *    writes the CSV (without transcript text) and opens the share sheet.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsScenarioTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = PlaybackFixtures.graph
    private val chunkFile get() = File(context.filesDir, "diagnostics-check-chunk.txt")

    @Test
    fun receive() {
        Evidence.assumeScenario("diagnostics-receive")
        SpeechFixtures.ensureModels()
        SpeechFixtures.awaitTranscriptionIdle()
        val chunkId = "diagnostics-check-" + System.currentTimeMillis()
        val (manifest, audio) = SpeechFixtures.chunk(chunkId, "diagnostics-check", System.currentTimeMillis() - 5 * 60_000L, SpeechFixtures.speech(WAV))
        val frame = ChunkFrame.header(manifest) + audio.readBytes()
        audio.delete()
        val ack = runBlocking { graph.chunkReceiver.store(TransferPaths.CHUNK_CHANNEL_PREFIX + chunkId, ByteArrayInputStream(frame)) }
        assertEquals(AckStatus.STORED, ack?.status)
        chunkFile.writeText(chunkId)

        // The receiver asked for a run, as on a real transfer; wait until the worker logged its outcome.
        val outcome = PlaybackFixtures.waitFor(300_000, "the transcription of $chunkId") {
            readLog().lastOrNull {
                it.chunk == chunkId && it.event in setOf(DiagnosticEvents.TRANSCRIPTION_FINISHED, DiagnosticEvents.TRANSCRIPTION_FAILED)
            }
        }
        val state = runBlocking { graph.store.read { chunk(chunkId)?.state } }
        Evidence.log("DIAGNOSTICS_CHECK phase=received chunk=$chunkId ack=${ack?.status} state=$state ${outcome.event}='${outcome.detail}'")
        assertEquals(DiagnosticEvents.TRANSCRIPTION_FINISHED, outcome.event)
        assertEquals(ChunkState.TRANSCRIBED, state)
    }

    @Test
    fun verify() {
        Evidence.assumeScenario("diagnostics-verify")
        val chunkId = chunkFile.readText().trim()
        val stoppedFrom = requireNotNull(Evidence.argument("stoppedFrom")?.toLongOrNull()) { "-e stoppedFrom <epoch ms>" }
        val stoppedUntil = requireNotNull(Evidence.argument("stoppedUntil")?.toLongOrNull()) { "-e stoppedUntil <epoch ms>" }
        val window = (stoppedFrom - 2_000)..(stoppedUntil + 2_000)
        runBlocking { graph.startup.join() }

        // WatchAudioApp.onCreate writes app_start and the earlier exits in the background.
        val exit = PlaybackFixtures.waitFor(15_000, "a previous_exit line for the force-stop") {
            readLog().lastOrNull { line -> line.event == DiagnosticEvents.PREVIOUS_EXIT && line.value("at")?.toLongOrNull()?.let { it in window } == true }
        }
        val exitAt = requireNotNull(exit.value("at")?.toLongOrNull())
        val lines = readLog()
        val found = linkedMapOf(
            DiagnosticEvents.CHUNK_RECEIVED to lines.lastOrNull { it.event == DiagnosticEvents.CHUNK_RECEIVED && it.chunk == chunkId },
            DiagnosticEvents.TRANSCRIPTION_STARTED to lines.lastOrNull { it.event == DiagnosticEvents.TRANSCRIPTION_STARTED && it.chunk == chunkId },
            DiagnosticEvents.TRANSCRIPTION_FINISHED to lines.lastOrNull { it.event == DiagnosticEvents.TRANSCRIPTION_FINISHED && it.chunk == chunkId },
            // The start after the force-stop (this process). Compared with the exit's own time, not
            // stoppedUntil: that is rounded up to the end of a second, which this start may share.
            DiagnosticEvents.APP_START to lines.lastOrNull { it.event == DiagnosticEvents.APP_START && it.time > exitAt },
            DiagnosticEvents.PREVIOUS_EXIT to exit,
        )
        // The process that was force-stopped (opened by the script) had logged its start too.
        val stoppedProcessStart = lines.lastOrNull { it.event == DiagnosticEvents.APP_START && it.time in (stoppedFrom - 60_000)..stoppedFrom }
        val rtf = found[DiagnosticEvents.TRANSCRIPTION_FINISHED]?.value("rtf")
        val description = exit.detail.substringAfter("description=", "").ifEmpty { "-" }

        // Export from the Status tab, as the user would.
        runBlocking { graph.store.updateSettings { it.copy(onboardingCompleted = true) } }
        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { deleteRecursively() }
        val device = UiDevice.getInstance(instrumentation)
        var chooser: String? = null
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Strings.TAB_STATUS)
            compose.onNodeWithText(Strings.EXPORT_DIAGNOSTICS).performScrollTo().performClick()
            chooser = runCatching {
                PlaybackFixtures.waitFor(15_000, "the share sheet") { resumedActivity()?.takeIf { "Chooser" in it || "Resolver" in it } }
            }.getOrNull()
            chooser?.let { device.wait(Until.hasObject(By.pkg(it.substringBefore('/'))), 5_000) }
            Evidence.screenshot("diagnostics-export")
            device.pressBack()
        }
        val exported = exportDir.listFiles { file -> file.name.endsWith(".csv") }?.singleOrNull()
        val content = exported?.readText().orEmpty()
        val exportedLines = parse(content)
        val exportedHasEvents = found.values.all { line -> line != null && exportedLines.any { it.raw == line.raw } }
        val transcript = SpeechFixtures.transcriptOf(graph.store, chunkId).filter { it.isNotBlank() }
        val leaked = transcript.count { it in content }

        Evidence.log(
            "DIAGNOSTICS_CHECK phase=verified chunk=$chunkId events=" +
                found.entries.joinToString(";") { (event, line) -> "$event:${if (line != null) "found" else "MISSING"}" } +
                " rtf=${rtf ?: "-"} previous_exit_reason=${exit.value("reason")} previous_exit_at=${exit.value("at")}" +
                " previous_exit_description='$description' app_start_before_stop=${stoppedProcessStart != null}" +
                " exported_file=${exported?.name ?: "-"} exported_rows=${exportedLines.size}" +
                " header=${content.startsWith("timestamp,event,detail\n")} exported_has_events=$exportedHasEvents" +
                " transcript_segments=${transcript.size} transcript_in_export=${leaked > 0} share_sheet=${chooser ?: "-"}",
        )
        found.forEach { (event, line) -> assertNotNull(line, "$event missing from the diagnostics log") }
        assertNotNull(rtf, "transcription_finished without rtf")
        assertNotNull(chooser, "the share sheet did not open")
        assertNotNull(exported, "no exported CSV in ${exportDir.path}")
        assertTrue(content.startsWith("timestamp,event,detail\n"), "exported CSV without header")
        assertTrue(exportedHasEvents, "the exported CSV lacks events of the log")
        assertTrue(transcript.isNotEmpty(), "the chunk has no transcript to compare against")
        assertFalse(leaked > 0, "transcript text in the exported CSV")
    }

    /** The log's rows, rotated part first. */
    private fun readLog(): List<Line> =
        listOf(graph.diagnostics.previousFile, graph.diagnostics.file).map { File(it.toString()) }.filter { it.isFile }.flatMap { parse(it.readText()) }

    /** Component of the resumed activity, e.g. `com.android.intentresolver/.ChooserActivity`. */
    private fun resumedActivity(): String? =
        RESUMED.find(Evidence.shell("dumpsys activity activities"))?.groupValues?.get(1)

    /** One row of the diagnostics CSV (`timestamp,event,detail`). */
    private data class Line(val raw: String, val time: Long, val event: String, val detail: String) {
        val chunk: String? get() = value("chunk")

        fun value(key: String): String? = Regex("(?:^| )$key=(\\S+)").find(detail)?.groupValues?.get(1)
    }

    private fun parse(text: String): List<Line> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("timestamp,") }
        .mapNotNull { row ->
            val parts = row.split(',', limit = 3)
            val time = runCatching { Instant.parse(parts[0]).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            Line(row, time, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        }
        .toList()

    private companion object {
        /** Reference recording with a keyword the transcript must contain (SpeechFixtures.KEYWORDS). */
        const val WAV = 1

        /** `AppGraph.DIAGNOSTICS_EXPORT_DIR` (cache subdirectory of the export). */
        const val EXPORT_DIR = "diagnostics-export"

        val RESUMED = Regex("""ResumedActivity[:=] ?ActivityRecord\{\S+ u\d+ (\S+)""")
    }
}
