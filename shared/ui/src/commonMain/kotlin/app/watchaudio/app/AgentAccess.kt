package app.watchaudio.app

import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent access on this platform (UC11, docs/research/agent-access.md): while the user has it on,
 * the phone connects to the bridge CLI of each paired PC on the local network and answers its
 * agent's tool calls (`shared/agent` `AgentConnector`). Android: a foreground service (#184);
 * iOS: only while the app is in the foreground (#185).
 *
 * Pairings, their scope and the access log live in [AppServices.store] (`AgentQueries`); the
 * shared UI changes them there and the platform's connection follows (a revoked pairing is
 * dropped at once).
 */
interface AgentAccess {
    /** On/off and the connection of each active pairing. */
    val state: StateFlow<AgentAccessState>

    /** The platform can read the PC's QR code; otherwise only the typed pairing string is offered. */
    val canScanQr: Boolean

    /** Reads a QR code (only ever from the user's tap). */
    suspend fun scanQr(): QrScan

    /**
     * Pairs with the bridge of [uri] (the user's tap): connects to one of its LAN addresses,
     * shows the 6-digit code through [confirm] and stores the pairing once both sides confirmed.
     */
    suspend fun pair(uri: PairingUri, confirm: suspend (code: String) -> Boolean): PairAttempt

    /** Turns agent access on for [minutes] from now (the user's tap); it then turns itself off. */
    suspend fun turnOn(minutes: Int): TurnOnResult

    /** Turns agent access off now: every connection ends. */
    fun turnOff()
}

/** [AgentAccess.state]. */
data class AgentAccessState(
    /** When access turns itself off (epoch ms), or null while it is off. */
    val offAt: Long? = null,
    /** Active pairings by id and their connection, while on. */
    val links: Map<Long, LinkState> = emptyMap(),
) {
    val isOn: Boolean get() = offAt != null
}

/** Result of [AgentAccess.scanQr]. */
sealed interface QrScan {
    data class Found(val text: String) : QrScan

    /** The user closed the scanner. */
    data object Cancelled : QrScan

    /** A picture was taken, but no QR code could be read from it. */
    data object NotFound : QrScan

    /** No scanner on this device (e.g. no camera app). */
    data object Unavailable : QrScan
}

/** Result of [AgentAccess.pair]. */
sealed interface PairAttempt {
    /** The bridge answered; how pairing ended. */
    data class Done(val outcome: PairOutcome) : PairAttempt

    /** None of the QR's addresses answered (another network, a firewall, the CLI stopped). */
    data object Unreachable : PairAttempt

    /** The user refused the local network permission (Android 17+, iOS). */
    data object PermissionDenied : PairAttempt
}

enum class TurnOnResult {
    ON,

    /** The user refused the local network (or notification) permission it needs. */
    PERMISSION_DENIED,

    /** Nothing to connect to: no active pairing. */
    NO_PAIRINGS,
}
