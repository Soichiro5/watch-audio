package app.watchaudio.android

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import app.watchaudio.android.alert.GapAlertMonitor
import app.watchaudio.android.asr.AndroidSherpaTranscriber
import app.watchaudio.android.asr.ModelManager
import app.watchaudio.android.transfer.ChunkReceiver
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.pipeline.TranscriptionPipeline
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path

/**
 * Process-wide object graph of the phone app. Created on first use (normally from
 * [WatchAudioApp.onCreate]); services and workers reach it through [get], since they can start
 * the process without any UI.
 *
 * All data lives in the app's internal storage: the database at `files/watch-audio.db`, audio
 * under `files/audio/`, models under `files/models/`, the diagnostics log under
 * `files/diagnostics/` (#77).
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

    val scheduler = TranscriptionScheduler(app, scope)
    val models = ModelManager(app, scope, diagnostics, onReady = scheduler::requestNow)
    val transcriber = AndroidSherpaTranscriber(models.files)
    val pipeline = TranscriptionPipeline(store, Path(audioRoot.path), transcriber, maxAttempts = MAX_ATTEMPTS)
    val storage = StorageManager(store, Path(audioRoot.path), AndroidStorageEnvironment(models.files.root, app.filesDir))

    /** Serializes pipeline runs between overlapping workers. */
    val transcriptionLock = Mutex()

    private val _lastChunkReceivedAt = MutableStateFlow<Long?>(null)
    val lastChunkReceivedAt: StateFlow<Long?> = _lastChunkReceivedAt.asStateFlow()

    val gapAlerts = GapAlertMonitor(app, store, scope, diagnostics)

    val chunkReceiver = ChunkReceiver(app, ingestor, scope, diagnostics) {
        _lastChunkReceivedAt.value = System.currentTimeMillis()
        scheduler.requestNow()
        gapAlerts.onChunkStored()
    }

    val services = AndroidAppServices(
        app,
        store,
        storage,
        audioRoot,
        models,
        scheduler,
        lastChunkReceivedAt,
        scope,
        diagnostics,
        onGapAlertSettingsChanged = gapAlerts::onSettingsChanged,
    )

    /**
     * Start-up recovery: re-queues chunks a killed process left mid-transcription and publishes
     * the model state. Workers wait for it before touching the pipeline.
     */
    val startup: Job = scope.launch {
        pipeline.recoverInterrupted().let { if (it > 0) Log.i(TAG, "re-queued $it interrupted chunks") }
        store.read { lastReceivedAt() }?.let { stored -> _lastChunkReceivedAt.compareAndSet(null, stored) }
        models.refresh()
    }

    suspend fun hasPendingChunks(): Boolean = store.read { pendingChunks(limit = 1, maxAttempts = MAX_ATTEMPTS) }.isNotEmpty()

    companion object {
        private const val TAG = "AppGraph"
        private const val MAX_ATTEMPTS = 3

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
            ).also { diagnosticsLog = it }
        }
    }
}
