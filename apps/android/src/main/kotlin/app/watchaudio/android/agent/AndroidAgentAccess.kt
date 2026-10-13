package app.watchaudio.android.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.watchaudio.android.AndroidAppServices
import app.watchaudio.app.AgentAccess
import app.watchaudio.app.AgentAccessState
import app.watchaudio.app.PairAttempt
import app.watchaudio.app.QrScan
import app.watchaudio.app.TurnOnResult
import app.watchaudio.core.agent.AgentConnector
import app.watchaudio.core.agent.AgentService
import app.watchaudio.core.agent.BridgeUnreachableException
import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.pairOver
import app.watchaudio.core.db.LocalStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Agent access on Android (#184): pairing through [TlsAgentTransport], the on/off state with its
 * auto-off deadline, and the connection itself in [AgentAccessService] (a foreground service with
 * a notification, so the PC's agent is answered with the screen off). Off by default and after
 * every process start: it only turns on from the user's tap.
 *
 * The QR code is read from a photo taken by the system camera app (`ACTION_IMAGE_CAPTURE`, so the
 * app holds no CAMERA permission) and decoded on the device ([QrDecoder]); the photo is deleted at
 * once. On Android 17+ the local network permission is asked for before connecting.
 */
class AndroidAgentAccess(
    context: Context,
    private val store: LocalStore,
    private val service: AgentService,
    /** `watch-audio-android/<version>`, sent to the bridge in the hello. */
    private val software: String,
    private val now: () -> Long = System::currentTimeMillis,
) : AgentAccess {
    private val app = context.applicationContext
    private val discovery = NsdBridgeDiscovery(app)
    private val mutableState = MutableStateFlow(AgentAccessState())
    override val state: StateFlow<AgentAccessState> = mutableState.asStateFlow()

    @Volatile
    private var transport: TlsAgentTransport? = null

    /** The transport, creating the Keystore key on first use. */
    suspend fun transport(): TlsAgentTransport = transport ?: withContext(Dispatchers.IO) {
        synchronized(this@AndroidAgentAccess) {
            transport ?: TlsAgentTransport(AgentIdentity.loadOrCreate(app), Build.MODEL ?: "Android", discovery::advertisements).also { transport = it }
        }
    }

    override val canScanQr: Boolean get() = true

    // The camera app and the permission prompt go through the visible activity; main thread only.
    private var pictureLauncher: ActivityResultLauncher<Uri>? = null
    private var pendingPicture: CompletableDeferred<Boolean>? = null
    private var permissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    fun attachLaunchers(picture: ActivityResultLauncher<Uri>, permission: ActivityResultLauncher<String>) {
        pictureLauncher = picture
        permissionLauncher = permission
    }

    fun detachLaunchers(picture: ActivityResultLauncher<Uri>, permission: ActivityResultLauncher<String>) {
        if (pictureLauncher === picture) pictureLauncher = null
        if (permissionLauncher === permission) permissionLauncher = null
    }

    /** Result of the camera app started by [scanQr]: true if a picture was saved. */
    fun onPictureTaken(saved: Boolean) {
        pendingPicture?.complete(saved)
        pendingPicture = null
    }

    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override suspend fun scanQr(): QrScan {
        val file = File(File(app.cacheDir, QR_DIR).apply { mkdirs() }, "pairing.jpg")
        try {
            val taken = withContext(Dispatchers.Main) {
                val launcher = pictureLauncher ?: return@withContext null
                val uri = FileProvider.getUriForFile(app, "${app.packageName}${AndroidAppServices.DIAGNOSTICS_AUTHORITY_SUFFIX}", file)
                val result = CompletableDeferred<Boolean>()
                pendingPicture?.complete(false)
                pendingPicture = result
                try {
                    launcher.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    pendingPicture = null
                    return@withContext null
                }
                result
            }?.await() ?: return QrScan.Unavailable
            if (!taken || !file.isFile || file.length() == 0L) return QrScan.Cancelled
            val text = withContext(Dispatchers.Default) { decodePhoto(file) }
            return if (text != null) QrScan.Found(text) else QrScan.NotFound
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    override suspend fun pair(uri: PairingUri, confirm: suspend (code: String) -> Boolean): PairAttempt {
        if (!localNetworkAllowed()) return PairAttempt.PermissionDenied
        return try {
            PairAttempt.Done(pairOver(transport(), store, uri, now, confirm))
        } catch (e: BridgeUnreachableException) {
            Log.i(TAG, "pairing: no address answered (${e.cause?.javaClass?.simpleName})")
            PairAttempt.Unreachable
        }
    }

    override suspend fun turnOn(minutes: Int): TurnOnResult {
        if (store.read { agentPairings() }.none { it.isActive }) return TurnOnResult.NO_PAIRINGS
        if (!localNetworkAllowed()) return TurnOnResult.PERMISSION_DENIED
        transport() // the Keystore key, before the service needs it
        session++
        mutableState.update { it.copy(offAt = now() + minutes * 60_000L) }
        AgentAccessService.start(app)
        return TurnOnResult.ON
    }

    override fun turnOff() {
        session++
        mutableState.value = AgentAccessState()
        AgentAccessService.stop(app)
    }

    /** Counts turn-ons and offs, so that a service stopping late cannot turn off a newer session. */
    @Volatile
    var session = 0
        private set

    /** When access turns itself off, or null while off. */
    val offAt: Long? get() = mutableState.value.offAt

    /** The connection loop the service runs; it ends serving once the deadline passed. */
    fun connector(transport: TlsAgentTransport): AgentConnector =
        AgentConnector(store, service, transport, software, allowed = { offAt?.let { now() < it } == true })

    /** The service reports each pairing's connection. */
    fun publishLinks(links: Map<Long, LinkState>) = mutableState.update { if (it.offAt != null) it.copy(links = links) else it }

    /** The service of [session] stopped (auto-off, the notification's button, or the system). */
    fun markOff(session: Int) {
        if (session == this.session) mutableState.value = AgentAccessState()
    }

    /** Android 17+ asks the user before an app reaches devices on the local network. */
    private suspend fun localNetworkAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17) return true
        if (granted()) return true
        val result = withContext(Dispatchers.Main) {
            val launcher = permissionLauncher ?: return@withContext null
            CompletableDeferred<Boolean>().also { result ->
                pendingPermission?.complete(false)
                pendingPermission = result
                launcher.launch(LOCAL_NETWORK_PERMISSION)
            }
        } ?: return false
        return result.await() || granted()
    }

    private fun granted() = ContextCompat.checkSelfPermission(app, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Decodes the photo at a size ZXing handles well (the long side at most ~2000 px, then half). */
    private fun decodePhoto(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2
        for (attempt in 0 until 2) {
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample shl attempt }) ?: return null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val (width, height) = bitmap.width to bitmap.height
            bitmap.recycle()
            QrDecoder.decode(pixels, width, height)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "AndroidAgentAccess"

        /** Cache subdirectory of the photo; must match `res/xml/diagnostics_paths.xml`. */
        const val QR_DIR = "qr-scan"

        private const val MAX_DECODE_SIDE = 2048

        /** Android 17 (API 37): the local network permission. */
        private const val ANDROID_17 = 37
        /** Only asked for on [ANDROID_17] and later. */
        @SuppressLint("InlinedApi")
        private const val LOCAL_NETWORK_PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}
