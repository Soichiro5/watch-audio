package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.platform.IoDispatcher
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

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
    private val diagnostics: DiagnosticsLog,
) : AppServices {
    private var player: AVAudioPlayer? = null

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

    /**
     * Copies the diagnostics log to the temporary directory (replacing earlier exports) and
     * presents the share sheet; the user picks the destination. Main thread (called from the UI).
     */
    override suspend fun exportDiagnostics(): Boolean {
        val target = withContext(IoDispatcher) {
            val dir = Path(NSTemporaryDirectory(), "diagnostics-export")
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            val stamp = Clock.System.now().toString().substringBefore('.').removeSuffix("Z").replace(":", "")
            Path(dir, "watch-audio-diagnostics-$stamp.csv").takeIf { diagnostics.exportTo(it) > 0 }
        } ?: return false
        val presenter = topViewController() ?: error("no visible screen to present the share sheet from")
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(target.toString())), applicationActivities = null)
        // Required on iPad, where the sheet is a popover.
        sheet.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(sheet, animated = true, completion = null)
        return true
    }

    private fun topViewController(): UIViewController? {
        val root = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstNotNullOfOrNull { it.rootViewController }
        var top = root
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun play(audioPath: String, offsetMs: Long) {
        stopPlayback()
        val absolute = if (audioPath.startsWith("/")) audioPath else "$audioRoot/$audioPath"
        if (!NSFileManager.defaultManager.fileExistsAtPath(absolute)) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        val next = runCatching { AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(absolute), error = null) }
            .getOrNull() ?: return
        next.prepareToPlay()
        next.currentTime = offsetMs.coerceAtLeast(0) / 1000.0
        next.play()
        player = next
    }

    override fun stopPlayback() {
        player?.stop()
        player = null
    }
}
