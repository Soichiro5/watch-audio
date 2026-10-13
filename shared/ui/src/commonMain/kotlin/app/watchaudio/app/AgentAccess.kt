package app.watchaudio.app

import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent access on this platform (UC11, docs/research/agent-access.md): while the user has it on,
 * the phone connects to the bridge CLI of each paired PC on the local network and answers its
 * agent's tool calls (`shared/agent` `AgentConnector`). Android: a foreground service (#184);
 * iOS: only while the app is in the foreground (#185, [foregroundOnly]).
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

    /** How [scanQr] reads the code; the screen words its button and note after it. */
    val qrScanner: QrScanner get() = QrScanner.CAMERA_APP_PHOTO

    /**
     * Access lasts only while the app is on screen (iOS, #185): leaving the app turns it off, and
     * the screen says so. False where it keeps answering in the background (Android's foreground service).
     */
    val foregroundOnly: Boolean get() = false

    /** Where the user allows the app onto the local network, for the message when it is refused. */
    val localNetworkSetting: LocalNetworkSetting get() = LocalNetworkSetting.NEARBY_DEVICES

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
    /** While on, the system refused the local network (iOS), so no pairing can connect. */
    val localNetworkDenied: Boolean = false,
) {
    val isOn: Boolean get() = offAt != null
}

/** How [AgentAccess.scanQr] reads the QR code. */
enum class QrScanner {
    /** A photo taken by the system camera app, decoded on the device (Android, #184). */
    CAMERA_APP_PHOTO,

    /** The app's own live camera view, asking for the camera permission when scanning (iOS, #185). */
    LIVE_CAMERA,
}

/** Where the user allows local network access ([AgentAccess.localNetworkSetting]). */
enum class LocalNetworkSetting {
    /** Android 17+: the app's 「付近のデバイス」 permission. */
    NEARBY_DEVICES,

    /** iOS: Settings → Privacy & Security → Local Network. */
    LOCAL_NETWORK,
}

/** Result of [AgentAccess.scanQr]. */
sealed interface QrScan {
    data class Found(val text: String) : QrScan

    /** The user closed the scanner. */
    data object Cancelled : QrScan

    /** A picture was taken, but no QR code could be read from it. */
    data object NotFound : QrScan

    /** No scanner on this device (e.g. no camera app, or the camera permission was refused). */
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

    /** The phone's key could not be prepared (e.g. the Keychain refused); nothing turned on. */
    FAILED,
}
