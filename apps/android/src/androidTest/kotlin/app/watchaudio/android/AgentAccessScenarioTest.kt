package app.watchaudio.android

import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.watchaudio.app.Strings
import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.settings.updateSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #184: agent access against the real bridge CLI on the CI host (reached at 10.0.2.2), driven by
 * `.github/scripts/agent-access.sh` with `tools/agent-bridge/e2e/emulator-host.ts` on the host:
 *
 * 1. [pair] (`-e scenario agent-pair -e pairingUri <base64url of the URI>`): seeds a transcript,
 *    opens 「エージェント連携」 from the status tab, enters the string the CLI printed, shows the
 *    6-digit code (the script compares it with the PC's) and confirms it.
 * 2. [serve] (`-e scenario agent-serve`): turns agent access on with the switch (foreground
 *    service, notification), waits until the PC's agent has read the transcript (access log),
 *    turns it off and on again, and revokes the pairing; the host checks that its tool calls are
 *    answered only while it is on and paired.
 */
@RunWith(AndroidJUnit4::class)
class AgentAccessScenarioTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph get() = PlaybackFixtures.graph
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun pair() {
        Evidence.assumeScenario("agent-pair")
        val uri = String(Base64.decode(requireNotNull(Evidence.argument("pairingUri")) { "-e pairingUri <base64url>" }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        runBlocking { graph.store.updateSettings { it.copy(onboardingCompleted = true) } }
        PlaybackFixtures.seedYesterdayChunk(20_000, listOf(2_000L to "エージェント連携の確認をしましょう", 9_000L to "来週の会議の資料を用意します"))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Strings.TAB_STATUS)
            compose.onNodeWithText(Strings.AGENT_OPEN).performScrollTo().performClick()
            compose.waitForText(Strings.AGENT_ENTER)
            compose.onNodeWithText(Strings.AGENT_ENTER).performScrollTo().performClick()
            compose.diagnosed("the text field") { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(uri)
            compose.onNodeWithText(Strings.AGENT_ENTER_SUBMIT).performScrollTo().performClick()
            compose.diagnosed("the confirmation code", 30_000) { compose.onAllNodes(hasText(Strings.AGENT_CODE_MATCHES)).fetchSemanticsNodes().isNotEmpty() }
            val code = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                .firstOrNull { CODE.matches(it) }
            Evidence.screenshot("agent-confirm")
            compose.onNodeWithText(Strings.AGENT_CODE_MATCHES).performScrollTo().performClick()
            compose.diagnosed("the pairing result", 60_000) { compose.onAllNodes(hasText("ペアリングしました", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            Evidence.screenshot("agent-paired")
            val pairing = runBlocking { graph.store.read { agentPairings() } }.singleOrNull { it.isActive }
            Evidence.log("AGENT_CHECK phase=paired code=${code?.replace(" ", "")} pc=${pairing?.name} address=${pairing?.address} scope_days=${pairing?.scopeDays} audio=${pairing?.allowAudio}")
            assertNotNull(pairing)
            assertEquals(false, pairing.allowAudio)
            assertTrue(pairing.address!!.startsWith("10.0.2.2:"), pairing.address)
        }
    }

    @Test
    fun serve() {
        Evidence.assumeScenario("agent-serve")
        val access = graph.agentAccess
        val pairing = runBlocking { graph.store.read { agentPairings() } }.single { it.isActive }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Strings.TAB_STATUS)
            compose.onNodeWithText(Strings.AGENT_OPEN).performScrollTo().performClick()
            // The switch is enabled once the pairings are loaded (the time choices show then).
            compose.waitForText(Strings.agentMinutes(30))

            // On: the foreground service connects and the PC's agent reads the transcript.
            val firstOn = System.currentTimeMillis()
            compose.onNodeWithText(Strings.AGENT_SWITCH_DETAIL_OFF).performScrollTo().performClick()
            PlaybackFixtures.waitFor(60_000, "the connection to the bridge") { access.state.value.links[pairing.id]?.takeIf { it == LinkState.CONNECTED } }
            val service = Evidence.shell("dumpsys activity services app.watchaudio/.agent.AgentAccessService")
            val foreground = "isForeground=true" in service
            val read = waitForLog(firstOn, "a tool call answered while on")
            Evidence.screenshot("agent-on")
            device.openNotification()
            SystemClock.sleep(1_500)
            Evidence.screenshot("agent-notification")
            device.pressBack()
            Evidence.log("AGENT_CHECK phase=on links=${access.state.value.links} foreground_service=$foreground first_call=${read.tool}:${read.outcome}:${read.itemCount} query='${read.query}'")
            assertTrue(foreground, service)

            // Off: the connection ends; the host sees the phone gone. Then on again.
            compose.onNodeWithText(Strings.AGENT_SWITCH).performScrollTo().performClick()
            PlaybackFixtures.waitFor(15_000, "access off") { access.state.value.takeIf { !it.isOn } }
            // A call already being answered when the switch flipped may still be logged just after.
            val offSince = System.currentTimeMillis() + IN_FLIGHT_MS
            SystemClock.sleep(OFF_WINDOW_MS)
            val serviceAfterOff = Evidence.shell("dumpsys activity services app.watchaudio/.agent.AgentAccessService")
            val duringOff = log(offSince).count { it.outcome == AgentAccessOutcome.OK }
            Evidence.log("AGENT_CHECK phase=off service_running=${"AgentAccessService" in serviceAfterOff} calls_answered_while_off=$duringOff")
            assertEquals(0, duringOff)

            compose.waitForText(Strings.agentMinutes(30))
            val secondOn = System.currentTimeMillis()
            compose.onNodeWithText(Strings.AGENT_SWITCH_DETAIL_OFF).performScrollTo().performClick()
            waitForLog(secondOn, "a tool call answered after turning on again")

            // Revoke: the connection ends at once and nothing is answered any more.
            compose.onNodeWithText(Strings.AGENT_REVOKE).performScrollTo().performClick()
            compose.waitForText(Strings.AGENT_REVOKE_CONFIRM)
            compose.onNodeWithText(Strings.AGENT_REVOKE_CONFIRM).performScrollTo().performClick()
            val revokedAt = System.currentTimeMillis() + IN_FLIGHT_MS
            PlaybackFixtures.waitFor(15_000, "access off after revoking the last pairing") { access.state.value.takeIf { !it.isOn } }
            SystemClock.sleep(OFF_WINDOW_MS)
            val afterRevoke = log(revokedAt).count { it.outcome == AgentAccessOutcome.OK }
            val revoked = runBlocking { graph.store.read { agentPairing(pairing.id) } }
            compose.onNodeWithText(Strings.AGENT_HISTORY).performScrollTo()
            Evidence.screenshot("agent-revoked")
            val entries = log(firstOn)
            Evidence.log(
                "AGENT_CHECK phase=revoked revoked_at=${revoked?.revokedAt} calls_answered_after=$afterRevoke log_entries=${entries.size} " +
                    "tools=${entries.map { it.tool }.distinct()} outcomes=${entries.map { it.outcome }.distinct()}",
            )
            assertNotNull(revoked?.revokedAt)
            assertEquals(0, afterRevoke)
            assertFalse(access.state.value.isOn)
        }
    }

    private fun ComposeTestRule.waitForText(text: String) = diagnosed("「$text」") { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** [ComposeTestRule.waitUntil], leaving a screenshot and the semantics tree in logcat if it times out. */
    private fun ComposeTestRule.diagnosed(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { onRoot(useUnmergedTree = true).printToLog("AGENT_TREE") }
            runCatching { Evidence.screenshot("agent-timeout") }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun log(since: Long) = runBlocking { graph.store.read { agentAccessLog(500) } }.filter { it.at >= since }

    private fun waitForLog(since: Long, what: String) =
        PlaybackFixtures.waitFor(120_000, what) { log(since).firstOrNull { it.tool == "search_transcripts" && it.outcome == AgentAccessOutcome.OK } }

    private companion object {
        /** The code on screen: two groups of three digits. */
        val CODE = Regex("""\d{3} \d{3}""")

        /** Long enough for the host (one call per second) to notice. */
        const val OFF_WINDOW_MS = 6_000L

        /** Margin for a call that was being answered at the moment access ended. */
        const val IN_FLIGHT_MS = 1_000L
    }
}
