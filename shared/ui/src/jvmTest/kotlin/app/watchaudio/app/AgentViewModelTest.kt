package app.watchaudio.app

import app.watchaudio.app.state.AgentViewModel
import app.watchaudio.app.state.NowProvider
import app.watchaudio.app.state.PairingStep
import app.watchaudio.core.agent.AgentErrorCode
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.encodeBase64Url
import app.watchaudio.core.db.AgentAccessEntry
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.LocalStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 「エージェント連携」 (#184): pairing steps and their messages, the switch, revocation, retention. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dbFile = File.createTempFile("watch-audio-agent", ".db").also { it.delete() }
    private val store = LocalStore.open(dbFile.absolutePath, dispatcher = dispatcher)
    private val now = 1_760_000_000_000L
    private val agent = FakeAgentAccess(store, { now })
    private val services = FakeAppServices(store, agentAccess = agent)
    private val uri = PairingUri(1, "laptop", encodeBase64Url(ByteArray(32) { 5 }), listOf("192.168.1.20:47823"), encodeBase64Url(ByteArray(16) { 6 }), now + 300_000)

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.viewModel() = AgentViewModel(services, NowProvider { now }, scope = backgroundScope).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    @Test
    fun scannedCodePairsAfterTheUserConfirms() = runVmTest {
        val vm = viewModel()
        agent.scan = QrScan.Found(uri.toUri())
        vm.scan()
        runCurrent()
        assertEquals(PairingStep.Confirm("laptop", "123456"), vm.state.value.pairing)
        vm.confirmCode(true)
        runCurrent()
        assertEquals(PairingStep.Paired("laptop"), vm.state.value.pairing)
        assertEquals(listOf("laptop"), vm.state.value.pairings.map { it.name })
        vm.dismissPairing()
        runCurrent()
        assertEquals(PairingStep.Idle, vm.state.value.pairing)
    }

    @Test
    fun scanOutcomes() = runVmTest {
        val vm = viewModel()
        agent.scan = QrScan.Cancelled
        vm.scan()
        runCurrent()
        assertEquals(PairingStep.Idle, vm.state.value.pairing)
        agent.scan = QrScan.NotFound
        vm.scan()
        runCurrent()
        assertEquals(PairingStep.Failed(Strings.AGENT_SCAN_NOT_FOUND), vm.state.value.pairing)
        agent.scan = QrScan.Found("https://example.com/not-a-pairing")
        vm.scan()
        runCurrent()
        assertEquals(PairingStep.Failed(Strings.AGENT_BAD_CODE), vm.state.value.pairing)
        assertTrue(agent.pairedUris.isEmpty())
    }

    /** iOS (#185): the live camera, the Local Network setting and the foreground-only note. */
    @Test
    fun iosWording() = runVmTest {
        val ios = FakeAgentAccess(
            store,
            { now },
            qrScanner = QrScanner.LIVE_CAMERA,
            foregroundOnly = true,
            localNetworkSetting = LocalNetworkSetting.LOCAL_NETWORK,
        )
        val vm = AgentViewModel(FakeAppServices(store, agentAccess = ios), NowProvider { now }, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(QrScanner.LIVE_CAMERA, vm.state.value.qrScanner)
        assertTrue(vm.state.value.foregroundOnly)
        ios.scan = QrScan.Unavailable
        vm.scan()
        runCurrent()
        assertEquals(PairingStep.Failed(Strings.AGENT_SCAN_LIVE_UNAVAILABLE), vm.state.value.pairing)
        vm.dismissPairing()
        ios.pairAnswer = PairAttempt.PermissionDenied
        vm.openEntry()
        vm.setEntryText(uri.toUri())
        vm.submitEntry()
        runCurrent()
        assertEquals(PairingStep.Failed(Strings.AGENT_PERMISSION_DENIED_LOCAL_NETWORK), vm.state.value.pairing)
        store.transaction { addAgentPairing("laptop", uri.fingerprint, null, 30, false, now) }
        ios.turnOnAnswer = TurnOnResult.PERMISSION_DENIED
        vm.setOn(true)
        runCurrent()
        assertEquals(Strings.AGENT_PERMISSION_DENIED_LOCAL_NETWORK, vm.state.value.message)
        ios.turnOnAnswer = TurnOnResult.FAILED
        vm.setOn(true)
        runCurrent()
        assertEquals(Strings.AGENT_TURN_ON_FAILED, vm.state.value.message)
    }

    @Test
    fun typedStringAndItsFailures() = runVmTest {
        val vm = viewModel()
        fun attempt(text: String): PairingStep {
            vm.dismissPairing()
            vm.openEntry()
            vm.setEntryText(text)
            vm.submitEntry()
            runCurrent()
            return vm.state.value.pairing
        }
        assertEquals(PairingStep.Failed(Strings.AGENT_EXPIRED), attempt(uri.copy(expiresAt = now - 1).toUri()))
        agent.pairAnswer = PairAttempt.Unreachable
        assertEquals(PairingStep.Failed(Strings.AGENT_UNREACHABLE), attempt("  " + uri.toUri() + "\n"))
        agent.pairAnswer = PairAttempt.PermissionDenied
        assertEquals(PairingStep.Failed(Strings.AGENT_PERMISSION_DENIED), attempt(uri.toUri()))
        agent.pairAnswer = PairAttempt.Done(PairOutcome.Refused(AgentErrorCode.PAIRING_INVALID, "used"))
        assertEquals(PairingStep.Failed(Strings.AGENT_PAIR_USED), attempt(uri.toUri()))
        agent.pairAnswer = PairAttempt.Done(PairOutcome.Refused(AgentErrorCode.PAIRING_REJECTED, "declined on the PC"))
        assertEquals(PairingStep.Failed(Strings.AGENT_PAIR_REJECTED_BY_PC), attempt(uri.toUri()))
        agent.pairAnswer = null
        assertIs<PairingStep.Confirm>(attempt(uri.toUri()))
        vm.confirmCode(false)
        runCurrent()
        assertEquals(PairingStep.Failed(Strings.AGENT_PAIR_DECLINED), vm.state.value.pairing)
        assertTrue(store.read { agentPairings() }.isEmpty())
    }

    @Test
    fun switchNeedsAPairingAndReportsARefusedPermission() = runVmTest {
        val vm = viewModel()
        vm.setOn(true)
        runCurrent()
        assertEquals(Strings.AGENT_NO_PAIRINGS, vm.state.value.message)
        store.transaction { addAgentPairing("laptop", uri.fingerprint, null, 30, false, now) }
        agent.turnOnAnswer = TurnOnResult.PERMISSION_DENIED
        vm.setOn(true)
        runCurrent()
        assertEquals(Strings.AGENT_PERMISSION_DENIED, vm.state.value.message)
        agent.turnOnAnswer = null
        vm.setOnMinutes(15)
        vm.setOn(true)
        runCurrent()
        assertNull(vm.state.value.message)
        assertEquals(now + 15 * 60_000L, vm.state.value.access.offAt)
        vm.setOn(false)
        runCurrent()
        assertNull(vm.state.value.access.offAt)
        assertEquals(listOf(30, 30, 15), agent.turnOnRequests)
    }

    @Test
    fun revokingTheLastPairingTurnsAccessOff() = runVmTest {
        val vm = viewModel()
        val id = store.transaction { addAgentPairing("laptop", uri.fingerprint, null, 30, false, now) }
        vm.setOn(true)
        runCurrent()
        vm.setScopeDays(vm.state.value.pairings.single(), 7)
        runCurrent()
        assertEquals(7, store.read { agentPairing(id) }!!.scopeDays)
        val pairing = vm.state.value.pairings.single()
        vm.requestRevoke(pairing)
        runCurrent()
        assertEquals(pairing.copy(scopeDays = 7), vm.state.value.revoking)
        vm.cancelRevoke()
        runCurrent()
        assertNull(vm.state.value.revoking)
        vm.requestRevoke(pairing)
        vm.confirmRevoke()
        runCurrent()
        assertEquals(now, store.read { agentPairing(id) }!!.revokedAt)
        assertTrue(vm.state.value.pairings.isEmpty())
        assertEquals(1, agent.turnOffs)
    }

    @Test
    fun openingTheScreenDropsLogEntriesPastNinetyDays() = runVmTest {
        val id = store.transaction { addAgentPairing("laptop", uri.fingerprint, null, 30, false, now) }
        store.transaction {
            logAgentAccess(AgentAccessEntry(now - 91 * DAY, id, "search_transcripts", AgentAccessOutcome.OK, 1, "old"))
            logAgentAccess(AgentAccessEntry(now - DAY, id, "search_transcripts", AgentAccessOutcome.OK, 2, "new"))
        }
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("new"), vm.state.value.log.map { it.entry.query })
        assertEquals("laptop", vm.state.value.log.single().pcName)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
