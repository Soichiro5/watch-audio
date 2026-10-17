package app.watchaudio.android

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.watchaudio.android.agent.AacClipSource
import app.watchaudio.android.agent.AndroidAgentAccess
import app.watchaudio.android.alert.GapAlertMonitor
import app.watchaudio.android.asr.AndroidSherpaTranscriber
import app.watchaudio.android.asr.ModelManager
import app.watchaudio.android.llm.GeminiNanoLlmProvider
import app.watchaudio.android.llm.MlKitGeminiNano
import app.watchaudio.android.search.AndroidEmbedding
import app.watchaudio.android.search.AndroidSemanticSearch
import app.watchaudio.android.transfer.ChunkReceiver
import app.watchaudio.android.work.AndroidDeviceConditions
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.core.agent.AgentService
import app.watchaudio.core.agent.AgentTools
import app.watchaudio.core.agent.pruneAgentAccessLog
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.pipeline.ChunkIntake
import app.watchaudio.core.pipeline.DeviceConditions
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.PauseReason
import app.watchaudio.core.pipeline.PolicyGatedTranscription
import app.watchaudio.core.pipeline.ProcessingPolicy
import app.watchaudio.core.pipeline.SpeechModel
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.pipeline.TranscriptionRun
import app.watchaudio.core.search.SemanticIndex
import app.watchaudio.core.settings.loadSettings
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

/**
 * Process-wide object graph of the phone app. Created on first use (normally from
 * [WatchAudioApp.onCreate]); services and workers reach it through [get], since they can start
 * the process without any UI.
 *
 * All data lives in the app's internal storage: the database at `files/watch-audio.db`, audio
 * under `files/audio/`, models under `files/models/`, the diagnostics log under
 * `files/diagnostics/` (#77), the semantic search's vector cache under `files/search/` (#24).
 */
class AppGraph private constructor(context: Context) {
    private val app = context.applicationContext

