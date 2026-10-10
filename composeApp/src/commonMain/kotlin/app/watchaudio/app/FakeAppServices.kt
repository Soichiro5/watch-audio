package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AppServices] over a real [LocalStore] with no platform behind it: playback, transcription
 * and model downloads are only recorded. For tests, previews and as a stand-in host until a
 * platform implementation is wired in.
 */
class FakeAppServices(
    override val store: LocalStore,
    override val audioRoot: String = "",
    initialStatus: ProcessingStatus = ProcessingStatus(),
    override val permissions: List<AppPermission> = emptyList(),
    override val modelSetup: ModelSetup = ModelSetup.DOWNLOAD,
) : AppServices {
    val mutableStatus = MutableStateFlow(initialStatus)
    override val status: StateFlow<ProcessingStatus> = mutableStatus.asStateFlow()

    val played = mutableListOf<Pair<String, Long>>()
    var stopRequests = 0
        private set
    var transcriptionRequests = 0
        private set
    val downloadRequests = mutableListOf<Boolean>()

    /** Current permission states; [requestPermission] grants unless [deniesOnRequest]. */
    val permissionStates = permissions.associateWith { PermissionStatus.NOT_REQUESTED }.toMutableMap()
    val permissionRequests = mutableListOf<AppPermission>()
    var deniesOnRequest = false
    var gapAlertSettingsChanges = 0
        private set

    override fun requestTranscription() {
        transcriptionRequests++
    }

    override fun play(audioPath: String, offsetMs: Long) {
        played += audioPath to offsetMs
    }

    override fun stopPlayback() {
        stopRequests++
    }

    override fun downloadModels(allowMetered: Boolean) {
        downloadRequests += allowMetered
    }

    override suspend fun permissionStatus(permission: AppPermission): PermissionStatus =
        permissionStates[permission] ?: PermissionStatus.GRANTED

    override suspend fun requestPermission(permission: AppPermission): PermissionStatus {
        permissionRequests += permission
        if (permissionStates[permission] == PermissionStatus.NOT_REQUESTED) {
            permissionStates[permission] = if (deniesOnRequest) PermissionStatus.DENIED else PermissionStatus.GRANTED
        }
        return permissionStatus(permission)
    }

    override fun gapAlertSettingsChanged() {
        gapAlertSettingsChanges++
    }

    companion object {
        /** Opens (or creates) the regular database in [dataDir]. */
        fun open(dataDir: String): FakeAppServices =
            FakeAppServices(LocalStore.open("$dataDir/${LocalStore.FILE_NAME}"), audioRoot = dataDir)
    }
}
