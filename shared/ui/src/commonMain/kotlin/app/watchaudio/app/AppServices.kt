package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StorageManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.RawSink
import kotlinx.io.files.Path

/**
 * What the shared UI needs from each phone platform. Implemented by apps/android
 * (`AndroidAppServices`) and by the iOS host (`IosAppServices` in shared/ui/iosMain).
 */
interface AppServices {
    /** The local database (everything stays on the device). */
    val store: LocalStore

    /** Absolute path of the audio archive root; chunk `audioPath`s are relative to it. */
    val audioRoot: String

    /** Retention setting, usage breakdown and deletion (#33), over [store] and [audioRoot]. */
    val storage: StorageManager

    val status: StateFlow<ProcessingStatus>

    /** Ask the platform to run transcription soon (e.g. enqueue background work). */
    fun requestTranscription()

    /** Start playback of an archived chunk at [offsetMs]. */
    fun play(audioPath: String, offsetMs: Long)

    fun stopPlayback()

    /**
     * Start (or resume) downloading the ASR model; progress is reported through [status] as
     * [app.watchaudio.core.pipeline.ModelState.Downloading]. Platforms whose model is managed by the OS keep the no-op.
     * @param allowMetered also download over mobile data, not only Wi-Fi.
     */
    fun downloadModels(allowMetered: Boolean) {}

    /** How the platform obtains its speech model (decides what the onboarding offers). */
    val modelSetup: ModelSetup get() = ModelSetup.DOWNLOAD

    /** Runtime permissions the onboarding asks for, in order; empty when none are needed. */
    val permissions: List<AppPermission> get() = emptyList()

    suspend fun permissionStatus(permission: AppPermission): PermissionStatus = PermissionStatus.GRANTED

    /** Shows the system prompt if the platform still can, and returns the resulting status. */
    suspend fun requestPermission(permission: AppPermission): PermissionStatus = permissionStatus(permission)

    /**
     * Opens this app's page in the system settings, where a refused permission
     * ([PermissionStatus.DENIED]) can still be granted. The UI re-reads the permissions on resume.
     */
    fun openAppSettings() {}

    /**
     * The gap alert preferences (`AppSettings.gapAlert`) were changed in the UI; re-evaluate any
     * scheduled alert.
     */
    fun gapAlertSettingsChanged() {}

    /**
     * The transcription preferences (`AppSettings.transcribeOnlyWhileCharging`, #75) were changed in
     * the UI; re-plan the scheduled transcription work.
     */
    fun transcriptionSettingsChanged() {}

    /**
     * Writes the on-device diagnostics log (`DiagnosticsLog`: event times, ids, durations and
     * error types; never audio or transcript text) to one file and opens the system share sheet,
     * where the user picks where it goes. Only ever called from the user's tap.
     * @return false if there is nothing to export (or the platform keeps no log).
     */
    suspend fun exportDiagnostics(): Boolean = false

    /** Writes the user's data export (#155) from [store] and the audio under [audioRoot]. */
    val exporter: DataExporter get() = DataExporter(store, Path(audioRoot))

    /**
     * Asks the user where to save the data export (#155) named [fileName] and writes it there with
     * [write]. Only ever called from the user's tap; the app never sends the file anywhere itself:
     * - Android: the system's save dialog (SAF `ACTION_CREATE_DOCUMENT`); [write] streams straight
     *   into the document the user created.
     * - iOS: [write] fills a file in the app's temporary directory, then the share sheet opens
     *   (「ファイルに保存」 and the other destinations the user may pick); the file is deleted when
     *   the sheet closes.
     *
     * @return false if the user cancelled before anything was written (or the platform cannot save).
     * @throws Exception from [write] or the destination; a partly written document is removed.
     */
    suspend fun saveExport(fileName: String, mimeType: String, write: suspend (RawSink) -> Unit): Boolean = false

    /**
     * The on-device diagnostics log, for failures the UI itself sees (error types only, never
     * messages or user data). [Diagnostics.NONE] where the platform keeps no log.
     */
    val diagnostics: Diagnostics get() = Diagnostics.NONE
}

enum class ModelSetup {
    /** The app downloads the model itself; the user picks Wi-Fi only or mobile data too. */
    DOWNLOAD,

    /** The OS installs and updates the model ([AppServices.downloadModels] asks it to). */
    OS_MANAGED,
}

enum class AppPermission {
    /** Local notifications, e.g. the "recording is not arriving" alert. */
    NOTIFICATIONS,

    /** On-device speech recognition (iOS Speech framework). */
    SPEECH_RECOGNITION,
}

enum class PermissionStatus {
    GRANTED,

    /** Not granted yet; [AppServices.requestPermission] can still show the system prompt. */
    NOT_REQUESTED,

    /** Refused; it can only be changed in the system settings. */
    DENIED,
}