    val diagnostics: DiagnosticsLog = diagnostics(app)

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "background task failed", e) },
    )

    val store: LocalStore = LocalStore.open(File(app.filesDir, LocalStore.FILE_NAME).path)
    val audioRoot = File(app.filesDir, "audio")
    val ingestor = Ingestor(store, Path(audioRoot.path))

    val scheduler = TranscriptionScheduler(app, scope, chargingOnly = { store.loadSettings().transcribeOnlyWhileCharging })
    val models = ModelManager(app, scope, diagnostics, onReady = scheduler::requestNow)
    val transcriber = AndroidSherpaTranscriber(models.files, models.englishFiles, englishEnabled = { store.loadSettings().transcribeEnglish })
    val pipeline = TranscriptionPipeline(store, Path(audioRoot.path), transcriber, segmentFilter = FalsePositiveFilter.DEFAULT)

    /** Semantic search (#24): the embedding model and the vector cache of its passages. */
    val embedder = AndroidEmbedding.embedder(models.files.root)
    val semanticIndex = SemanticIndex(
        store,
        embedder,
        File(app.filesDir, "search").path,
        modelState = { if (models.isEmbeddingReady()) ModelState.Ready else models.embeddingState.value },
    )

    val storage = StorageManager(
        store,
        Path(audioRoot.path),
        AndroidStorageEnvironment(models.files.root, app.filesDir),
        diagnostics = diagnostics,
        // Deleted transcripts leave no vectors behind in the cache files.
        onDeleted = { semanticIndex.sync(prune = true) },
    )

    /** Battery, battery saver and thermal state for the processing policy (#75); tests substitute their own. */
    @VisibleForTesting
    @Volatile
    var deviceConditions: () -> DeviceConditions = AndroidDeviceConditions(app)::read

    /** When transcription may run (#75); tests use smaller limits. */
    @VisibleForTesting
    @Volatile
    var processingPolicy = ProcessingPolicy()

    /** The clock of the hourly battery budget; tests move it forward. */
    @VisibleForTesting
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** Why the last run paused (null: it did not), for the status card. */
    val transcriptionPause = MutableStateFlow<PauseReason?>(null)

    /** True while a run embeds for semantic search (#24): the worker runs, but nothing is transcribed. */
    val embeddingRunning = MutableStateFlow(false)

    /** [pipeline] under the battery/thermal policy, with the current [deviceConditions], [processingPolicy] and [clock]. */
    private fun gatedTranscription() = PolicyGatedTranscription(pipeline, store, processingPolicy, deviceConditions, clock, diagnostics)

    /** One transcription run, shared with iOS (#134); [app.watchaudio.android.work.TranscriptionWorker] maps its follow-up to WorkManager. */
    val transcriptionRun = TranscriptionRun(
        pipeline,
        store,
        storage,
        model = object : SpeechModel {
            override suspend fun ensureReady(): ModelState = when {
                models.isReady() -> ModelState.Ready
                models.state.value is ModelState.Downloading -> models.state.value
                else -> ModelState.Missing
            }

            override fun recheck() = models.recheck()
        },
        diagnostics = diagnostics,
        gate = ::gatedTranscription,
        releaseTranscriber = transcriber::release,
        embedding = EmbeddingStep(
            store,
            embedder,
            semanticIndex,
            diagnostics,
            isModelReady = models::isEmbeddingReady,
            onRunning = { embeddingRunning.value = it },
        ),
    )

    private val _lastChunkReceivedAt = MutableStateFlow<Long?>(null)
    val lastChunkReceivedAt: StateFlow<Long?> = _lastChunkReceivedAt.asStateFlow()

    val gapAlerts = GapAlertMonitor(app, store, scope, diagnostics)

    /** Where chunks from the watch enter, shared with iOS (#134). */
    val chunkIntake = ChunkIntake(ingestor, diagnostics) {
        _lastChunkReceivedAt.value = System.currentTimeMillis()
        scheduler.requestNow()
        gapAlerts.onChunkStored()
    }

    val chunkReceiver = ChunkReceiver(app, chunkIntake, scope, diagnostics)

    private val semanticSearch = AndroidSemanticSearch(semanticIndex, models)

    /**
     * Agent access (#184): the tools a paired PC's agent may call (search by meaning where the
     * embedding model is ready, audio clips for pairings allowed audio) and the connection while on.
     */
    val agentAccess = AndroidAgentAccess(
        app,
        store,
        AgentService(store, AgentTools(store, TimeZone.currentSystemDefault(), semantic = semanticSearch, audio = AacClipSource(audioRoot))),
        software = "watch-audio-android/" + runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty(),
    )

    val services = AndroidAppServices(
        app,
        store,
        storage,
        audioRoot,
        models,
        scheduler,
        lastChunkReceivedAt,
        transcriptionPause,
        scope,
        diagnostics,
        onGapAlertSettingsChanged = gapAlerts::onSettingsChanged,
        semanticSearch = semanticSearch,
        embeddingRunning = embeddingRunning,
        agentAccess = agentAccess,
        llm = GeminiNanoLlmProvider(MlKitGeminiNano(), diagnostics),
    )

    /**
     * Start-up recovery: re-queues chunks a killed process left mid-transcription (so that
     * [hasPendingChunks] sees them before the first run, which recovers again) and publishes the
     * model state. Workers wait for it before touching the pipeline.
     */
    val startup: Job = scope.launch {
        pipeline.recoverInterrupted().let { if (it > 0) Log.i(TAG, "re-queued $it interrupted chunks") }
        store.read { lastReceivedAt() }?.let { stored -> _lastChunkReceivedAt.compareAndSet(null, stored) }
        models.refresh()
        models.syncEnglish(store.loadSettings().transcribeEnglish)
        models.syncEmbedding()
        // Agent access log retention (#184): also when the app is not opened.
        store.pruneAgentAccessLog(System.currentTimeMillis())
    }

    suspend fun hasPendingChunks(): Boolean = pipeline.hasPending()

    companion object {
        private const val TAG = "AppGraph"

        /**
         * Cache subdirectory for the diagnostics log copies handed to the share sheet; must match
         * `res/xml/diagnostics_paths.xml`.
         */
        const val DIAGNOSTICS_EXPORT_DIR = "diagnostics-export"

        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(context).also { instance = it } }

        @Volatile
        private var diagnosticsLog: DiagnosticsLog? = null

        /**
         * The process-wide diagnostics log. Available before (and without) the graph, so a crash
         * while building the graph is still recorded.
         */
        fun diagnostics(context: Context): DiagnosticsLog = diagnosticsLog ?: synchronized(this) {
            diagnosticsLog ?: DiagnosticsLog(
                Path(File(File(context.applicationContext.filesDir, "diagnostics"), DiagnosticsLog.FILE_NAME).path),
                exportDirectory = Path(File(context.applicationContext.cacheDir, DIAGNOSTICS_EXPORT_DIR).path),
            ).also { diagnosticsLog = it }
        }
    }
}
