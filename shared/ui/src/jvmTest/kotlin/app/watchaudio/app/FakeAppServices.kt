package app.watchaudio.app

import app.watchaudio.app.licenses.NoticeApp
import app.watchaudio.core.agent.AgentLimits
import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.backup.BackupRestorer
import app.watchaudio.core.backup.BackupWriter
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.search.SemanticSearch
import app.watchaudio.core.search.SemanticStatus
import app.watchaudio.core.search.VectorHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

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
    override val supportsEnglish: Boolean = false,
    override val exporter: DataExporter = DataExporter(store, Path(audioRoot)),
    // Few PBKDF2 rounds keep the tests fast; the format records the count.
    override val backupWriter: BackupWriter = BackupWriter(store, Path(audioRoot), iterations = 1_000),
    override val backupRestorer: BackupRestorer = BackupRestorer(store, Path(audioRoot)),
    override val noticeApps: Set<NoticeApp> = NoticeApp.entries.toSet(),
    override val semanticSearch: SemanticSearch? = null,
    override val agentAccess: AgentAccess? = null,
) : AppServices {
    val mutableStatus = MutableStateFlow(initialStatus)
    override val status: StateFlow<ProcessingStatus> = mutableStatus.asStateFlow()

    val played = mutableListOf<Pair<String, Long>>()
    var stopRequests = 0
        private set
    var transcriptionRequests = 0
        private set
    val downloadRequests = mutableListOf<Boolean>()
    val englishDownloadRequests = mutableListOf<Boolean>()

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

    override fun downloadEnglishModel(allowMetered: Boolean) {
        englishDownloadRequests += allowMetered
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

    /** File names [saveExport] was asked for; the user cancels if [exportCancelled]. */
    val exportRequests = mutableListOf<String>()
    var exportCancelled = false

    /** Makes the destination fail after the user picked it (e.g. it is full). */
    var exportDestinationFailure: Exception? = null

    /** The user closes the iOS share sheet without picking a destination, after the file was written. */
    var exportDismissedAfterWrite = false

    /** What was "saved": file name to the bytes the shared code wrote. */
    val savedExports = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun saveExport(fileName: String, mimeType: String, write: suspend (RawSink) -> Unit): Boolean {
        exportRequests += fileName
        if (exportCancelled) return false
        exportDestinationFailure?.let { throw it }
        val buffer = Buffer()
        write(buffer)
        if (exportDismissedAfterWrite) return false
        savedExports += fileName to buffer.readByteArray()
        return true
    }

    /** The file the user "picks" in [openBackup]; null: they cancel. */
    var backupToOpen: ByteArray? = null
    var backupOpenRequests = 0
        private set

    override suspend fun openBackup(read: suspend (open: () -> RawSource) -> Unit): Boolean {
        backupOpenRequests++
        val bytes = backupToOpen ?: return false
        read { Buffer().apply { write(bytes) } }
        return true
    }

    /** What the shared UI wrote to [diagnostics], as `event detail` lines. */
    val diagnosticsLines = mutableListOf<String>()
    override val diagnostics: Diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            diagnosticsLines += "$event $detail"
        }
    }
}

/** A [SemanticSearch] that answers every query with [hits] (passage ids stored by the test) and reports [status]. */
class FakeSemanticSearch(
    var status: SemanticStatus = SemanticStatus.Ready,
    var hits: List<VectorHit> = emptyList(),
) : SemanticSearch {
    override val modelId = "fake-model"
    val queries = mutableListOf<String>()

    override suspend fun status(): SemanticStatus = status

    override suspend fun search(query: String, limit: Int): List<VectorHit> {
        queries += query
        return hits.take(limit)
    }
}

/**
 * [AgentAccess] without a network: pairing shows [code] and stores the pairing like the real
 * `AgentLink.pair` once confirmed; turning on marks every active pairing connected.
 */
class FakeAgentAccess(
    private val store: LocalStore,
    private val now: () -> Long,
    override val canScanQr: Boolean = true,
    var code: String = "123456",
    override val qrScanner: QrScanner = QrScanner.CAMERA_APP_PHOTO,
    override val foregroundOnly: Boolean = false,
    override val localNetworkSetting: LocalNetworkSetting = LocalNetworkSetting.NEARBY_DEVICES,
) : AgentAccess {
    val mutableState = MutableStateFlow(AgentAccessState())
    override val state: StateFlow<AgentAccessState> = mutableState.asStateFlow()

    /** What [scanQr] answers. */
    var scan: QrScan = QrScan.Cancelled
    var scans = 0
        private set

    /** Set to answer [pair] without the code (e.g. [PairAttempt.Unreachable]). */
    var pairAnswer: PairAttempt? = null
    val pairedUris = mutableListOf<PairingUri>()
    val turnOnRequests = mutableListOf<Int>()
    var turnOffs = 0
        private set

    /** Answer of [turnOn] other than the normal one, e.g. [TurnOnResult.PERMISSION_DENIED]. */
    var turnOnAnswer: TurnOnResult? = null

    override suspend fun scanQr(): QrScan {
        scans++
        return scan
    }

    override suspend fun pair(uri: PairingUri, confirm: suspend (code: String) -> Boolean): PairAttempt {
        pairedUris += uri
        pairAnswer?.let { return it }
        if (!confirm(code)) return PairAttempt.Done(PairOutcome.Declined)
        val pairing = store.transaction {
            val id = addAgentPairing(uri.name, uri.fingerprint, uri.addresses.first(), AgentLimits.DEFAULT_SCOPE_DAYS, false, now())
            agentPairing(id)!!
        }
        return PairAttempt.Done(PairOutcome.Paired(pairing))
    }

    override suspend fun turnOn(minutes: Int): TurnOnResult {
        turnOnRequests += minutes
        turnOnAnswer?.let { return it }
        val active = store.read { agentPairings() }.filter { it.isActive }
        if (active.isEmpty()) return TurnOnResult.NO_PAIRINGS
        mutableState.value = AgentAccessState(now() + minutes * 60_000L, active.associate { it.id to LinkState.CONNECTED })
        return TurnOnResult.ON
    }

    override fun turnOff() {
        turnOffs++
        mutableState.value = AgentAccessState()
    }
}
