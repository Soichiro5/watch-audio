package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AgentAccessState
import app.watchaudio.app.AppServices
import app.watchaudio.app.PairAttempt
import app.watchaudio.app.QrScan
import app.watchaudio.app.Strings
import app.watchaudio.app.TurnOnResult
import app.watchaudio.core.agent.AgentErrorCode
import app.watchaudio.core.agent.AgentLimits
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.pruneAgentAccessLog
import app.watchaudio.core.db.AgentAccessEntry
import app.watchaudio.core.db.AgentPairing
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where pairing a PC stands on the 「エージェント連携」 screen. */
sealed interface PairingStep {
    data object Idle : PairingStep

    /** The text field for the string the CLI printed is open. */
    data class Entering(val text: String = "") : PairingStep

    data object Scanning : PairingStep

    data class Connecting(val pcName: String) : PairingStep

    /** Both screens show [code]; the user compares them. */
    data class Confirm(val pcName: String, val code: String) : PairingStep

    /** The phone confirmed; the PC's user still has to. */
    data class WaitingForPc(val pcName: String) : PairingStep

    data class Paired(val pcName: String) : PairingStep

    data class Failed(val message: String) : PairingStep
}

/** One access log row with the name of its pairing. */
data class AccessLogRow(val entry: AgentAccessEntry, val pcName: String)

/** The 「エージェント連携」 screen (#184). */
data class AgentUiState(
    val canScanQr: Boolean = false,
    val access: AgentAccessState = AgentAccessState(),
    /** Minutes until auto-off chosen for the next time it is turned on. */
    val onMinutes: Int = AgentLimits.DEFAULT_ON_MINUTES,
    /** Active pairings, newest first. */
    val pairings: List<AgentPairing> = emptyList(),
    /** Newest first, at most [AgentViewModel.LOG_ROWS]. */
    val log: List<AccessLogRow> = emptyList(),
    val pairing: PairingStep = PairingStep.Idle,
    /** The pairing whose revocation the user is asked to confirm. */
    val revoking: AgentPairing? = null,
    /** Something to tell about the switch (e.g. a refused permission). */
    val message: String? = null,
)

/**
 * Agent access (#184): pairing a PC (QR or typed string, then the 6-digit code), the on/off
 * switch, each pairing's scope, revocation and the access log. Pairings and the log are read from
 * [AppServices.store]; the platform's [AppServices.agentAccess] connects. Opening the screen
 * deletes access log entries past [AgentLimits.ACCESS_LOG_RETENTION_DAYS].
 */
