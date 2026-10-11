package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.StorageManager
import kotlin.math.abs
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.StateFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * [AppServices] for iOS. Created by [IosAppGraph]; the shared UI only sees the interface.
 * Playback and [requestTranscription] are main-thread APIs (called from the UI).
 */
class IosAppServices internal constructor(
    override val store: LocalStore,
    override val audioRoot: String,
    override val storage: StorageManager,
    override val status: StateFlow<ProcessingStatus>,
    private val onRequestTranscription: () -> Unit,
    private val onPrepareModel: () -> Unit,
    private val onGapAlertSettingsChanged: () -> Unit,
) : AppServices {
    private var player: AVAudioPlayer? = null

    /** What the last [play] call did, for diagnosing playback in simulator checks. */
    private var lastPlay = "not called"

    override fun requestTranscription() = onRequestTranscription()

    /** The Speech framework's model is installed by the OS (AssetInventory). */
    override val modelSetup: ModelSetup get() = ModelSetup.OS_MANAGED

    /** Asks the OS to install the speech model now; there is no Wi-Fi/mobile choice on iOS. */
    override fun downloadModels(allowMetered: Boolean) = onPrepareModel()

    override val permissions: List<AppPermission> get() = IosPermissions.all

    override suspend fun permissionStatus(permission: AppPermission): PermissionStatus = IosPermissions.status(permission)

    override suspend fun requestPermission(permission: AppPermission): PermissionStatus = IosPermissions.request(permission)

    /** This app's page in the Settings app (notification and speech recognition switches). */
    override fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun gapAlertSettingsChanged() = onGapAlertSettingsChanged()

    @OptIn(ExperimentalForeignApi::class)
    override fun play(audioPath: String, offsetMs: Long) {
        stopPlayback()
        val absolute = if (audioPath.startsWith("/")) audioPath else "$audioRoot/$audioPath"
        if (!NSFileManager.defaultManager.fileExistsAtPath(absolute)) {
            lastPlay = "file missing: $absolute"
            return
        }
        val session = AVAudioSession.sharedInstance()
        val sessionError = nsErrorOf { session.setCategory(AVAudioSessionCategoryPlayback, it) }
            ?: nsErrorOf { session.setActive(true, it) }
        val next = runCatching { AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(absolute), error = null) }
            .getOrNull()
        if (next == null) {
            lastPlay = "AVAudioPlayer cannot open $absolute (session error: $sessionError)"
            return
        }
        val offset = offsetMs.coerceAtLeast(0) / 1000.0
        next.prepareToPlay()
        next.currentTime = offset
        val started = next.play()
        // A seek before play() is not honoured for every file; seek again once it plays.
        if (started && abs(next.currentTime - offset) > 0.5) next.currentTime = offset
        player = next
        lastPlay = "play(offset=${offset}s) started=$started duration=${next.duration}s position=${next.currentTime}s" +
            (sessionError?.let { " session error: $it" } ?: "")
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun nsErrorOf(call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean): String? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (call(error.ptr)) null else (error.value?.localizedDescription ?: "failed")
    }

    override fun stopPlayback() {
        player?.stop()
        player = null
    }

    /** Position of the current playback in ms, or null when nothing is playing (simulator checks, #19). */
    internal fun playingPositionMs(): Long? = player?.takeIf { it.playing }?.let { (it.currentTime * 1000).toLong() }

    /** The last [play] outcome and the player's current state (simulator checks). */
    internal fun playbackDiagnostics(): String =
        "$lastPlay; now " + (player?.let { "playing=${it.playing} position=${it.currentTime}s" } ?: "no player")
}
