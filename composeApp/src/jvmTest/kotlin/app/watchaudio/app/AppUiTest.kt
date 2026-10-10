package app.watchaudio.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.watchaudio.app.logic.dayWindow
import app.watchaudio.app.settings.loadSettings
import app.watchaudio.app.settings.updateSettings
import app.watchaudio.app.state.NowProvider
import app.watchaudio.core.db.LocalStore
import java.io.File
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
 * Screenshots land in `composeApp/build/screenshots/` for a quick visual check.
 */
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    private val dbFile = File.createTempFile("watch-audio-app", ".db").also { it.delete() }
    private val store = LocalStore.open(dbFile.absolutePath)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val day = LocalDate(2026, 10, 10)
    private val now = dayWindow(day, tokyo).start + (15 * 60 + 40) * 60_000L
    private val services = FakeAppServices(store, initialStatus = ProcessingStatus(asrModel = ModelState.Downloading(0.42f)))

    private val owners = object : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @AfterTest
    fun tearDown() {
        owners.viewModelStore.clear()
        store.close()
        dbFile.delete()
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
}
