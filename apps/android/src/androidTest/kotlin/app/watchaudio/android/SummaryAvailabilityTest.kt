package app.watchaudio.android

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.watchaudio.android.llm.GeminiNanoLlmProvider
import app.watchaudio.app.Strings
import app.watchaudio.core.settings.updateSettings
import app.watchaudio.core.summary.LlmAvailability
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hour summaries on a phone without Gemini Nano (#205): the real ML Kit provider of the app reports
 * the device as unsupported, and the day screen says 「この端末では、端末内の AI による要約を使えません」
 * with no 「この日を要約」 button while the rest of the day (transcript) stays. Every emulator is such a
 * phone (no AICore); on a device with Gemini Nano the UI part is skipped (checked by hand, #61).
 * Evidence: `LLM_CHECK` / `SUMMARY_CHECK` lines and the screenshot `summary-unsupported`.
 */
@RunWith(AndroidJUnit4::class)
class SummaryAvailabilityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun withoutGeminiNanoTheDayScreenSaysUnsupported() {
        val llm = PlaybackFixtures.graph.services.llm
        val availability = runBlocking { llm.availability() }
        Evidence.log("LLM_CHECK provider=${llm.id} availability=$availability")
        assertEquals(GeminiNanoLlmProvider.ID, llm.id)
        assumeTrue("this device has Gemini Nano ($availability); checked on the device (#61)", availability is LlmAvailability.Unsupported)

        PlaybackFixtures.seedYesterdayChunk(
            durationMs = 60_000,
            segments = listOf(5_000L to "明日の会議は十時からなので資料を共有します", 20_000L to "予算の見積もりは少し増えそうです"),
        )
        runBlocking { PlaybackFixtures.graph.store.updateSettings { it.copy(onboardingCompleted = true) } }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Strings.TAB_TIMELINE)
            compose.onNodeWithContentDescription(Strings.PREV_DAY).performClick()
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodes(hasText(Strings.SUMMARY_UNSUPPORTED)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(Strings.SUMMARY_UNSUPPORTED).performScrollTo()
            assertTrue(compose.onAllNodes(hasText(Strings.SUMMARIZE_DAY)).fetchSemanticsNodes().isEmpty(), "no summarize button")
            // The rest of the day (coverage, transcript) stays: the card is the only change (screenshot).
            assertTrue(compose.onAllNodes(hasText(Strings.SUMMARIES)).fetchSemanticsNodes().isNotEmpty(), "summary card shown")
            Evidence.screenshot("summary-unsupported")
            Evidence.log("SUMMARY_CHECK state=unsupported button=absent")
        }
    }
}
