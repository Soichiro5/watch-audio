package app.watchaudio.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.settings.updateSettings
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone

/**
 * Playback on the iOS simulator (#19, #25): for audio archived by the production Ingestor,
 * [IosAppServices] opens the right file in an AVAudioPlayer at the requested position, directly
 * and through the shared Compose UI (timeline of the previous day; search, then one tap on the hit).
 * The iOS counterpart of apps/android's `PlaybackTest`; runs in `./gradlew iosSimulatorArm64Test`.
 *
 * This executable is not an app process, so `AVAudioPlayer.play()` returns false here; that the
 * player really starts at the offset and advances is checked in the app by `apps/ios/Tests/PlaybackTests`.
 */
@OptIn(ExperimentalTestApi::class)
class IosPlaybackTest {
    private val fixture = IosPlaybackFixture()
    private val owners = object : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @AfterTest
    fun tearDown() {
        owners.viewModelStore.clear()
        fixture.close()
    }

    /** #19: yesterday's recording is opened at a given time. */
    @Test
    fun preparesYesterdaysChunkAtTheRequestedOffset() {
        val chunk = fixture.seedYesterdayChunk(durationMs = 90_000, segments = emptyList())
        fixture.services.play(chunk.audioPath, 45_000)
        fixture.assertPreparedAt(chunk, requestedMs = 45_000, durationMs = 90_000, check = "services.play")
    }

    /** #19 through the UI: go to the previous day on the timeline and tap an utterance. */
    @Test
    fun timelineOfThePreviousDayPlaysAnUtterance() {
        val chunk = fixture.seedYesterdayChunk(
            durationMs = 90_000,
            segments = listOf(5_000L to "朝の挨拶です", 30_000L to "昨日の打ち合わせの件です"),
        )
        runBlocking { fixture.store.updateSettings { it.copy(onboardingCompleted = true) } }

        runComposeUiTest {
            showApp()
            waitForText(Strings.TAB_TIMELINE)
            onNode(hasContentDescription(Strings.PREV_DAY)).performClick()
            waitForText("昨日の打ち合わせの件です")
            onAllNodes(hasText("昨日の打ち合わせの件です"))[0].performClick()

            waitForText(Strings.playingFrom(formatClock(chunk.startedAt + 30_000, TimeZone.currentSystemDefault())))
            fixture.assertPreparedAt(chunk, requestedMs = 30_000, durationMs = 90_000, check = "timeline_previous_day")
        }
    }

    /** #25: search, one tap on the hit, and the audio plays from that utterance. */
    @Test
    fun searchHitPlaysWithOneTap() {
        val word = "再生確認" + Random.nextInt(100_000, 999_999)
        val chunk = fixture.seedYesterdayChunk(
            durationMs = 120_000,
            segments = listOf(10_000L to "冒頭の雑談です", 60_000L to "これは${word}の発話です"),
        )
        runBlocking { fixture.store.updateSettings { it.copy(onboardingCompleted = true) } }

        runComposeUiTest {
            showApp()
            waitForText(Strings.TAB_SEARCH)
            onNodeWithText(Strings.TAB_SEARCH).performClick()
            onNode(hasSetTextAction()).performTextInput(word)
            waitForText("の発話です", substring = true)

            onAllNodes(hasText("の発話です", substring = true))[0].performClick() // the one tap

            waitForText(Strings.playingFrom(formatClock(chunk.startedAt + 60_000, TimeZone.currentSystemDefault())))
            fixture.assertPreparedAt(chunk, requestedMs = 60_000, durationMs = 120_000, check = "search_one_tap")
        }
    }

    private fun ComposeUiTest.showApp() {
        setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owners,
                LocalLifecycleOwner provides owners,
            ) {
                App(fixture.services)
            }
        }
    }

    /**
     * Waits for [text] while letting main-queue work run: the view models use
     * `Dispatchers.Main`, which only advances while the main run loop turns.
     */
    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) {
        waitUntil(conditionDescription = "text \"$text\"", timeoutMillis = 15_000) {
            IosPlaybackFixture.pumpMainRunLoop(20)
            onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
