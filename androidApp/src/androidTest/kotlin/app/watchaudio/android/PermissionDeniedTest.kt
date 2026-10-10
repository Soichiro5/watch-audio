package app.watchaudio.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.hasText
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
import app.watchaudio.app.settings.updateSettings
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #32 on the emulator: the onboarding asks for the notification permission, the user refuses it in
 * the system dialog, and the page explains it and opens the app's system settings.
 *
 * Needs a permission that was never granted (revoking one kills the app process, so the test cannot
 * reset it itself): emulator.yml runs it right after installing, with `-e scenario permission-denied`.
 */
@RunWith(AndroidJUnit4::class)
class PermissionDeniedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun refusedNotificationPermissionLeadsToTheAppSettings() {
        Evidence.assumeScenario("permission-denied")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val context = instrumentation.targetContext
        check(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            "POST_NOTIFICATIONS is already granted; run this on a fresh install"
        }
        runBlocking { PlaybackFixtures.graph.store.updateSettings { it.copy(onboardingCompleted = false) } }

        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(Strings.WELCOME_TITLE)
            compose.onNodeWithText(Strings.ONBOARDING_NEXT).performClick()
            waitForText(Strings.CONSENT_CHECKBOX)
            compose.onNodeWithText(Strings.CONSENT_CHECKBOX).performScrollTo().performClick()
            compose.onNodeWithText(Strings.ONBOARDING_NEXT).performClick()
            waitForText(Strings.PERMISSION_ALLOW)
            compose.onNodeWithText(Strings.PERMISSION_ALLOW).performClick()

            // The system permission dialog: refuse.
            val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 15_000)
            Evidence.screenshot("permission-system-dialog")
            checkNotNull(deny) { "the system permission dialog did not appear" }.click()

            waitForText(Strings.OPEN_SETTINGS)
            compose.onNodeWithText(Strings.PERMISSION_DENIED).assertExists()
            Evidence.screenshot("onboarding-permission-denied")

            compose.onNodeWithText(Strings.OPEN_SETTINGS).performClick()
            val settingsShown = device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000) == true
            Evidence.screenshot("app-settings-opened")
            Evidence.log("PERMISSION_CHECK permission=POST_NOTIFICATIONS state=denied denied_text_shown=true settings_opened=$settingsShown")
            assertTrue(settingsShown, "the app's settings page did not open")
            device.pressBack()
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 15_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
}
