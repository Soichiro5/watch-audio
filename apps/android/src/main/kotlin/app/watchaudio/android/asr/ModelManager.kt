package app.watchaudio.android.asr

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.ModelState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provides the on-device speech models in [files]: imports a developer's `adb push` copy, or
 * downloads the files of [ModelManifest] through [ModelDownloadWorker]. This is a download
 * only; nothing about the user is sent.
 *
 * New files are written next to the target and renamed into place only when complete (and,
 * for downloads, verified), so a crash never leaves a half-written model that looks complete.
 */
class ModelManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: Diagnostics,
    private val onReady: () -> Unit,
) {
    val files = ModelFiles(File(context.filesDir, "models"))

    private val _state = MutableStateFlow<ModelState>(ModelState.Unknown)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Last 10 % step written to the diagnostics log during a download. */
    @Volatile
    private var loggedDecile = 0

    private val downloader = ModelDownloader()

    /** Unfinished downloads; unlike `.tmp-*` they survive restarts so they can be resumed. */
    private val partialDir = File(files.root, PARTIAL_DIR)

    /** Named by content hash, so a part of an older model version is never resumed. */
    private fun partFile(source: ModelSource) = File(partialDir, "${source.sha256}.part")

    /** True while a download is queued, waiting for its network constraint (e.g. Wi-Fi). */
    val downloadWaiting: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }

    fun isReady(): Boolean = files.isComplete()

    /**
     * Publishes whether the files are (still) complete, e.g. after chunks failed to transcribe
     * ([app.watchaudio.core.pipeline.SpeechModel.recheck]). Leaves a running download alone.
     */
    fun recheck() {
        if (_state.value !is ModelState.Downloading) publishPresence()
    }

    /**
     * Startup step: removes leftovers of interrupted downloads, imports sideloaded models and
     * publishes the state. Must run before any download starts in this process.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        files.root.mkdirs()
        files.root.listFiles { file -> file.name.startsWith(TEMP_PREFIX) }?.forEach { it.deleteRecursively() }
        try {
            importSideloaded()
        } catch (e: IOException) {
            Log.w(TAG, "sideloaded models could not be imported", e)
        }
        if (isReady()) partialDir.deleteRecursively()
        publishPresence()
    }

    /**
     * Downloads missing models. Without [allowMetered] the download waits for an unmetered
     * network (about 160 MB); the user can opt in to mobile data.
     * A download that is already running is left alone.
     */
    fun download(allowMetered: Boolean) = enqueue(allowMetered, ExistingWorkPolicy.REPLACE)

    /** Automatic start-up download: only on unmetered networks and never replaces a user's request. */
    fun downloadIfMissing() {
        if (!isReady()) enqueue(allowMetered = false, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(allowMetered: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        scope.launch(Dispatchers.IO) {
            val running = workManager.getWorkInfosForUniqueWork(WORK_NAME).get().any { it.state == WorkInfo.State.RUNNING }
            if (!running) workManager.enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }

    /**
     * Called by [ModelDownloadWorker]. Downloads each missing file of [ModelManifest] with
     * resume (`.partial/<sha256>.part`, kept across retries and restarts) and SHA-256
     * verification. Throws on failure so the worker can retry.
     */
    internal suspend fun performDownload() = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext publishPresence()
        val downloadContext = coroutineContext
        val checkActive = { downloadContext.ensureActive() }
        val missing = ModelManifest.files.filterNot { files.isPresent(files.fileFor(it)) }
        val total = missing.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        var published = -1f
        loggedDecile = 0
        try {
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_START, "files=${missing.size} bytes=$total")
            _state.value = ModelState.Downloading(0f)
            for (source in missing) {
                retrying {
                    downloader.download(source, files.fileFor(source), partFile(source), checkActive) { bytes ->
                        val value = ((done + bytes).toFloat() / total).coerceIn(0f, 1f)
                        if (value - published >= 0.005f) {
                            published = value
                            _state.value = ModelState.Downloading(value)
                        }
                        val decile = (value * 10).toInt()
                        if (decile > loggedDecile) {
                            loggedDecile = decile
                            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_PROGRESS, "pct=${decile * 10} bytes=${done + bytes}")
                        }
                    }
                }
                done += source.size
            }
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_DONE)
            partialDir.deleteRecursively()
            publishPresence()
        } catch (e: CancellationException) {
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_FAILED, "cancelled=true")
            _state.value = if (isReady()) ModelState.Ready else ModelState.Missing
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "model download failed", e)
            // Network errors only (HTTP status, timeouts); nothing about the user is in them.
            diagnostics.log(
                DiagnosticEvents.MODEL_DOWNLOAD_FAILED,
                "error=${DiagnosticsLog.typeName(e)} message=${e.message.orEmpty().take(200)}",
            )
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Copies models pushed by a developer to the external files dir (see docs/platforms/android-phone.md). */
    private fun importSideloaded() {
        val source = context.getExternalFilesDir(null)?.let { ModelFiles(File(it, "models")) } ?: return
        val pairs = source.all.zip(files.all).filter { (from, _) -> from.isFile && from.length() > 0 }
        if (pairs.isEmpty()) return
        val temp = File(files.root, TEMP_PREFIX + "sideload").apply { mkdirs() }
        try {
            for ((from, to) in pairs) {
                val staged = File(temp, to.relativeTo(files.root).path).apply { parentFile?.mkdirs() }
                from.copyTo(staged, overwrite = true)
                to.parentFile?.mkdirs()
                moveIntoPlace(staged, to)
                from.delete()
                Log.i(TAG, "imported sideloaded ${to.name}")
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun publishPresence() {
        val ready = isReady()
        val wasReady = _state.value == ModelState.Ready
        _state.value = if (ready) ModelState.Ready else ModelState.Missing
        if (ready != wasReady) diagnostics.log(DiagnosticEvents.MODEL_READY, "ready=$ready")
        if (ready && !wasReady) onReady()
    }

    private suspend fun <T> retrying(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (++attempt >= ATTEMPTS) throw e
                Log.i(TAG, "download attempt $attempt failed, retrying", e)
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun moveIntoPlace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val WORK_NAME = "model-download"
        private const val TAG = "ModelManager"
        private const val TEMP_PREFIX = ".tmp-"
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5_000L

        private const val PARTIAL_DIR = ".partial"
    }
}
