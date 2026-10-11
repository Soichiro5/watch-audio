package app.watchaudio.app

import app.watchaudio.core.alert.GapAlertEvaluator
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.DiagnosticsPipelineObserver
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.PolicyGatedTranscription
import app.watchaudio.core.pipeline.ProcessingDecision
import app.watchaudio.core.pipeline.ProcessingPolicy
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.platform.platformName
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.decodeManifest
import app.watchaudio.core.protocol.toJson
import app.watchaudio.core.settings.loadSettings
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Owns the local database, the audio archive, the [Ingestor] (chunks from the Apple Watch via
 * WatchConnectivity) and the [TranscriptionPipeline] (Speech framework, on device). Everything is
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
    private val runLock = Mutex()

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
    private lateinit var ingestor: Ingestor
    private lateinit var pipeline: TranscriptionPipeline
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
        ingestor = Ingestor(store, Path(audioRoot))
        pipeline = TranscriptionPipeline(store, Path(audioRoot), IosTranscriberAdapter(transcriber))
        storage = StorageManager(store, Path(audioRoot), IosStorageEnvironment(rootDirectory))
        gapAlerts = IosGapAlerts(GapAlertEvaluator(store), diagnostics)
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
        val manifest = try {
            decodeManifest(manifestJson)
        } catch (e: Exception) {
            file?.let(::deleteQuietly)
            diagnostics.log(DiagnosticEvents.CHUNK_REJECTED, "reason=malformed_manifest error=${DiagnosticsLog.typeName(e)}")
            val chunkId = chunkIdOf(manifestJson) ?: return null
            return ChunkAck(chunkId = chunkId, status = AckStatus.REJECTED, detail = e.message?.take(200)).toJson()
        }
        val ack = try {
            ingestor.ingest(manifest, file)
        } catch (e: Exception) {
            file?.let(::deleteQuietly)
            diagnostics.log(
                DiagnosticEvents.CHUNK_REJECTED,
                "chunk=${manifest.chunkId} reason=ingest_failed error=${DiagnosticsLog.typeName(e)}",
            )
            status.update { it.copy(message = "取り込みに失敗: ${e.message}") }
            return null
        }
        // The ACK itself is sent by the Swift receiver (WatchConnectivity).
        diagnostics.log(
            DiagnosticEvents.CHUNK_RECEIVED,
            "chunk=${manifest.chunkId} status=${ack.status} audio_ms=${manifest.durationMs} " +
                "bytes=${manifest.audio?.sizeBytes ?: 0}${ack.detail?.let { " detail=$it" }.orEmpty()}",
        )
        if (!ack.status.isFinal) file?.let(::deleteQuietly) // corrupt/rejected: the watch resends
        if (ack.status == AckStatus.STORED) {
            status.update { it.copy(lastChunkReceivedAt = Clock.System.now().toEpochMilliseconds()) }
            requestTranscription()
            // The gap (if any) is over; arm the alert for the next one (#43). Never block the ACK.
            try {
                gapAlerts.reschedule(chunkArrived = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.update { it.copy(message = "通知の予約に失敗: ${e.message}") }
            }
        }
        return ack.toJson()
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
            var pause: ProcessingDecision.Pause?
            do {
                rerunRequested = false
                rerunUserRequested = false
                pause = runPipeline("foreground", userRequested = user, shouldContinue = { appActive })
                user = rerunUserRequested
            } while (rerunRequested && appActive)
            val retryAfterMs = pause?.retryAfterMs
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
        var pause: ProcessingDecision.Pause? = null
        // Runs on external power only; the policy can still pause it when the phone is warm (#75).
        val job = scope.launch { pause = runPipeline("bgtask", userRequested = false, shouldContinue = { !flag.expired }) }
        task.expirationHandler = {
            // Stop between chunks if possible; cancel the running chunk so we finish in time.
            flag.expired = true
            job.cancel()
        }
        job.join()
        if (flag.expired) diagnostics.log(DiagnosticEvents.WORKER_STOP, "worker=bgtask expired=true")
        task.setTaskCompletedWithSuccess(!flag.expired)
        when {
            flag.expired -> scheduleBackgroundProcessing()
            pause != null -> scheduleBackgroundProcessing(earliestAfterMs = pause?.retryAfterMs)
        }
    }

    /**
     * Serialized: at most one pipeline run (foreground or BGTask) at a time. Transcribes, then
     * applies the audio retention setting (only transcribed audio is ever deleted, #33).
     * @return the policy's pause if it stopped the run (#75).
     */
    private suspend fun runPipeline(worker: String, userRequested: Boolean, shouldContinue: () -> Boolean): ProcessingDecision.Pause? =
        runLock.withLock {
            diagnostics.log(DiagnosticEvents.WORKER_START, "worker=$worker")
            var summary = ""
            var pause: ProcessingDecision.Pause? = null
            try {
                // Without the model every chunk would fail and use up its retry attempts.
                if (ensureModel()) {
                    val outcome = transcribePending(userRequested, shouldContinue)
                    pause = outcome.pause
                    summary = " transcribed=${outcome.result.transcribed} failed=${outcome.result.failed}" +
                        pause?.let { " paused=${it.reason.code}" }.orEmpty()
                } else {
                    summary = " skipped=model_not_ready"
                }
                applyRetention(shouldContinue)
            } finally {
                diagnostics.log(DiagnosticEvents.WORKER_STOP, "worker=$worker$summary")
            }
            pause
        }

    /** Transcribes what waits, as far as [processingPolicy] allows (checked before every chunk). */
    private suspend fun transcribePending(userRequested: Boolean, shouldContinue: () -> Boolean): PolicyGatedTranscription.Outcome {
        status.update { it.copy(transcribing = true) }
        try {
            // Chunks left TRANSCRIBING by a cancelled or killed run go back into the queue.
            pipeline.recoverInterrupted()
            val chargingOnly = store.loadSettings().transcribeOnlyWhileCharging
            val outcome = PolicyGatedTranscription(
                pipeline,
                store,
                processingPolicy,
                conditions = IosDeviceConditions::read,
                clock = { Clock.System.now().toEpochMilliseconds() },
                diagnostics = diagnostics,
            ).run(chargingOnly, userRequested, DiagnosticsPipelineObserver(diagnostics), shouldContinue)
            val result = outcome.result
            status.update { it.copy(paused = outcome.pause?.reason) }
            if (result.failed > 0) {
                // Re-check the model next time (the OS may have removed it).
                status.update { it.copy(asrModel = ModelState.Unknown, message = "文字起こしに失敗したチャンク: ${result.failed}") }
            }
            return outcome
        } finally {
            status.update { it.copy(transcribing = false) }
        }
    }

    /** Installs the Speech model on request (onboarding / status tab), then transcribes what waits. */
    private fun prepareModel() {
        if (!started) return
        scope.launch {
            val ready = runLock.withLock { ensureModel() }
            if (ready) requestTranscription()
        }
    }

    private suspend fun applyRetention(shouldContinue: () -> Boolean) {
        try {
            val purged = storage.applyRetention(shouldContinue)
            if (purged.purgedChunks > 0) {
                diagnostics.log(DiagnosticEvents.RETENTION_PURGE, "chunks=${purged.purgedChunks} bytes=${purged.freedBytes}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log(DiagnosticEvents.RETENTION_PURGE, "failed=true error=${DiagnosticsLog.typeName(e)}")
            status.update { it.copy(message = "古い音声の削除に失敗: ${e.message}") }
        }
    }

    /** Makes sure the Speech model is installed (the OS downloads it on first use). */
    private suspend fun ensureModel(): Boolean {
        if (status.value.asrModel == ModelState.Ready) return true
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
        return error == null
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

    private fun chunkIdOf(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["chunkId"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private class ExpirationFlag {
        @Volatile
        var expired = false
    }
}
