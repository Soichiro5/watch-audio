package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.platform.IoDispatcher
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError
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
    override val diagnostics: DiagnosticsLog,
    private val onTranscriptionSettingsChanged: () -> Unit = {},
) : AppServices {
    private var player: AVAudioPlayer? = null

    /** Absolute path of the file in [player]. */
    private var playerPath: String? = null

    /** Whether `AVAudioPlayer.play()` returned true for the current [player]. */
    private var playerStarted = false

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

    override fun transcriptionSettingsChanged() = onTranscriptionSettingsChanged()

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

    /**
     * Writes the data export (#155) to the temporary directory (`tmp/data-export/`, replacing earlier
     * exports), then presents the share sheet, where the user picks 「ファイルに保存」 or another
     * destination. The file is deleted when the sheet closes. Main thread (called from the UI).
     */
    override suspend fun saveExport(fileName: String, mimeType: String, write: suspend (RawSink) -> Unit): Boolean {
        val target = withContext(IoDispatcher) {
            val dir = Path(NSTemporaryDirectory(), "data-export")
            SystemFileSystem.createDirectories(dir)
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            Path(dir, fileName)
        }
        try {
            withContext(IoDispatcher) { SystemFileSystem.sink(target).use { write(it) } }
            val presenter = topViewController() ?: error("no visible screen to present the share sheet from")
            val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(target.toString())), applicationActivities = null)
            // Required on iPad, where the sheet is a popover.
            sheet.popoverPresentationController?.sourceView = presenter.view
            // The destination has its copy once the sheet closes (saved, sent or cancelled).
            sheet.completionWithItemsHandler = { _, _, _, _ -> SystemFileSystem.delete(target, mustExist = false) }
            presenter.presentViewController(sheet, animated = true, completion = null)
        } catch (e: Throwable) {
            withContext(IoDispatcher + NonCancellable) { SystemFileSystem.delete(target, mustExist = false) }
            throw e
        }
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
        playerPath = absolute
        playerStarted = started
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
        playerPath = null
        playerStarted = false
    }

    /**
     * Position of the current playback in ms, or -1 when nothing is playing. Read by the
     * app-hosted XCTest (`apps/ios/Tests/PlaybackTests`, #19), whose process can actually start the player.
     */
    val playbackPositionMs: Long
        get() = player?.takeIf { it.playing }?.let { (it.currentTime * 1000).toLong() } ?: -1

    /** The last [play] outcome and the player's current state (simulator checks). */
    fun playbackDiagnostics(): String =
        "$lastPlay; now " + (player?.let { "playing=${it.playing} position=${it.currentTime}s" } ?: "no player")

    /**
     * The player [play] prepared, whether or not it could start: in a process that is not an app
     * (the Kotlin/Native test executable) `play()` returns false, but the player is opened and seeked.
     */
    internal fun preparedPlayback(): PreparedPlayback? {
        val current = player ?: return null
        return PreparedPlayback(
            absolutePath = playerPath ?: return null,
            positionMs = (current.currentTime * 1000).toLong(),
            durationMs = (current.duration * 1000).toLong(),
            started = playerStarted,
            playing = current.playing,
        )
    }
}

/** State of the player prepared by [IosAppServices.play] (simulator checks). */
internal data class PreparedPlayback(
    val absolutePath: String,
    val positionMs: Long,
    val durationMs: Long,
    val started: Boolean,
    val playing: Boolean,
)
