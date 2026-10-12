package app.watchaudio.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.watchaudio.app.state.NowProvider
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.PauseReason
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StatusNotice
import app.watchaudio.core.settings.loadSettings
import app.watchaudio.core.settings.updateSettings
import app.watchaudio.core.timeline.dayWindow
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Renders the whole app headlessly over a seeded database and walks through the three tabs.
 * Screenshots land in `shared/ui/build/screenshots/` for a quick visual check.
 */
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    private val dbFile = File.createTempFile("watch-audio-app", ".db").also { it.delete() }
    private val store = LocalStore.open(dbFile.absolutePath)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val day = LocalDate(2026, 10, 10)
    private val now = dayWindow(day, tokyo).start + (15 * 60 + 40) * 60_000L
    private val audioDir = Files.createTempDirectory("watch-audio-app-audio").toFile()
    private val services = FakeAppServices(
        store,
        audioRoot = audioDir.path,
        initialStatus = ProcessingStatus(asrModel = ModelState.Downloading(0.42f)),
    )

    private val owners = object : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @AfterTest
    fun tearDown() {
        owners.viewModelStore.clear()
        store.close()
        dbFile.delete()
        audioDir.deleteRecursively()
    }

    @Test
    fun walkThroughTabs() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        showApp(services)

        // Timeline: coverage, the charging gap and the transcript.
        waitForText("2026年10月10日(土)")
        waitForText("録音カバー率")
        waitForText("充電中")
        waitForText("おはようございます、今日もよろしくお願いします")
        screenshot("timeline")

        onAllNodes(hasText("おはようございます、今日もよろしくお願いします"))[0].performClick()
        waitForText("07:00 から再生中")
        assertEquals("c0.m4a" to 20_000L, services.played.last())

        // Search, then open a hit on its day.
        onNodeWithText(Strings.TAB_SEARCH).performClick()
        onNode(hasSetTextAction()).performTextInput("会議は")
        waitForText("明日の", substring = true)
        screenshot("search")
        onAllNodes(hasText("明日の", substring = true))[0].performClick()
        waitForText("2026年10月10日(土)")

        // Status: model download progress, stats and the privacy note.
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText("ダウンロード中… 42%")
        waitForText(Strings.PRIVACY_NOTE)
        screenshot("status")

        // Alert settings: turning quiet hours off is stored and reported to the platform.
        onNodeWithText(Strings.QUIET_HOURS_SWITCH).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { services.gapAlertSettingsChanges == 1 }
        assertEquals(false, runBlocking { store.loadSettings() }.gapAlert.quietHours.enabled)

        // Transcription timing (#75): the charging-only switch is stored and reported to the platform.
        onNodeWithText(Strings.CHARGING_ONLY_SWITCH).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { services.transcriptionSettingsChanges == 1 }
        assertEquals(true, runBlocking { store.loadSettings() }.transcribeOnlyWhileCharging)

        // Storage: breakdown, retention and the typed confirmation for deleting everything.
        waitForText(Strings.STORAGE_USAGE)
        waitForText(Strings.RETENTION)
        onNodeWithText(Strings.DELETE_ALL).performScrollTo().performClick()
        waitForText(Strings.DELETE_ALL_NOTE)
        onNode(hasSetTextAction() and hasText(Strings.DELETE_ALL_INPUT)).performScrollTo().performTextInput(Strings.DELETE_ALL_WORD)
        screenshot("storage")
        onNodeWithText(Strings.DELETE_ALL_CONFIRM).performScrollTo().performClick()
        waitForText(Strings.DELETED_ALL)
        assertEquals(0, runBlocking { store.read { stats() } }.chunks)

        // Diagnostics log (#77): exported only through the button.
        assertEquals(0, services.diagnosticsExports)
        services.diagnosticsExportResult = false
        onNodeWithText(Strings.EXPORT_DIAGNOSTICS).performScrollTo().performClick()
        waitForText(Strings.DIAGNOSTICS_EMPTY)
        assertEquals(1, services.diagnosticsExports)
        screenshot("diagnostics")
    }

    @Test
    fun onboardingThenMainScreen() = runDesktopComposeUiTest(width = 412, height = 915) {
        val services = FakeAppServices(
            store,
            initialStatus = ProcessingStatus(asrModel = ModelState.Missing),
            permissions = listOf(AppPermission.NOTIFICATIONS),
        )
        showApp(services)

        waitForText(Strings.WELCOME_TITLE)
        waitForText(Strings.PRIVACY_NOTE)
        screenshot("onboarding-welcome")
        onNodeWithText(Strings.ONBOARDING_NEXT).performClick()

        // Consent is required before going on.
        waitForText(Strings.CONSENT_TITLE)
        onNodeWithText(Strings.ONBOARDING_NEXT).assertIsNotEnabled()
        onNodeWithText(Strings.CONSENT_CHECKBOX).performScrollTo().performClick()
        onNodeWithText(Strings.ONBOARDING_NEXT).assertIsEnabled()
        screenshot("onboarding-consent")
        onNodeWithText(Strings.ONBOARDING_NEXT).performClick()

        waitForText(Strings.PERMISSIONS_TITLE)
        onNodeWithText(Strings.PERMISSION_ALLOW).performClick()
        waitForText(Strings.PERMISSION_GRANTED)
        assertEquals(listOf(AppPermission.NOTIFICATIONS), services.permissionRequests)
        onNodeWithText(Strings.ONBOARDING_NEXT).performClick()

        waitForText(Strings.MODEL_SETUP_TITLE)
        onNodeWithText(Strings.DOWNLOAD_ANY).performClick()
        assertEquals(listOf(true), services.downloadRequests)
        screenshot("onboarding-model")
        onNodeWithText(Strings.ONBOARDING_SKIP).performClick()

        waitForText(Strings.WATCH_INSTRUCTION)
        onNodeWithText(Strings.ONBOARDING_BACK).performClick()
        waitForText(Strings.MODEL_SETUP_TITLE)
        onNodeWithText(Strings.ONBOARDING_SKIP).performClick()
        onNodeWithText(Strings.ONBOARDING_START).performClick()

        waitForText(Strings.TAB_STATUS)
        assertTrue(runBlocking { store.loadSettings() }.onboardingCompleted)

        // The guide can be opened again from the status tab; it starts over.
        onNodeWithText(Strings.TAB_STATUS).performClick()
        onNodeWithText(Strings.SHOW_ONBOARDING_AGAIN).performScrollTo().performClick()
        waitForText(Strings.WELCOME_TITLE)
    }

    /** #32: a refused permission explains itself and leads to the system settings. */
    @Test
    fun refusedPermissionOffersTheSystemSettings() = runDesktopComposeUiTest(width = 412, height = 915) {
        val services = FakeAppServices(
            store,
            initialStatus = ProcessingStatus(asrModel = ModelState.Missing),
            permissions = listOf(AppPermission.NOTIFICATIONS),
        )
        services.deniesOnRequest = true
        showApp(services)

        waitForText(Strings.WELCOME_TITLE)
        onNodeWithText(Strings.ONBOARDING_NEXT).performClick()
        waitForText(Strings.CONSENT_TITLE)
        onNodeWithText(Strings.CONSENT_CHECKBOX).performScrollTo().performClick()
        onNodeWithText(Strings.ONBOARDING_NEXT).performClick()

        waitForText(Strings.PERMISSIONS_TITLE)
        onNodeWithText(Strings.PERMISSION_ALLOW).performClick()
        waitForText(Strings.PERMISSION_DENIED)
        waitForText(Strings.OPEN_SETTINGS)
        screenshot("onboarding-permission-denied")

        onNodeWithText(Strings.OPEN_SETTINGS).performClick()
        assertEquals(1, services.appSettingsOpened)
        // Refusing does not block the rest of the guide.
        onNodeWithText(Strings.ONBOARDING_SKIP).performClick()
        waitForText(Strings.MODEL_SETUP_TITLE)
    }

    /** #135: the status tab's notes and failures are Japanese, and nothing on the tab is worded in English. */
    @Test
    fun statusNoticesAreJapanese() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        val services = FakeAppServices(
            store,
            audioRoot = audioDir.path,
            initialStatus = ProcessingStatus(asrModel = ModelState.Missing, notice = StatusNotice.ModelWaitingForWifi),
        )
        showApp(services)
        // The tabs appear once the settings are loaded; clicking before that finds no node.
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.statusNotice(StatusNotice.ModelWaitingForWifi))
        assertNoEnglish()
        screenshot("status-waiting-for-wifi")

        services.mutableStatus.value = ProcessingStatus(
            asrModel = ModelState.Ready,
            notice = StatusNotice.TranscriptionFailed(2),
            paused = PauseReason.LOW_BATTERY,
        )
        waitForText(Strings.statusNotice(StatusNotice.TranscriptionFailed(2)))
        assertNoEnglish()
        screenshot("status-notice")

        // A failed download shows a fixed text, never the platform's (English) reason.
        services.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Failed("HTTP 404 for https://example.invalid/model.onnx"))
        waitForText(Strings.modelFailed(ModelSetup.DOWNLOAD))
        assertTrue(onAllNodes(hasText("HTTP 404", substring = true)).fetchSemanticsNodes().isEmpty())
        assertNoEnglish()
        screenshot("status-model-failed")
    }

    /** #155: the export card; the archive only goes where the platform's save dialog says. */
    @Test
    fun exportCardWritesTheChosenRange() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
            val window = dayWindow(day, tokyo)
            store.read { chunksBetween(window.start, window.end) }.mapNotNull { it.audioPath }.forEach {
                File(audioDir, it).writeBytes(ByteArray(1_000))
            }
        }
        showApp(services)
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        onNodeWithText(Strings.EXPORT_RANGE_DATES).performScrollTo().performClick()
        waitForText(Strings.exportFrom("2026年10月4日(日)"))
        waitForText(Strings.exportTo("2026年10月10日(土)"))
        onNodeWithText(Strings.EXPORT_INCLUDE_AUDIO).performScrollTo().performClick()
        onNodeWithText(Strings.EXPORT_BUTTON).performScrollTo()
        assertNoEnglish()
        screenshot("export")
        assertTrue(services.exportRequests.isEmpty()) // nothing leaves before the tap

        onNodeWithText(Strings.EXPORT_BUTTON).performClick()
        waitForText("1日分を書き出しました", substring = true)
        val (name, bytes) = services.savedExports.single()
        assertEquals("watch-audio-2026-10-10.zip", name)
        assertTrue(bytes.size > 1_000)
        onNode(hasText("1日分を書き出しました", substring = true)).performScrollTo()
        assertNoEnglish()
        screenshot("export-done")
    }

    /** #40: the backup card; the file only goes where the platform's save dialog says, and comes back the same way. */
    @Test
    fun backupCardCreatesAndRestoresABackup() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        showApp(services)
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE)).performScrollTo().performTextInput("とけいの記録 2026 秋")
        waitForText(Strings.PASSPHRASE_STRONG)
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE_CONFIRM)).performScrollTo().performTextInput("とけいの記録 2026")
        waitForText(Strings.BACKUP_PASSPHRASE_MISMATCH)
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE_CONFIRM)).performTextInput(" 秋")
        onNodeWithText(Strings.BACKUP_CREATE_BUTTON).performScrollTo()
        assertTrue(onAllNodes(hasText(Strings.BACKUP_PASSPHRASE_MISMATCH)).fetchSemanticsNodes().isEmpty())
        assertNoEnglish()
        screenshot("backup")
        assertTrue(services.exportRequests.isEmpty()) // nothing leaves before the tap

        onNodeWithText(Strings.BACKUP_CREATE_BUTTON).performClick()
        waitForText("バックアップしました", substring = true)
        val (name, bytes) = services.savedExports.single()
        assertEquals("watch-audio-backup-2026-10-10.wabackup", name)
        onNode(hasText("バックアップしました", substring = true)).performScrollTo()
        assertNoEnglish()
        screenshot("backup-done")

        // Restoring onto the same phone adds nothing twice.
        services.backupToOpen = bytes
        onNode(hasSetTextAction() and hasText(Strings.RESTORE_PASSPHRASE)).performScrollTo().performTextInput("とけいの記録 2026 秋")
        onNodeWithText(Strings.RESTORE_BUTTON).performScrollTo().performClick()
        waitForText("すでにこの端末にあった", substring = true)
        onNode(hasText("すでにこの端末にあった", substring = true)).performScrollTo()
        assertNoEnglish()
        screenshot("restore-done")
    }

    /**
     * #156: audio in a saved backup leaves the phone after a confirmation; the timeline says the
     * audio is in the backup instead of offering playback, and search still finds the transcript.
     */
    @Test
    fun offloadedAudioKeepsTranscriptsAndSearch() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
            val window = dayWindow(day, tokyo)
            store.read { chunksBetween(window.start, window.end) }.mapNotNull { it.audioPath }.forEach {
                File(audioDir, it).apply { parentFile.mkdirs() }.writeBytes(ByteArray(1_000))
            }
        }
        showApp(services)
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.OFFLOAD_NO_BACKUP)

        // A backup with audio, saved where the platform's dialog says.
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE)).performScrollTo().performTextInput("とけいの記録 2026 秋")
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE_CONFIRM)).performScrollTo().performTextInput("とけいの記録 2026 秋")
        onNodeWithText(Strings.BACKUP_INCLUDE_AUDIO).performScrollTo().performClick()
        onNodeWithText(Strings.BACKUP_CREATE_BUTTON).performScrollTo().performClick()
        waitForText("バックアップしました", substring = true)
        waitForText(Strings.OFFLOAD_BUTTON)
        val transcribed = runBlocking { store.read { stats() }.offloadableAudio.chunks }
        assertTrue(transcribed > 0)

        // Offloading: a confirmation names the backup and what goes, then the audio leaves the phone.
        onNodeWithText(Strings.OFFLOAD_BUTTON).performScrollTo().performClick()
        waitForText("2026年10月10日(土) 作成", substring = true)
        onNodeWithText(Strings.OFFLOAD_CONFIRM).performScrollTo()
        onNodeWithText(Strings.OFFLOAD_TITLE).performScrollTo()
        assertNoEnglish()
        screenshot("offload-confirm")
        onNodeWithText(Strings.OFFLOAD_CONFIRM).performClick()
        waitForText("端末から外しました", substring = true)
        onNode(hasText("端末から外しました", substring = true)).performScrollTo()
        assertNoEnglish()
        screenshot("offload-done")
        val stats = runBlocking { store.read { stats() } }
        assertEquals(transcribed, stats.audioInBackupChunks)
        assertEquals(0, stats.offloadableAudio.chunks)

        // Timeline: the transcript stays, marked as in the backup, and tapping it plays nothing.
        onNodeWithText(Strings.TAB_TIMELINE).performClick()
        waitForText("おはようございます、今日もよろしくお願いします")
        waitForText(Strings.AUDIO_IN_BACKUP)
        waitForText("「バックアップにあり」の音声は", substring = true)
        assertNoEnglish()
        screenshot("timeline-offloaded")
        onAllNodes(hasText("おはようございます、今日もよろしくお願いします"))[0].performClick()
        waitForIdle()
        assertTrue(services.played.isEmpty())

        // Search still finds the offloaded transcript.
        onNodeWithText(Strings.TAB_SEARCH).performClick()
        onNode(hasSetTextAction()).performTextInput("会議は")
        waitForText("明日の", substring = true)
        screenshot("search-offloaded")
    }

    /** #144: a failed chunk shows a fixed Japanese note, never the exception message stored in the DB. */
    @Test
    fun timelineFailureShowsNoExceptionMessage() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        val failed = runBlocking { store.read { chunksBetween(dayWindow(day, tokyo).start, now) } }
            .filter { it.state == ChunkState.FAILED }
        assertTrue(failed.isNotEmpty() && failed.all { it.lastError == DemoData.DEMO_FAILURE })
        showApp(services)

        val row = hasText("文字起こし失敗", substring = true)
        waitForText("2026年10月10日(土)")
        onNode(hasScrollToNodeAction()).performScrollToNode(row)
        waitForText(Strings.FAILED_CHUNKS_NOTE)
        assertTrue(onAllNodes(hasText("IOException", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("MediaCodec", substring = true)).fetchSemanticsNodes().isEmpty())
        assertNoEnglish()
        screenshot("timeline-failed")
    }

    /** Fails on any text on screen (scrolled away or not) with an English word and no Japanese. */
    private fun ComposeUiTest.assertNoEnglish() {
        val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertTrue(texts.isNotEmpty())
        val english = texts.filter { text ->
            ENGLISH_WORD.containsMatchIn(text) && text.none { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }
        }
        assertTrue(english.isEmpty(), "English text on screen: $english")
    }

    private fun ComposeUiTest.showApp(services: AppServices) {
        setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owners,
                LocalLifecycleOwner provides owners,
            ) {
                App(services, tokyo, NowProvider { now })
            }
        }
    }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private companion object {
        /** Three or more ASCII letters: a word, not a unit such as MB. */
        val ENGLISH_WORD = Regex("[A-Za-z]{3,}")
    }
}
