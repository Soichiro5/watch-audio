package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path

/**
 * [AppServices] over a real [LocalStore] with no platform behind it: playback, transcription
 * and model downloads are only recorded, while [storage] really deletes (audio under [audioRoot]).
 * For tests, previews and as a stand-in host until a platform implementation is wired in.
 */
class FakeAppServices(
    override val store: LocalStore,
    override val audioRoot: String = "",
    initialStatus: ProcessingStatus = ProcessingStatus(),
    override val permissions: List<AppPermission> = emptyList(),
    override val modelSetup: ModelSetup = ModelSetup.DOWNLOAD,
    override val storage: StorageManager = StorageManager(store, Path(audioRoot)),
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
    var appSettingsOpened = 0
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

    override fun openAppSettings() {
        appSettingsOpened++
    }

    override fun gapAlertSettingsChanged() {
        gapAlertSettingsChanges++
    }

    /** What [exportDiagnostics] answers; set [diagnosticsExportFailure] to make it throw instead. */
    var diagnosticsExportResult = true
    var diagnosticsExportFailure: Exception? = null
    var diagnosticsExports = 0
        private set

    override suspend fun exportDiagnostics(): Boolean {
        diagnosticsExports++
        diagnosticsExportFailure?.let { throw it }
        return diagnosticsExportResult
    }

    companion object {
        /** Opens (or creates) the regular database in [dataDir]. */
        fun open(dataDir: String): FakeAppServices =
            FakeAppServices(LocalStore.open("$dataDir/${LocalStore.FILE_NAME}"), audioRoot = dataDir)
    }
}
