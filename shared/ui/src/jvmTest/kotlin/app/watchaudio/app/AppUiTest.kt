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
import app.watchaudio.app.licenses.NoticeApp
import app.watchaudio.app.licenses.OpenSourceNotices
import app.watchaudio.app.state.NowProvider
import app.watchaudio.core.agent.AgentLimits
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.encodeBase64Url
import app.watchaudio.core.db.AgentAccessEntry
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.HighlightRecord
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.NewHourSummary
import app.watchaudio.core.db.hourSourceHash
import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.SummaryPrompts
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.search.VectorHit
import app.watchaudio.core.search.SemanticStatus
import app.watchaudio.core.db.NewPassage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer

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

    /**
     * #81: 「英語も文字起こしする」 is offered where the platform supports English, is stored and
     * reported, and the English model's state (and the mobile-data download) shows below it.
     */
    @Test
    fun englishSwitchShowsTheEnglishModel() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true) } }
        val services = FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), supportsEnglish = true)
        showApp(services)
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        // #167: the FunASR model license asks for the model name, author and source next to it.
        waitForText(ENGLISH_ATTRIBUTION)
        onNodeWithText(Strings.ENGLISH_SWITCH).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { services.transcriptionSettingsChanges == 1 }
        assertEquals(true, runBlocking { store.loadSettings() }.transcribeEnglish)

        services.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Ready, englishModel = ModelState.Missing, englishModelWaitingForWifi = true)
        waitForText(Strings.englishModelState(ModelState.Missing, waitingForWifi = true))
        onNodeWithText(Strings.ENGLISH_DOWNLOAD_NOW).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { services.englishDownloadRequests == listOf(true) }

        services.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Ready, englishModel = ModelState.Downloading(0.3f))
        waitForText(Strings.englishModelState(ModelState.Downloading(0.3f), waitingForWifi = false))
        assertNoEnglish()
        screenshot("status-english-downloading")

        onNodeWithText(Strings.ENGLISH_SWITCH).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { services.transcriptionSettingsChanges == 2 }
        assertEquals(false, runBlocking { store.loadSettings() }.transcribeEnglish)
    }

    /**
     * #170: where the OS installs the English model (iOS), the switch states no download size or
     * attribution, preparing shows without a percentage, and a failed installation can be retried.
     */
    @Test
    fun englishSwitchWithAnOsManagedModel() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true, transcribeEnglish = true) } }
        val services = FakeAppServices(
            store,
            audioRoot = audioDir.path,
            initialStatus = ProcessingStatus(asrModel = ModelState.Ready, englishModel = ModelState.Downloading(0f)),
            modelSetup = ModelSetup.OS_MANAGED,
            supportsEnglish = true,
        )
        showApp(services)
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.englishModelState(ModelState.Downloading(0f), waitingForWifi = false, ModelSetup.OS_MANAGED))
        waitForText(Strings.ENGLISH_DETAIL_OS_MANAGED)
        assertTrue(onAllNodes(hasText(ENGLISH_ATTRIBUTION)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText(Strings.ENGLISH_DOWNLOAD_NOW)).fetchSemanticsNodes().isEmpty())
        assertNoEnglish()
        screenshot("status-english-os-managed-preparing")

        // The OS's (English) reason is not shown.
        services.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Ready, englishModel = ModelState.Failed("Asset installation failed"))
        waitForText(Strings.englishModelState(ModelState.Failed(""), waitingForWifi = false, ModelSetup.OS_MANAGED))
        onNodeWithText(Strings.ENGLISH_PREPARE_AGAIN).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { services.englishDownloadRequests.size == 1 }
        assertTrue(onAllNodes(hasText("Asset installation", substring = true)).fetchSemanticsNodes().isEmpty())
        assertNoEnglish()
    }

    @Test
    fun englishSwitchIsHiddenWithoutEnglishSupport() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true) } }
        showApp(FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready)))
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.CHARGING_ONLY_SWITCH)
        assertTrue(onAllNodes(hasText(Strings.ENGLISH_SWITCH)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText(ENGLISH_ATTRIBUTION)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * #167: 「ライセンス」 opens from the bottom of the status tab and lists the models and libraries
     * (the Android set: the phone app and the Wear OS recorder) with author, license and URL; a
     * license's full text opens on demand, and 「戻る」 returns to the status tab.
     */
    @Test
    fun licensesOpenFromTheStatusTab() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true) } }
        val apps = setOf(NoticeApp.ANDROID, NoticeApp.WEAR_OS)
        showApp(FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), noticeApps = apps))
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        onNodeWithText(Strings.SHOW_LICENSES).performScrollTo().performClick()

        waitForText(Strings.LICENSES_INTRO)
        for (notice in OpenSourceNotices.forApps(apps)) {
            onNode(hasText(notice.name)).performScrollTo()
            onAllNodes(hasText(Strings.licenseUrl(notice.url)))[0].performScrollTo()
        }
        onNode(hasText(Strings.licenseAuthor(OpenSourceNotices.SENSE_VOICE.author))).performScrollTo()
        onNode(hasText(Strings.FUNASR_TERMS_SUMMARY)).performScrollTo()
        assertTrue(onAllNodes(hasText("Skia")).fetchSemanticsNodes().isEmpty(), "iOS-only components are not listed on Android")
        onNodeWithText(Strings.LICENSES_INTRO).performScrollTo()
        screenshot("licenses")

        // Full texts are collapsed until asked for.
        assertTrue(onAllNodes(hasText("TERMS AND CONDITIONS", substring = true)).fetchSemanticsNodes().isEmpty())
        onAllNodes(hasText(Strings.SHOW_LICENSE_TEXT))[0].performScrollTo().performClick()
        waitForText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
        onNode(hasText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)).performScrollTo()
        screenshot("licenses-text")

        onNodeWithText(Strings.LICENSES_BACK).performClick()
        waitForText(Strings.SHOW_LICENSES)
        assertTrue(onAllNodes(hasText(Strings.LICENSES_INTRO)).fetchSemanticsNodes().isEmpty())
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
     * #164: once a full backup was saved, the card says when and offers a diff of what changed
     * since, with its size; the diff is saved like any backup.
     */
    @Test
    fun backupCardOffersADiffAfterAFullBackup() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
            // A full backup saved an hour ago; since then one more chunk was transcribed.
            val writer = services.backupWriter
            val plan = writer.plan(includeAudio = false, timeZone = tokyo, now = now - 3_600_000L)!!
            writer.recordSaved(plan, writer.write(plan, "とけいの記録 2026 秋", Buffer()))
            val pending = store.read { pendingChunks(1, 3) }.first()
            store.transaction {
                markTranscribing(pending.id)
                saveTranscript(pending.id, listOf(NewSegment(pending.startedAt + 1_000, pending.startedAt + 3_000, "あとで届いた発話", "ja")), "demo")
            }
        }
        showApp(services)
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.lastBackupFull("2026年10月10日(土) 14:40"))
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE)).performScrollTo().performTextInput("とけいの記録 2026 秋")
        onNode(hasSetTextAction() and hasText(Strings.BACKUP_PASSPHRASE_CONFIRM)).performScrollTo().performTextInput("とけいの記録 2026 秋")
        waitForText("前回のバックアップの後に増えた・変わった記録だけ（チャンク 1件", substring = true)
        onNodeWithText(Strings.BACKUP_DIFF_KEEP_FILES).performScrollTo()
        onNodeWithText(Strings.BACKUP_KIND_DIFF).performScrollTo()
        assertNoEnglish()
        screenshot("backup-diff")

        onNodeWithText(Strings.BACKUP_CREATE_BUTTON).performScrollTo().performClick()
        waitForText(Strings.backedUpDiff(days = 1, chunks = 1, audioFiles = 0))
        assertEquals("watch-audio-backup-2026-10-10-diff.wabackup", services.savedExports.single().first)
        waitForText(Strings.lastBackupDiff("2026年10月10日(土) 15:40", "2026年10月10日(土)", 1))
        onNode(hasText(Strings.backedUpDiff(days = 1, chunks = 1, audioFiles = 0))).performScrollTo()
        assertNoEnglish()
        screenshot("backup-diff-done")
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

    /** Search by meaning (#24): the indexing note, and a passage found by meaning with its label. */
    @Test
    fun searchByMeaning() = runDesktopComposeUiTest(width = 412, height = 915) {
        val semantic = FakeSemanticSearch(SemanticStatus.Indexing(0.42f))
        runBlocking {
            DemoData.seed(store, day, tokyo, until = now - 20 * 60_000L)
            store.updateSettings { it.copy(onboardingCompleted = true) }
            val chunk = store.read { chunksToEmbed("fake-model", 1) }.single().chunkId
            val segments = store.read { chunkSegments(chunk) }
            store.transaction {
                savePassages(chunk, "fake-model", segments.map { it.id }, listOf(NewPassage(0, segments.size, segments[0].startAt, 0.01f, ByteArray(4))))
            }
            semantic.hits = listOf(VectorHit(store.read { passageVectors("fake-model", 0, 1) }.single().id, 0.9f))
        }
        showApp(FakeAppServices(store, audioRoot = audioDir.path, semanticSearch = semantic))
        waitForText("2026年10月10日(土)")
        onNodeWithText(Strings.TAB_SEARCH).performClick()
        waitForText(Strings.semanticStatus(SemanticStatus.Indexing(0.42f))!!)
        waitForText(Strings.SEARCH_HINT_SEMANTIC)
        onNode(hasSetTextAction()).performTextInput("この前の予定の話")
        waitForText(Strings.MATCH_BY_MEANING)
        assertNoEnglish()
        screenshot("search-meaning")
    }

    /**
     * #184: 「エージェント連携」 opens from the status tab. Pairing by the typed string shows the
     * 6-digit code and stores the pairing once confirmed; the switch turns access on with the
     * chosen auto-off; the access log lists time, PC, tool, count and search terms; revoking asks
     * first, removes the pairing and turns access off with the last one.
     */
    @Test
    fun agentAccessPairTurnOnAndRevoke() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true) } }
        val agent = FakeAgentAccess(store, { now })
        showApp(FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), agentAccess = agent))
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.PRIVACY_NOTE_AGENT)
        onNodeWithText(Strings.AGENT_OPEN).performScrollTo().performClick()
        waitForText(Strings.AGENT_INTRO)
        waitForText(Strings.AGENT_NO_PAIRINGS)
        waitForText(Strings.AGENT_HISTORY_EMPTY)
        assertTrue(onAllNodes(hasText(Strings.AGENT_FOREGROUND_ONLY)).fetchSemanticsNodes().isEmpty())
        screenshot("agent-empty")

        // Pair by the string the CLI printed.
        val fingerprint = encodeBase64Url(ByteArray(32) { 9 })
        val uri = PairingUri(1, "仕事用ノート", fingerprint, listOf("192.168.1.20:47823"), encodeBase64Url(ByteArray(16) { 4 }), now + 300_000)
        onNodeWithText(Strings.AGENT_ENTER).performClick()
        onNode(hasSetTextAction()).performTextInput(uri.toUri())
        onNodeWithText(Strings.AGENT_ENTER_SUBMIT).performClick()
        waitForText("123 456")
        waitForText(Strings.agentConfirmTitle("仕事用ノート"))
        screenshot("agent-confirm")
        onNodeWithText(Strings.AGENT_CODE_MATCHES).performClick()
        waitForText(Strings.agentPaired("仕事用ノート"))
        assertEquals(listOf(uri), agent.pairedUris)
        val pairing = runBlocking { store.read { activeAgentPairing(fingerprint) } }!!
        assertEquals(AgentLimits.DEFAULT_SCOPE_DAYS, pairing.scopeDays)
        assertEquals(false, pairing.allowAudio)
        onNodeWithText(Strings.AGENT_CLOSE).performClick()

        // Agent access on for an hour; the PC shows as connected.
        onNodeWithText(Strings.agentMinutes(60)).performScrollTo().performClick()
        onNodeWithText(Strings.AGENT_SWITCH_DETAIL_OFF).performClick()
        waitForText(Strings.agentOnUntil("16:40"))
        waitForText("仕事用ノート: ${Strings.AGENT_LINK_CONNECTED}")
        assertEquals(listOf(60), agent.turnOnRequests)

        // Audio for this PC, a longer period, and what it read.
        onNodeWithText(Strings.AGENT_ALLOW_AUDIO).performScrollTo().performClick()
        onNodeWithText(Strings.agentScopeDays(30)).performScrollTo().performClick()
        onNodeWithText(Strings.agentScopeDays(90)).performClick()
        waitUntil(timeoutMillis = 10_000) { runBlocking { store.read { agentPairing(pairing.id) } }!!.let { it.allowAudio && it.scopeDays == 90 } }
        runBlocking {
            store.transaction {
                logAgentAccess(AgentAccessEntry(now - 5 * 60_000L, pairing.id, "search_transcripts", AgentAccessOutcome.OK, 3, "来週の会議"))
                logAgentAccess(AgentAccessEntry(now - 4 * 60_000L, pairing.id, "get_context", AgentAccessOutcome.OK, 11, "utterance 42"))
                logAgentAccess(AgentAccessEntry(now - 2 * 60_000L, pairing.id, "get_audio_clip", AgentAccessOutcome.DENIED, 0, "utterance 42"))
            }
        }
        waitForText("「来週の会議」")
        onNodeWithText(Strings.AGENT_SWITCH_SECTION).performScrollTo()
        screenshot("agent-on")
        onNodeWithText("「来週の会議」").performScrollTo()
        waitForText(Strings.agentOutcome(AgentAccessOutcome.DENIED)!!)
        screenshot("agent-history")
        assertNoEnglishExcept(setOf("「utterance 42」"))

        // Revoke asks first, then removes the pairing and turns access off with the last one.
        onNodeWithText(Strings.AGENT_REVOKE).performScrollTo().performClick()
        waitForText(Strings.agentConfirmRevoke("仕事用ノート"))
        onNodeWithText(Strings.AGENT_REVOKE_CONFIRM).performClick()
        waitUntil(timeoutMillis = 10_000) { agent.turnOffs == 1 }
        assertTrue(runBlocking { store.read { agentPairing(pairing.id) } }!!.revokedAt != null)
        waitForText(Strings.AGENT_NO_PAIRINGS)
        // The log keeps naming the revoked PC.
        waitForText("仕事用ノート", substring = true)

        onNodeWithText(Strings.AGENT_BACK).performClick()
        waitForText(Strings.AGENT_OPEN)
    }

    /**
     * iOS (#185): the screen says access lasts only while the app is on screen, offers the live
     * camera, and points to Settings when the system refused the local network while on.
     */
    @Test
    fun agentAccessOnIos() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true) } }
        val agent = FakeAgentAccess(
            store,
            { now },
            qrScanner = QrScanner.LIVE_CAMERA,
            foregroundOnly = true,
            localNetworkSetting = LocalNetworkSetting.LOCAL_NETWORK,
        )
        val fingerprint = encodeBase64Url(ByteArray(32) { 9 })
        runBlocking { store.transaction { addAgentPairing("仕事用ノート", fingerprint, "192.168.1.20:47823", 30, false, now) } }
        showApp(FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), agentAccess = agent))
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        onNodeWithText(Strings.AGENT_OPEN).performScrollTo().performClick()
        waitForText(Strings.AGENT_FOREGROUND_ONLY)
        waitForText(Strings.AGENT_SCAN_LIVE)
        waitForText(Strings.AGENT_SCAN_LIVE_NOTE)
        assertTrue(onAllNodes(hasText(Strings.AGENT_SCAN)).fetchSemanticsNodes().isEmpty())
        onNodeWithText(Strings.AGENT_SWITCH_DETAIL_OFF).performClick()
        waitForText("仕事用ノート: ${Strings.AGENT_LINK_CONNECTED}")
        agent.mutableState.value = agent.mutableState.value.copy(localNetworkDenied = true)
        waitForText(Strings.AGENT_PERMISSION_DENIED_LOCAL_NETWORK)
        onNodeWithText(Strings.AGENT_SWITCH_SECTION).performScrollTo()
        screenshot("agent-ios")
        assertNoEnglish()
    }

    /** Agent access is offered only where the platform has it. */
    @Test
    fun noAgentAccessEntryWithoutPlatformSupport() = runDesktopComposeUiTest(width = 412, height = 915) {
        runBlocking { store.updateSettings { it.copy(onboardingCompleted = true) } }
        showApp(FakeAppServices(store, audioRoot = audioDir.path))
        waitForText(Strings.TAB_STATUS)
        onNodeWithText(Strings.TAB_STATUS).performClick()
        waitForText(Strings.PRIVACY_NOTE)
        assertTrue(onAllNodes(hasText(Strings.AGENT_OPEN)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText(Strings.PRIVACY_NOTE_AGENT)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * Hour summaries (#27): 「この日を要約」 with a fake on-device LLM, the progress while it runs,
     * the hours with their highlights, and a highlight opening its utterance and playing it.
     */
    @Test
    fun hourSummariesFromTheDayScreen() = runDesktopComposeUiTest(width = 412, height = 1400) {
        runBlocking {
            for (hour in listOf(9, 10, 11)) DemoData.seedTalk(store, day, tokyo, hour)
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        val llm = FakeSummaryLlm().apply { gate = CompletableDeferred() }
        val services = FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), llm = llm)
        showApp(services)
        waitForText(Strings.summaryTodo(3))
        onNodeWithText(Strings.SUMMARIES).performScrollTo()
        screenshot("summary-todo")

        onNodeWithText(Strings.SUMMARIZE_DAY).performClick()
        waitForText(Strings.summarizing(0, 3))
        waitForText(Strings.SUMMARY_GENERATING)
        screenshot("summary-generating")

        llm.gate?.complete(Unit)
        waitForText(Strings.SUMMARY_ALL_DONE)
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("会議は十時から")).fetchSemanticsNodes().size == 3 }
        onNodeWithText(Strings.SUMMARIES).performScrollTo()
        screenshot("summary-done")
        assertNoEnglish()

        // The highlight of 10:00 cites the hour's first utterance: it plays from there.
        onAllNodes(hasText("会議は十時から"))[1].performClick()
        waitUntil(timeoutMillis = 10_000) { services.played.isNotEmpty() }
        assertEquals("t10.m4a" to 0L, services.played.last())
        waitForText("10:00 から再生中")
    }

    /** Every per-hour state of §4.6 at once: done, stale, failed, refused, little speech, not yet, waiting, not over. */
    @Test
    fun hourSummaryStates() = runDesktopComposeUiTest(width = 412, height = 2200) {
        val window = dayWindow(day, tokyo)
        fun hour(h: Int) = window.start + h * 3_600_000L
        suspend fun record(h: Int, state: HourSummaryState, summary: String? = null, highlights: List<HighlightRecord> = emptyList()) {
            val segments = store.read { segmentsStartedBetween(hour(h), hour(h + 1)) }
            val cited = highlights.map { it.copy(segmentIds = listOf(segments.first().id)) }
            store.transaction {
                saveHourSummary(
                    NewHourSummary(hour(h), tokyo.id, state, summary, "fake", "26", SummaryPrompts.VERSION, hourSourceHash(segments), now, highlights = cited),
                )
            }
        }
        runBlocking {
            for (h in 8..11) DemoData.seedTalk(store, day, tokyo, h)
            DemoData.seedTalk(store, day, tokyo, 12, utterances = 2)
            DemoData.seedTalk(store, day, tokyo, 13)
            DemoData.seedTalk(store, day, tokyo, 14, transcribed = false)
            DemoData.seedTalk(store, day, tokyo, 15)
            record(
                8, HourSummaryState.DONE, "明日の会議の時間と資料の共有、予算の見積もりについて話した。",
                listOf(HighlightRecord("会議は十時から", "明日の会議は十時に始まる。", emptyList()), HighlightRecord("予算が増える", "見積もりが先週より増えそう。", emptyList())),
            )
            record(9, HourSummaryState.DONE, "来週の水曜日に集まることを決めた。", listOf(HighlightRecord("次は水曜日", "来週の水曜日にもう一度集まる。", emptyList())))
            record(10, HourSummaryState.FAILED)
            record(11, HourSummaryState.REFUSED)
            // A late transcript of 9:00 makes its summary stale.
            store.transaction { saveTranscript("t9", listOf(NewSegment(hour(9) + 1_000, hour(9) + 3_000, "言い直し".repeat(60), "ja")), "demo") }
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        showApp(FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), llm = FakeSummaryLlm()))
        waitForText(Strings.SUMMARY_STALE)
        val texts = listOf(
            Strings.SUMMARY_FAILED, Strings.SUMMARY_RETRY, Strings.SUMMARY_REFUSED, Strings.SUMMARY_LITTLE_SPEECH, Strings.SUMMARY_NOT_YET,
            Strings.SUMMARY_WAIT_TRANSCRIPTION, Strings.SUMMARY_HOUR_NOT_OVER, "予算が増える",
        )
        texts.forEach { waitForText(it) }
        waitForText(Strings.summaryTodo(3)) // 9:00 (stale), 10:00 (failed), 13:00
        onNodeWithText(Strings.SUMMARIES).performScrollTo()
        screenshot("summary-states")
        assertNoEnglish()
    }

    /**
     * What the card says when the on-device LLM cannot be used (§4.6): a phone without Gemini Nano
     * or Apple Intelligence, and the states before the model is ready.
     */
    @Test
    fun hourSummaryAvailability() = runDesktopComposeUiTest(width = 412, height = 1100) {
        runBlocking {
            DemoData.seedTalk(store, day, tokyo, 9)
            store.updateSettings { it.copy(onboardingCompleted = true) }
        }
        val llm = FakeSummaryLlm(availability = LlmAvailability.Unsupported("device_not_eligible"))
        showApp(FakeAppServices(store, audioRoot = audioDir.path, initialStatus = ProcessingStatus(asrModel = ModelState.Ready), llm = llm))
        val states = listOf(
            Triple(LlmAvailability.Unsupported("device_not_eligible"), Strings.SUMMARY_UNSUPPORTED, "unsupported"),
            Triple(LlmAvailability.TurnedOff, Strings.SUMMARY_TURNED_OFF, "turned-off"),
            Triple(LlmAvailability.Downloading(null), Strings.SUMMARY_PREPARING, "preparing"),
            Triple(LlmAvailability.LanguageNotSupported, Strings.SUMMARY_LANGUAGE_NOT_SUPPORTED, "language"),
        )
        for ((availability, text, name) in states) {
            llm.availability = availability
            runBlocking { store.transaction { } } // the screen re-reads after a write
            waitForText(text)
            assertTrue(onAllNodes(hasText(Strings.SUMMARIZE_DAY)).fetchSemanticsNodes().isEmpty(), "no button when $availability")
            onNodeWithText(Strings.SUMMARIES).performScrollTo()
            screenshot("summary-$name")
        }
        // Android's AICore (#205): the OS downloads Gemini Nano only after the user's tap, so the button stays.
        llm.availability = LlmAvailability.NeedsDownload(null)
        runBlocking { store.transaction { } }
        waitForText(Strings.SUMMARY_NEEDS_MODEL)
        onNodeWithText(Strings.SUMMARIZE_DAY).assertExists()
        onNodeWithText(Strings.SUMMARIES).performScrollTo()
        screenshot("summary-needs-model")
        assertNoEnglish()
    }

    /** Fails on any text on screen (scrolled away or not) with an English word and no Japanese. */
    private fun ComposeUiTest.assertNoEnglish() = assertNoEnglishExcept(emptySet())

    /** [assertNoEnglish], allowing [allowed] (data shown as it is, e.g. what an agent asked for). */
    private fun ComposeUiTest.assertNoEnglishExcept(allowed: Set<String>) {
        val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .filter { it !in allowed }
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

        val ENGLISH_ATTRIBUTION = OpenSourceNotices.SENSE_VOICE.let { Strings.englishModelAttribution(it.name, it.author, it.url) }
    }
}
