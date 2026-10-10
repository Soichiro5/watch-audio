package app.watchaudio.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatClock
import kotlin.random.Random
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real playback on a device or emulator (#19, #25): audio archived by the production Ingestor
 * is played by [AndroidAppServices] (MediaPlayer) from the requested position. Run with
 * `./gradlew :androidApp:connectedDebugAndroidTest` (CI: emulator.yml).
 */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @After
    fun tearDown() = PlaybackFixtures.stopPlayback()

    /** #19: yesterday's recording plays from a given time. */
    @Test
    fun playsYesterdaysChunkFromTheRequestedOffset() {
        val chunk = PlaybackFixtures.seedYesterdayChunk(durationMs = 90_000, segments = emptyList())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            PlaybackFixtures.graph.services.play(chunk.audioPath, 45_000)
        }
        PlaybackFixtures.assertPlaysFrom(requestedMs = 45_000, check = "services.play")
    }

    /** #25: search, one tap on the hit, and the audio plays from that utterance (also #19 via the UI). */
    @Test
    fun searchHitPlaysWithOneTap() {
        val word = "再生確認" + Random.nextInt(100_000, 999_999)
        val chunk = PlaybackFixtures.seedYesterdayChunk(
            durationMs = 120_000,
            segments = listOf(10_000L to "冒頭の雑談です", 60_000L to "これは${word}の発話です"),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(Strings.TAB_SEARCH).performClick()
            compose.onNode(hasSetTextAction()).performTextInput(word)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText("の発話です", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }

            compose.onAllNodes(hasText("の発話です", substring = true))[0].performClick() // the one tap

            val startsAt = chunk.startedAt + 60_000
            val playing = Strings.playingFrom(formatClock(startsAt, TimeZone.currentSystemDefault()))
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(playing)).fetchSemanticsNodes().isNotEmpty()
            }
            PlaybackFixtures.assertPlaysFrom(requestedMs = 60_000, check = "search_one_tap")
        }
    }
}
