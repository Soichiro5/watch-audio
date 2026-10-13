package app.watchaudio.app

import app.watchaudio.core.agent.AgentConnector
import app.watchaudio.core.agent.AgentService
import app.watchaudio.core.agent.AudioClipSource
import app.watchaudio.core.agent.BridgeUnreachableException
import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.pairOver
import app.watchaudio.core.db.LocalStore
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.posix.memcpy

/**
 * Agent access on iOS (#185): pairing through [IosAgentTransport], the on/off state with its
 * auto-off deadline, and the connection itself ([AgentConnector]) **only while the app is on
 * screen**. iOS has no foreground service: an app in the background is suspended within seconds
 * and cannot keep a connection, so leaving the app ([setAppActive] false) turns access off (the
 * bridge gets `bye(access_off)`); it stays off when the user comes back. The auto-off deadline
 * works as on Android (a timer, and [AgentConnector]'s `allowed` refuses calls after it).
 *
 * The QR code is read with the app's own camera view (AVFoundation's QR detector, on the device),
 * asking for the camera permission only then. The first connection to the local network makes iOS
 * ask for the Local Network permission; a refusal shows as [AgentAccessState.localNetworkDenied]
 * (or [PairAttempt.PermissionDenied] while pairing).
 *
 * Main thread for the calls from the UI; the connection runs on [Dispatchers.Default].
 */
class IosAgentAccess(
    private val store: LocalStore,
    private val service: AgentService,
    private val platform: IosAgentPlatform,
    /** `watch-audio-ios/<version>`, sent to the bridge in the hello. */
    private val software: String,
    /** The name given to the PC while pairing: the model ("iPhone"), never the user's device name. */
    private val phoneName: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AgentAccess {
    private val mutableState = MutableStateFlow(AgentAccessState())
    override val state: StateFlow<AgentAccessState> = mutableState.asStateFlow()

    override val canScanQr: Boolean get() = true
    override val qrScanner: QrScanner get() = QrScanner.LIVE_CAMERA
    override val foregroundOnly: Boolean get() = true
    override val localNetworkSetting: LocalNetworkSetting get() = LocalNetworkSetting.LOCAL_NETWORK

    private val transportLock = Mutex()
    private var transport: IosAgentTransport? = null

    /** The running connection of the current session, or null while off. Main thread. */
    private var session: Job? = null

    /** Counts turn-ons and offs, so that a late auto-off cannot turn off a newer session. */
    @Volatile
    private var generation = 0

    /** Whether the app is on screen; access can only be turned on then. */
    @Volatile
    private var appActive = true

    /** The transport, creating the Keychain key and certificate on first use. */
    suspend fun transport(): IosAgentTransport = transportLock.withLock {
        transport ?: IosAgentTransport.load(platform, phoneName).also { created ->
            created.onLocalNetworkDenied = {
                mutableState.update { if (it.isOn) it.copy(localNetworkDenied = true) else it }
            }
            transport = created
        }
    }

    override suspend fun scanQr(): QrScan = withContext(Dispatchers.Main) {
        val result = suspendCancellableCoroutine { continuation ->
            platform.scanQr { result -> if (continuation.isActive) continuation.resume(result) }
        }
        when {
            result.text != null -> QrScan.Found(result.text)
            result.unavailable -> QrScan.Unavailable
            else -> QrScan.Cancelled
        }
    }

    override suspend fun pair(uri: PairingUri, confirm: suspend (code: String) -> Boolean): PairAttempt = try {
        PairAttempt.Done(pairOver(transport(), store, uri, now, confirm))
    } catch (e: BridgeUnreachableException) {
        if (e.cause is LocalNetworkDeniedException) PairAttempt.PermissionDenied else PairAttempt.Unreachable
    }

    override suspend fun turnOn(minutes: Int): TurnOnResult {
        if (store.read { agentPairings() }.none { it.isActive }) return TurnOnResult.NO_PAIRINGS
        val transport = try {
            transport()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TurnOnResult.FAILED
        }
        return withContext(Dispatchers.Main) {
            if (!appActive) return@withContext TurnOnResult.FAILED
            stop()
            val current = ++generation
            val offAt = now() + minutes * 60_000L
            mutableState.value = AgentAccessState(offAt = offAt)
            val connector = AgentConnector(store, service, transport, software, allowed = { generation == current && now() < offAt })
            session = scope.launch(Dispatchers.Default) {
                launch {
                    connector.links.collect { links ->
                        mutableState.update { state ->
                            if (generation != current || !state.isOn) {
                                state
                            } else {
                                val connected = links.values.any { it == LinkState.CONNECTED }
                                state.copy(links = links, localNetworkDenied = state.localNetworkDenied && !connected)
                            }
                        }
                    }
                }
                // Auto-off (the connector also refuses calls after the deadline if this timer is late).
                launch {
                    delay((offAt - now()).coerceAtLeast(0))
                    withContext(Dispatchers.Main) { if (generation == current) turnOff() }
                }
                try {
                    connector.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failure turns access off rather than leaving a switch that is on but serves nothing.
                    withContext(Dispatchers.Main) { if (generation == current) turnOff() }
                }
            }
            TurnOnResult.ON
        }
    }

    override fun turnOff() {
        generation++
        stop()
        mutableState.value = AgentAccessState()
    }

    /** Cancels the session (the connector says `bye` to each bridge and closes); returns its job. */
    private fun stop(): Job? {
        val job = session ?: return null
        session = null
        job.cancel()
        return job
    }

    /**
     * Called by [IosAppGraph] from the scene phase (main thread). Leaving the screen turns access
     * off; a background task keeps the app awake while the `bye` messages go out.
     */
    fun setAppActive(active: Boolean) {
        appActive = active
        if (active || !state.value.isOn) return
        val application = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        fun end() {
            if (task != UIBackgroundTaskInvalid) {
                application.endBackgroundTask(task)
                task = UIBackgroundTaskInvalid
            }
        }
        task = application.beginBackgroundTaskWithName("agent-access-off") { end() }
        generation++
        val job = stop()
        mutableState.value = AgentAccessState()
        if (job == null) end() else job.invokeOnCompletion { scope.launch(Dispatchers.Main) { end() } }
    }
}

/**
 * Audio for `get_audio_clip` (#185), only for pairings the user allowed audio: the chunk's AAC file
 * decoded by AVFoundation (through [platform]) into 16 kHz mono, at most the asked-for part.
 */
class IosAudioClipSource(private val platform: IosAgentPlatform, private val audioRoot: String) : AudioClipSource {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun read(audioPath: String, offsetMs: Long, durationMs: Long): FloatArray {
        val result = suspendCancellableCoroutine { continuation ->
            platform.readAudio("$audioRoot/$audioPath", offsetMs, durationMs) { result -> if (continuation.isActive) continuation.resume(result) }
        }
        val data = result.samples ?: throw IllegalStateException("audio: ${result.error}")
        val samples = FloatArray((data.length / 4u).toInt())
        if (samples.isNotEmpty()) samples.usePinned { memcpy(it.addressOf(0), data.bytes, samples.size.toULong() * 4u) }
        return samples
    }
}
