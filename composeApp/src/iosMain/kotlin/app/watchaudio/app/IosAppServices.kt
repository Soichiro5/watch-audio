package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.StorageManager
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.StateFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

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

    override fun requestTranscription() = onRequestTranscription()

    /** The Speech framework's model is installed by the OS (AssetInventory). */
    override val modelSetup: ModelSetup get() = ModelSetup.OS_MANAGED

    /** Asks the OS to install the speech model now; there is no Wi-Fi/mobile choice on iOS. */
    override fun downloadModels(allowMetered: Boolean) = onPrepareModel()

    override val permissions: List<AppPermission> get() = IosPermissions.all

    override suspend fun permissionStatus(permission: AppPermission): PermissionStatus = IosPermissions.status(permission)

    override suspend fun requestPermission(permission: AppPermission): PermissionStatus = IosPermissions.request(permission)

    override fun gapAlertSettingsChanged() = onGapAlertSettingsChanged()

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
