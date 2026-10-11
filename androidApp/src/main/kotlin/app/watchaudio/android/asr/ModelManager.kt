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
import app.watchaudio.app.ModelState
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
 * downloads them from the sherpa-onnx GitHub releases through [ModelDownloadWorker]. This is a
 * download only; nothing about the user is sent.
 *
 * New files are written to a temporary directory next to the target and renamed into place, so
 * a crash never leaves a half-written model that looks complete.
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

    /** True while a download is queued, waiting for its network constraint (e.g. Wi-Fi). */
    val downloadWaiting: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }

    fun isReady(): Boolean = files.isComplete()

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
        publishPresence()
    }

    /**
     * Downloads missing models. Without [allowMetered] the download waits for an unmetered
     * network (the archive is several hundred MB); the user can opt in to mobile data.
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

    /** Called by [ModelDownloadWorker]. Throws on failure so the worker can retry. */
    internal suspend fun performDownload() = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext publishPresence()
        val downloadContext = coroutineContext
        val checkActive = { downloadContext.ensureActive() }
        val temp = File(files.root, TEMP_PREFIX + "download").apply {
            deleteRecursively()
            mkdirs()
        }
        val staged = ModelFiles(temp)
        val vadShare = if (files.hasVad()) 0f else VAD_SHARE
        loggedDecile = 0
        try {
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_START, "vad=${!files.hasVad()} asr=${!files.hasAsr()}")
            _state.value = ModelState.Downloading(0f)
            if (!files.hasVad()) {
                retrying {
                    fetch(VAD_URL) { input, total ->
                        staged.vad.outputStream().use { output ->
                            ModelArchive.copy(progress(input, total, 0f, vadShare), output, checkActive)
                        }
                    }
                }
                moveIntoPlace(staged.vad, files.vad)
            }
            if (!files.hasAsr()) {
                retrying {
                    fetch(ASR_URL) { input, total ->
                        val wanted = ModelFiles.ASR_ARCHIVE_ENTRIES.associateWith { File(temp, it) }
                        ModelArchive.extract(progress(input, total, vadShare, 1f), wanted, checkActive)
                    }
                }
                // The model directory is replaced with a single rename.
                files.asrDir.deleteRecursively()
                moveIntoPlace(staged.asrDir, files.asrDir)
            }
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_DONE)
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
        } finally {
            temp.deleteRecursively()
        }
    }

    /** Copies models pushed by a developer to the external files dir (see docs/impl/android-phone.md). */
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

    /** Wraps [input] so that reading it advances the published progress from [from] to [to]. */
    private fun progress(input: InputStream, total: Long, from: Float, to: Float): InputStream {
        if (total <= 0) return input
        var published = -1f
        return CountingInputStream(input) { read ->
            val value = from + (to - from) * (read.toFloat() / total).coerceAtMost(1f)
            if (value - published >= 0.005f) {
                published = value
                _state.value = ModelState.Downloading(value)
            }
            val decile = (value * 10).toInt()
            if (decile > loggedDecile) {
                loggedDecile = decile
                diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_PROGRESS, "pct=${decile * 10} bytes=$read")
            }
        }
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

    private fun <T> fetch(url: String, block: (InputStream, Long) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            return connection.inputStream.use { block(it, connection.contentLengthLong) }
        } finally {
            connection.disconnect()
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

        /** silero_vad.onnx is ~2 MB against hundreds of MB for the ASR archive. */
        private const val VAD_SHARE = 0.01f

        private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
        const val ASR_URL = "$RELEASES/${AsrSettings.JA_MODEL_NAME}.tar.bz2"
        const val VAD_URL = "$RELEASES/silero_vad.onnx"
    }
}
