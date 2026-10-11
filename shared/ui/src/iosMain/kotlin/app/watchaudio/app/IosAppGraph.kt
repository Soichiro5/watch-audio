package app.watchaudio.app

import app.watchaudio.core.alert.GapAlertEvaluator
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.ChunkIntake
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.PolicyGatedTranscription
import app.watchaudio.core.pipeline.ProcessingPolicy
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.SpeechModel
import app.watchaudio.core.pipeline.StatusNotice
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.pipeline.TranscriptionRun
import app.watchaudio.core.pipeline.TranscriptionRun.FollowUp
import app.watchaudio.core.platform.platformName
import app.watchaudio.core.protocol.toJson
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Composition root of the iOS app, called from Swift as `IosAppGraph.shared`.
 *
 * Owns the local database, the audio archive, the [ChunkIntake] (chunks from the Apple Watch via
 * WatchConnectivity) and the [TranscriptionRun] (Speech framework, on device); both are shared
 * with Android, and this graph only maps them to WatchConnectivity and BGTask (#134). Everything is
 * stored under Application Support/WatchAudio, which is excluded from iCloud/computer backups and
 * uses the `CompleteUntilFirstUserAuthentication` protection class ([LocalDataDirectory]):
 * nothing leaves the phone.
 * The diagnostics log (#77) is `diagnostics/diagnostics.csv` there; it leaves only through the
 * user's export ([IosAppServices.exportDiagnostics]).
 *
 * Threading: [start] and [setAppActive] are called on the main thread; [ingest] from any thread.
 * Internal state is only mutated on the main dispatcher.
 */
@OptIn(ExperimentalForeignApi::class)
object IosAppGraph {
    /** Must match `BGTaskSchedulerPermittedIdentifiers` in apps/ios/project.yml. */
    const val PROCESSING_TASK_ID = "app.watchaudio.transcribe"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val status = MutableStateFlow(ProcessingStatus())

    /** One model installation at a time (a run and the user's request may both ask). */
    private val modelLock = Mutex()

    private var started = false
    private var appActive = false
    private var foregroundRun: Job? = null
    private var rerunRequested = false
    private var rerunUserRequested = false

    /** A foreground retry after a timed pause (warm phone, hourly cap; #75). */
    private var retryRun: Job? = null

    /** When transcription may run on battery and when warm (#75); the same policy as Android. */
    private val processingPolicy = ProcessingPolicy()

    private lateinit var transcriber: IosSpeechTranscriber
    private lateinit var store: LocalStore
    private lateinit var intake: ChunkIntake
    private lateinit var transcriptionRun: TranscriptionRun
    private lateinit var storage: StorageManager
    private lateinit var appServices: IosAppServices
    private lateinit var gapAlerts: IosGapAlerts
    private lateinit var diagnostics: DiagnosticsLog

    /** For the shared Compose UI; valid after [start]. */
    val services: AppServices get() = appServices

    /** Application Support/WatchAudio (created on first use, excluded from backup, protected). */
    val rootDirectory: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
        val root = "$base/WatchAudio"
        LocalDataDirectory.prepare(root)
        root
    }

    /** Where the WatchConnectivity receiver moves incoming files before calling [ingest]. */
    fun inboxDirectoryPath(): String {
        val inbox = "$rootDirectory/inbox"
        SystemFileSystem.createDirectories(Path(inbox))
        return inbox
    }

    /** Audio archive (paths in the database are relative to it). */
    internal val audioDirectory: String by lazy {
        val audio = "$rootDirectory/audio"
        LocalDataDirectory.prepare(audio)
        audio
    }

    /**
     * Opens the database and registers the background task. Call once from the app's `init`
     * (BGTaskScheduler requires registration before launch finishes). Idempotent.
     */
    fun start(transcriber: IosSpeechTranscriber) {
        if (started) return
        started = true
        this.transcriber = transcriber
        diagnostics = DiagnosticsLog(Path("$rootDirectory/diagnostics/${DiagnosticsLog.FILE_NAME}"))
        recordUncaughtExceptions(diagnostics)
        diagnostics.log(DiagnosticEvents.APP_START, "version=${appVersion()} os=${platformName()}")
        val audioRoot = audioDirectory
        cleanInbox()
        store = LocalStore.open("$rootDirectory/${LocalStore.FILE_NAME}")
        // SpeechAnalyzer reports neither a speech probability nor a noise level, so the filter keeps
        // everything for now; it applies as soon as the Swift side supplies them (#105).
        val pipeline = TranscriptionPipeline(store, Path(audioRoot), IosTranscriberAdapter(transcriber), segmentFilter = FalsePositiveFilter.DEFAULT)
        storage = StorageManager(store, Path(audioRoot), IosStorageEnvironment(rootDirectory))
        gapAlerts = IosGapAlerts(GapAlertEvaluator(store), diagnostics)
        intake = ChunkIntake(Ingestor(store, Path(audioRoot)), diagnostics, onStored = { chunkStored() })
        transcriptionRun = TranscriptionRun(
            pipeline,
            store,
            storage,
            model = speechModel,
            diagnostics = diagnostics,
            gate = {
                PolicyGatedTranscription(
                    pipeline,
                    store,
                    processingPolicy,
                    conditions = IosDeviceConditions::read,
                    clock = { Clock.System.now().toEpochMilliseconds() },
                    diagnostics = diagnostics,
                )
            },
        )
        appServices = IosAppServices(
            store,
            audioRoot,
            storage,
            status,
            // Only the user's tap calls this: the run waives the battery rules (not the thermal one).
            onRequestTranscription = { requestTranscription(userRequested = true) },
            onPrepareModel = ::prepareModel,
            onGapAlertSettingsChanged = { scope.launch { runCatching { gapAlerts.reschedule(chunkArrived = false) } } },
            diagnostics = diagnostics,
            onTranscriptionSettingsChanged = { requestTranscription() },
        )
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(PROCESSING_TASK_ID, null) { task ->
            if (task != null) scope.launch { runBackgroundTask(task) }
        }
    }

    /** Called from SwiftUI's scenePhase: transcribe while visible, hand over to BGTask otherwise. */
    fun setAppActive(active: Boolean) {
        if (!started) return
        appActive = active
        if (active) {
            requestTranscription()
        } else {
            // Speech analysis is not guaranteed in the background; continue in a BGProcessingTask.
            foregroundRun?.cancel()
            foregroundRun = null
            retryRun?.cancel()
            retryRun = null
            scheduleBackgroundProcessing()
        }
    }

    /**
     * Ingests one chunk from the watch. [filePath] is the audio already moved into
     * [inboxDirectoryPath] (null for a silent chunk). [completion] receives the ChunkAck JSON to
     * send back, or null if the chunk could not be handled at all (the watch will resend).
     * Called from any thread; [completion] runs on the main thread.
     */
    fun ingest(manifestJson: String, filePath: String?, completion: (String?) -> Unit) {
        scope.launch {
            // Keeps the app running long enough to verify, store and ACK when woken in the background.
            val application = UIApplication.sharedApplication
            var backgroundTask = UIBackgroundTaskInvalid
            fun endBackgroundTask() {
                if (backgroundTask != UIBackgroundTaskInvalid) {
                    application.endBackgroundTask(backgroundTask)
                    backgroundTask = UIBackgroundTaskInvalid
                }
            }
            backgroundTask = application.beginBackgroundTaskWithName("ingest-chunk") { endBackgroundTask() }
            try {
                completion(ingestNow(manifestJson, filePath))
            } finally {
                endBackgroundTask()
            }
        }
    }

    private suspend fun ingestNow(manifestJson: String, filePath: String?): String? {
        val file = filePath?.let { Path(it) }
        if (!started) {
            file?.let(::deleteQuietly)
            return null
        }
        // The ACK itself is sent by the Swift receiver (WatchConnectivity).
        val result = intake.receive(manifestJson, file)
        // ChunkIntake logged the error type; the watch resends the chunk.
        if (result is ChunkIntake.Result.Failed) status.update { it.copy(notice = StatusNotice.IngestFailed) }
        return result.ack?.toJson()
    }

    /** A new chunk was stored: transcribe it and re-arm the gap alert. */
    private suspend fun chunkStored() {
        status.update { it.copy(lastChunkReceivedAt = Clock.System.now().toEpochMilliseconds()) }
        requestTranscription()
        // The gap (if any) is over; arm the alert for the next one (#43). Never block the ACK.
        try {
            gapAlerts.reschedule(chunkArrived = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log(
                DiagnosticEvents.GAP_ALERT_BLOCKED,
                "reason=schedule_failed error=${DiagnosticsLog.typeName(e)}",
            )
            status.update { it.copy(notice = StatusNotice.GapAlertScheduleFailed) }
        }
    }

    /**
     * Transcribes in the foreground while the app is visible, under [processingPolicy] (#75); a timed
     * pause (warm phone, hourly cap on battery) is retried while the app stays visible.
     * @param userRequested the user's tap (see [ProcessingPolicy.decide]).
     */
    private fun requestTranscription(userRequested: Boolean = false) {
        if (!started) return
        if (!appActive) {
            scheduleBackgroundProcessing()
            return
        }
        if (foregroundRun?.isActive == true) {
            rerunRequested = true
            rerunUserRequested = rerunUserRequested || userRequested
            return
        }
        retryRun?.cancel()
        retryRun = null
        foregroundRun = scope.launch {
            var user = userRequested
            var followUp: FollowUp
            do {
                rerunRequested = false
                rerunUserRequested = false
                followUp = runPipeline("foreground", userRequested = user, shouldContinue = { appActive })
                user = rerunUserRequested
            } while ((rerunRequested || followUp == FollowUp.RunAgain) && appActive)
            val retryAfterMs = (followUp as? FollowUp.Paused)?.retryAfterMs
            if (retryAfterMs != null && appActive) {
                retryRun = scope.launch {
                    delay(retryAfterMs)
                    requestTranscription()
                }
            }
        }
    }

    private suspend fun runBackgroundTask(task: BGTask) {
        val flag = ExpirationFlag()
        var followUp: FollowUp? = null
        // Runs on external power only; the policy can still pause it when the phone is warm (#75).
        val job = scope.launch {
            do {
                followUp = runPipeline("bgtask", userRequested = false, shouldContinue = { !flag.expired })
            } while (followUp == FollowUp.RunAgain && !flag.expired)
        }
        task.expirationHandler = {
            // Stop between chunks if possible; cancel the running chunk so we finish in time.
            flag.expired = true
            job.cancel()
        }
        job.join()
        if (flag.expired) diagnostics.log(DiagnosticEvents.WORKER_STOP, "worker=bgtask expired=true")
        task.setTaskCompletedWithSuccess(!flag.expired)
        val paused = followUp as? FollowUp.Paused
        when {
            flag.expired -> scheduleBackgroundProcessing()
            paused != null -> scheduleBackgroundProcessing(earliestAfterMs = paused.retryAfterMs)
        }
    }

    /**
     * One shared [TranscriptionRun] (serialized with any other run), with its outcome mapped to the
     * status tab.
     */
    private suspend fun runPipeline(worker: String, userRequested: Boolean, shouldContinue: () -> Boolean): FollowUp {
        val report = transcriptionRun.run(
            worker,
            userRequested = userRequested,
            shouldContinue = shouldContinue,
            onTranscribing = { on -> status.update { it.copy(transcribing = on) } },
        )
        report.transcription?.let { outcome ->
            status.update { it.copy(paused = outcome.pause?.reason) }
            if (outcome.result.failed > 0) {
                status.update { it.copy(notice = StatusNotice.TranscriptionFailed(outcome.result.failed)) }
            }
        }
        // TranscriptionRun logged the error type.
        if (report.retention is TranscriptionRun.Retention.Failed) {
            status.update { it.copy(notice = StatusNotice.RetentionFailed) }
        }
        return report.followUp
    }

    /** Installs the Speech model on request (onboarding / status tab), then transcribes what waits. */
    private fun prepareModel() {
        if (!started) return
        scope.launch {
            if (ensureModel()) requestTranscription()
        }
    }

    /** The Speech model as [TranscriptionRun] sees it: installed by the OS on first use. */
    private val speechModel = object : SpeechModel {
        override suspend fun ensureReady(): ModelState {
            ensureModel()
            return status.value.asrModel
        }

        // Re-check the model next time (the OS may have removed it).
        override fun recheck() = status.update { it.copy(asrModel = ModelState.Unknown) }
    }

    /** Makes sure the Speech model is installed (the OS downloads it on first use). */
    private suspend fun ensureModel(): Boolean = modelLock.withLock {
        if (status.value.asrModel == ModelState.Ready) return@withLock true
        status.update { it.copy(asrModel = ModelState.Downloading(progress = 0f)) }
        diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_START, "os_managed=true")
        val error = suspendCancellableCoroutine { cont -> transcriber.prepareModel { cont.resume(it) } }
        if (error == null) {
            diagnostics.log(DiagnosticEvents.MODEL_READY, "ready=true")
        } else {
            // The OS's description of the asset failure; it holds nothing about the user.
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_FAILED, "message=${error.take(200)}")
        }
        status.update { it.copy(asrModel = if (error == null) ModelState.Ready else ModelState.Failed(error)) }
        error == null
    }

    /** @param earliestAfterMs not before this delay (a thermal pause, #75). */
    private fun scheduleBackgroundProcessing(earliestAfterMs: Long? = null) {
        val request = BGProcessingTaskRequest(PROCESSING_TASK_ID)
        request.requiresExternalPower = true
        earliestAfterMs?.let { request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(it / 1000.0) }
        request.requiresNetworkConnectivity = false
        // Fails on the simulator and when Background App Refresh is off; foreground runs still work.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    /** Files left in the inbox by a process that died mid-ingest; the watch resends them. */
    private fun cleanInbox() {
        val inbox = Path(inboxDirectoryPath())
        runCatching { SystemFileSystem.list(inbox).forEach(::deleteQuietly) }
    }

    private fun deleteQuietly(path: Path) {
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }

    /**
     * Writes a crash line (types and top frames, no messages) for Kotlin exceptions nobody caught,
     * then lets the previous hook (normally: terminate) run. Objective-C/Swift crashes are left to
     * the OS crash reports (Settings > Privacy > Analytics, Xcode Organizer).
     */
    @OptIn(ExperimentalNativeApi::class)
    private fun recordUncaughtExceptions(log: DiagnosticsLog) {
        var previous: ReportUnhandledExceptionHook? = null
        previous = setUnhandledExceptionHook { throwable ->
            runCatching { log.recordCrash("kotlin", throwable) }
            previous?.invoke(throwable)
        }
    }

    private fun appVersion(): String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"

    private class ExpirationFlag {
        @Volatile
        var expired = false
    }
}