class AgentViewModel(
    private val services: AppServices,
    private val clock: NowProvider = NowProvider.System,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val access = services.agentAccess
    private val local = MutableStateFlow(AgentUiState(canScanQr = access?.canScanQr == true))
    private val data = refreshSignal(services).map { load() }
    private var pairJob: Job? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    val state: StateFlow<AgentUiState> = combine(local, data, access?.state ?: flowOf(AgentAccessState())) { ui, (pairings, log), accessState ->
        ui.copy(pairings = pairings, log = log, access = accessState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch { services.store.pruneAgentAccessLog(clock.now()) }
    }

    private suspend fun load(): Pair<List<AgentPairing>, List<AccessLogRow>> = services.store.read {
        val all = agentPairings()
        val names = all.associate { it.id to it.name }
        all.filter { it.isActive } to agentAccessLog(LOG_ROWS).map { AccessLogRow(it, names[it.pairingId].orEmpty()) }
    }

    fun setOnMinutes(minutes: Int) = local.update { it.copy(onMinutes = minutes) }

    fun setOn(on: Boolean) {
        val agent = access ?: return
        local.update { it.copy(message = null) }
        if (!on) {
            agent.turnOff()
            return
        }
        viewModelScope.launch {
            val message = when (agent.turnOn(local.value.onMinutes)) {
                TurnOnResult.ON -> null
                TurnOnResult.PERMISSION_DENIED -> Strings.AGENT_PERMISSION_DENIED
                TurnOnResult.NO_PAIRINGS -> Strings.AGENT_NO_PAIRINGS
            }
            local.update { it.copy(message = message) }
        }
    }

    fun openEntry() = local.update { it.copy(pairing = PairingStep.Entering()) }

    fun setEntryText(text: String) = local.update { state ->
        if (state.pairing is PairingStep.Entering) state.copy(pairing = PairingStep.Entering(text)) else state
    }

    fun submitEntry() {
        val text = (local.value.pairing as? PairingStep.Entering)?.text ?: return
        startPairing(text)
    }

    fun scan() {
        val agent = access ?: return
        if (pairJob?.isActive == true) return
        local.update { it.copy(pairing = PairingStep.Scanning) }
        pairJob = viewModelScope.launch {
            when (val scan = agent.scanQr()) {
                is QrScan.Found -> pair(scan.text)
                QrScan.Cancelled -> local.update { it.copy(pairing = PairingStep.Idle) }
                QrScan.NotFound -> fail(Strings.AGENT_SCAN_NOT_FOUND)
                QrScan.Unavailable -> fail(Strings.AGENT_SCAN_UNAVAILABLE)
            }
        }
    }

    /** The user compared the codes on both screens. */
    fun confirmCode(matches: Boolean) {
        pendingConfirm?.complete(matches)
        pendingConfirm = null
    }

    /** Closes the pairing panel (and abandons a pairing in progress). */
    fun dismissPairing() {
        pendingConfirm?.complete(false)
        pendingConfirm = null
        pairJob?.cancel()
        local.update { it.copy(pairing = PairingStep.Idle) }
    }

    fun setScopeDays(pairing: AgentPairing, days: Int) = viewModelScope.launch {
        services.store.transaction { setAgentScope(pairing.id, days, pairing.allowAudio) }
    }

    fun setAllowAudio(pairing: AgentPairing, allow: Boolean) = viewModelScope.launch {
        services.store.transaction { setAgentScope(pairing.id, pairing.scopeDays, allow) }
    }

    fun requestRevoke(pairing: AgentPairing) = local.update { it.copy(revoking = pairing) }

    fun cancelRevoke() = local.update { it.copy(revoking = null) }

    /** Revokes at once: the platform's connection to it ends on this write. */
    fun confirmRevoke() {
        val pairing = local.value.revoking ?: return
        local.update { it.copy(revoking = null) }
        viewModelScope.launch {
            services.store.transaction { revokeAgentPairing(pairing.id, clock.now()) }
            val remaining = services.store.read { agentPairings() }.count { it.isActive }
            if (remaining == 0) access?.turnOff()
        }
    }

    private fun startPairing(text: String) {
        if (pairJob?.isActive == true) return
        pairJob = viewModelScope.launch { pair(text) }
    }

    private suspend fun pair(text: String) {
        val agent = access ?: return
        val uri = try {
            PairingUri.parse(text)
        } catch (e: IllegalArgumentException) {
            return fail(Strings.AGENT_BAD_CODE)
        }
        if (uri.expiresAt < clock.now()) return fail(Strings.AGENT_EXPIRED)
        local.update { it.copy(pairing = PairingStep.Connecting(uri.name)) }
        val attempt = try {
            agent.pair(uri) { code ->
                val answer = CompletableDeferred<Boolean>()
                pendingConfirm = answer
                local.update { it.copy(pairing = PairingStep.Confirm(uri.name, code)) }
                answer.await().also { matches ->
                    if (matches) local.update { it.copy(pairing = PairingStep.WaitingForPc(uri.name)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(Strings.AGENT_PAIR_FAILED)
        }
        val step = when (attempt) {
            PairAttempt.Unreachable -> PairingStep.Failed(Strings.AGENT_UNREACHABLE)
            PairAttempt.PermissionDenied -> PairingStep.Failed(Strings.AGENT_PERMISSION_DENIED)
            is PairAttempt.Done -> when (val outcome = attempt.outcome) {
                is PairOutcome.Paired -> PairingStep.Paired(outcome.pairing.name)
                PairOutcome.Declined -> PairingStep.Failed(Strings.AGENT_PAIR_DECLINED)
                is PairOutcome.Refused -> PairingStep.Failed(
                    when (outcome.code) {
                        AgentErrorCode.PAIRING_EXPIRED -> Strings.AGENT_EXPIRED
                        AgentErrorCode.PAIRING_INVALID -> Strings.AGENT_PAIR_USED
                        AgentErrorCode.PAIRING_REJECTED -> Strings.AGENT_PAIR_REJECTED_BY_PC
                        else -> Strings.AGENT_PAIR_FAILED
                    },
                )
            }
        }
        local.update { it.copy(pairing = step) }
    }

    private fun fail(message: String) = local.update { it.copy(pairing = PairingStep.Failed(message)) }

    companion object {
        /** Access log rows shown (the rest stays until the retention removes it). */
        const val LOG_ROWS = 200
    }
}
