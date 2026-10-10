package app.watchaudio.app

import app.watchaudio.app.alert.GapAlertEvaluator
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.decodeManifest
import app.watchaudio.core.protocol.toJson
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Composition root of the iOS app, called from Swift as `IosAppGraph.shared`.
 *
 * Owns the local database, the audio archive, the [Ingestor] (chunks from the Apple Watch via
 * WatchConnectivity) and the [TranscriptionPipeline] (Speech framework, on device). Everything is
 * stored under Application Support/WatchAudio, which is excluded from iCloud/iTunes backups:
 * nothing leaves the phone.
 *
 * Threading: [start] and [setAppActive] are called on the main thread; [ingest] from any thread.
 * Internal state is only mutated on the main dispatcher.
 */
@OptIn(ExperimentalForeignApi::class)
object IosAppGraph {
    /** Must match `BGTaskSchedulerPermittedIdentifiers` in iosApp/project.yml. */
    const val PROCESSING_TASK_ID = "app.watchaudio.transcribe"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val status = MutableStateFlow(ProcessingStatus())
    private val runLock = Mutex()

    private var started = false
    private var appActive = false
    private var foregroundRun: Job? = null
    private var rerunRequested = false

    private lateinit var transcriber: IosSpeechTranscriber
    private lateinit var store: LocalStore
    private lateinit var ingestor: Ingestor
    private lateinit var pipeline: TranscriptionPipeline
    private lateinit var appServices: IosAppServices
    private lateinit var gapAlerts: IosGapAlerts

    /** For the shared Compose UI; valid after [start]. */
    val services: AppServices get() = appServices

    /** Application Support/WatchAudio (created on first use, excluded from backup). */
    val rootDirectory: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
        val root = "$base/WatchAudio"
        SystemFileSystem.createDirectories(Path(root))
        // A directory's flag also covers everything inside it.
        NSURL.fileURLWithPath(root).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        root
    }

    /** Where the WatchConnectivity receiver moves incoming files before calling [ingest]. */
    fun inboxDirectoryPath(): String {
        val inbox = "$rootDirectory/inbox"
        SystemFileSystem.createDirectories(Path(inbox))
        return inbox
    }

    /**
     * Opens the database and registers the background task. Call once from the app's `init`
     * (BGTaskScheduler requires registration before launch finishes). Idempotent.
     */
    fun start(transcriber: IosSpeechTranscriber) {
        if (started) return
        started = true
        this.transcriber = transcriber
        val audioRoot = "$rootDirectory/audio"
        SystemFileSystem.createDirectories(Path(audioRoot))
        cleanInbox()
        store = LocalStore.open("$rootDirectory/${LocalStore.FILE_NAME}")
        ingestor = Ingestor(store, Path(audioRoot))
        pipeline = TranscriptionPipeline(store, Path(audioRoot), IosTranscriberAdapter(transcriber))
        gapAlerts = IosGapAlerts(GapAlertEvaluator(store))
        appServices = IosAppServices(
            store,
            audioRoot,
            status,
            onRequestTranscription = ::requestTranscription,
            onPrepareModel = ::prepareModel,
            onGapAlertSettingsChanged = { scope.launch { runCatching { gapAlerts.reschedule(chunkArrived = false) } } },
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
            val chunkId = chunkIdOf(manifestJson) ?: return null
            return ChunkAck(chunkId = chunkId, status = AckStatus.REJECTED, detail = e.message?.take(200)).toJson()
        }
        val ack = try {
            ingestor.ingest(manifest, file)
        } catch (e: Exception) {
            file?.let(::deleteQuietly)
            status.update { it.copy(message = "取り込みに失敗: ${e.message}") }
            return null
        }
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

    private fun requestTranscription() {
        if (!started) return
        if (!appActive) {
            scheduleBackgroundProcessing()
            return
        }
        if (foregroundRun?.isActive == true) {
            rerunRequested = true
            return
        }
        foregroundRun = scope.launch {
            do {
                rerunRequested = false
                runPipeline(shouldContinue = { appActive })
            } while (rerunRequested && appActive)
        }
    }

    private suspend fun runBackgroundTask(task: BGTask) {
        val flag = ExpirationFlag()
        val job = scope.launch { runPipeline(shouldContinue = { !flag.expired }) }
        task.expirationHandler = {
            // Stop between chunks if possible; cancel the running chunk so we finish in time.
            flag.expired = true
            job.cancel()
        }
        job.join()
        task.setTaskCompletedWithSuccess(!flag.expired)
        if (flag.expired) scheduleBackgroundProcessing()
    }

    /** Serialized: at most one pipeline run (foreground or BGTask) at a time. */
    private suspend fun runPipeline(shouldContinue: () -> Boolean) = runLock.withLock {
        // Without the model every chunk would fail and use up its retry attempts.
        if (!ensureModel()) return@withLock
        status.update { it.copy(transcribing = true) }
        try {
            // Chunks left TRANSCRIBING by a cancelled or killed run go back into the queue.
            pipeline.recoverInterrupted()
            val result = pipeline.processPending(shouldContinue)
            if (result.failed > 0) {
                // Re-check the model next time (the OS may have removed it).
                status.update { it.copy(asrModel = ModelState.Unknown, message = "文字起こしに失敗したチャンク: ${result.failed}") }
            }
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

    /** Makes sure the Speech model is installed (the OS downloads it on first use). */
    private suspend fun ensureModel(): Boolean {
        if (status.value.asrModel == ModelState.Ready) return true
        status.update { it.copy(asrModel = ModelState.Downloading(progress = 0f)) }
        val error = suspendCancellableCoroutine { cont -> transcriber.prepareModel { cont.resume(it) } }
        status.update { it.copy(asrModel = if (error == null) ModelState.Ready else ModelState.Failed(error)) }
        return error == null
    }

    private fun scheduleBackgroundProcessing() {
        val request = BGProcessingTaskRequest(PROCESSING_TASK_ID)
        request.requiresExternalPower = true
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

    private fun chunkIdOf(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["chunkId"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private class ExpirationFlag {
        @Volatile
        var expired = false
    }
}
