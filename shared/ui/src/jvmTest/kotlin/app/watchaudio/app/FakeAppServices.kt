package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path

/**
 * [AppServices] over a real [LocalStore] with no platform behind it: playback, transcription
 * and model downloads are only recorded, while [storage] really deletes (audio under [audioRoot]).
 * Test double for the JVM view model and UI tests; it is not shipped in the apps.
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
    var transcriptionSettingsChanges = 0
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

    override fun transcriptionSettingsChanged() {
        transcriptionSettingsChanges++
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

    /** What the shared UI wrote to [diagnostics], as `event detail` lines. */
    val diagnosticsLines = mutableListOf<String>()
    override val diagnostics: Diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            diagnosticsLines += "$event $detail"
        }
    }
}
