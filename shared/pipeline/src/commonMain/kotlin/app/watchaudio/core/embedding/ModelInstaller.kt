package app.watchaudio.core.embedding

import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.platform.IoDispatcher
import app.watchaudio.core.platform.PlatformLock
import app.watchaudio.core.platform.sha256Of
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One file of a model the app downloads: [url] (download only, nothing is sent), its [path] under
 * the models root, the exact [size] in bytes and its lowercase hex [sha256].
 */
data class ModelDownload(val url: String, val path: String, val size: Long, val sha256: String)

/**
 * The platform's HTTP download of one file (iOS: `URLSession` restricted to Wi-Fi, #178). Only
 * fetches; nothing is uploaded.
 */
interface ModelFetcher {
    /**
     * Downloads [url] into [part]. With [offset] > 0, [part] already holds the file's first
     * [offset] bytes and the fetcher asks for the rest (an HTTP range); a server that sends the
     * whole file instead makes it start [part] over. [onProgress] gets the bytes in [part] so far,
     * [onWaiting] that the download waits for an allowed network (Wi-Fi). Returns once the server
     * sent everything.
     *
     * @throws ModelFetchException when the download failed (the bytes so far stay in [part]).
     */
    suspend fun fetch(url: String, part: Path, offset: Long, onProgress: (Long) -> Unit, onWaiting: () -> Unit)
}

/** A download failed; [message] is a short code without URLs or user data (e.g. `http_404`). */
class ModelFetchException(message: String) : Exception(message)

/**
 * Downloads a model's [files] into [root] (#178, iOS; Android has its own `ModelManager` on
 * WorkManager): each file goes to `<path>.part` first, continues from there after an
 * interruption, and is moved into place only once its size and SHA-256 match. [state] is what the
 * search screen shows (`Downloading` with the progress, `Missing` while waiting for Wi-Fi).
 *
 * [requestDownload] starts it in [scope] unless the files are there, it is already running, or the
 * last attempt failed less than [retryAfterMs] ago (the search screen asks every few seconds).
 *
 * @param label names the model in the diagnostics log (`model=<label>`).
 * @param onReady runs once the files were installed by this instance.
 */
class ModelInstaller(
    private val files: List<ModelDownload>,
    private val root: Path,
    private val fetcher: ModelFetcher,
    private val scope: CoroutineScope,
    private val diagnostics: Diagnostics = Diagnostics.NONE,
    private val label: String = "embedding",
    private val retryAfterMs: Long = 60_000L,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
    private val onReady: () -> Unit = {},
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Unknown)

    /** The download's state; [currentState] also looks at the files. */
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val lock = PlatformLock()
    private val mutex = Mutex()
    private var job: Job? = null
    private var failedAt: Long? = null

    /** Total bytes of [files]. */
    val totalBytes: Long = files.sumOf { it.size }

    /** Where [file] is installed. */
    fun fileOf(file: ModelDownload): Path = Path(root, file.path)

    private fun partOf(file: ModelDownload): Path = Path(root, file.path + PART_SUFFIX)

    private fun installed(file: ModelDownload): Boolean = SystemFileSystem.metadataOrNull(fileOf(file))?.size == file.size

    /** Whether every file is in place (with the size it had when its hash was checked). */
    fun isReady(): Boolean = files.all(::installed)

    /** [ModelState.Ready] once the files are there, the download's state otherwise. */
    fun currentState(): ModelState = if (isReady()) {
        ModelState.Ready
    } else {
        _state.value.let { if (it == ModelState.Ready || it == ModelState.Unknown) ModelState.Missing else it }
    }

    /** Starts the download in the background if it is needed and allowed now (see the class). */
    fun requestDownload() {
        if (isReady()) {
            _state.value = ModelState.Ready
            return
        }
        lock.withLock {
            val running = job?.isActive == true
            val backingOff = failedAt?.let { clock() - it < retryAfterMs } == true
            if (!running && !backingOff) job = scope.launch { install() }
        }
    }

    /**
     * Downloads, checks and installs the missing files; one call at a time.
     * @return whether every file is installed afterwards.
     */
    suspend fun install(): Boolean = mutex.withLock {
        if (isReady()) {
            _state.value = ModelState.Ready
            return@withLock true
        }
        try {
            withContext(dispatcher) { downloadMissing() }
            lock.withLock { failedAt = null }
            _state.value = ModelState.Ready
            diagnostics.log(DiagnosticEvents.MODEL_READY, "model=$label ready=true")
            onReady()
            true
        } catch (e: CancellationException) {
            _state.value = ModelState.Missing
            throw e
        } catch (e: Exception) {
            lock.withLock { failedAt = clock() }
            val reason = (e as? ModelFetchException)?.message ?: DiagnosticsLog.typeName(e)
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_FAILED, "model=$label reason=${reason.take(80)}")
            _state.value = ModelState.Failed(reason)
            false
        }
    }

    private suspend fun downloadMissing() {
        var done = files.filter(::installed).sumOf { it.size }
        diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_START, "model=$label bytes=${totalBytes - done}")
        for (file in files) {
            if (installed(file)) continue
            val target = fileOf(file)
            target.parent?.let { SystemFileSystem.createDirectories(it) }
            val part = partOf(file)
            var have = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
            if (have > file.size) {
                SystemFileSystem.delete(part)
                have = 0L
            }
            val before = done
            _state.value = ModelState.Downloading(progress(before + have))
            if (have < file.size) {
                fetcher.fetch(
                    file.url,
                    part,
                    have,
                    onProgress = { bytes -> _state.value = ModelState.Downloading(progress(before + bytes)) },
                    onWaiting = { _state.value = ModelState.Missing },
                )
            }
            val size = SystemFileSystem.metadataOrNull(part)?.size
            if (size != file.size || sha256Of(part) != file.sha256) {
                // Wrong bytes cannot be continued: the next attempt starts this file over.
                SystemFileSystem.delete(part, mustExist = false)
                throw ModelFetchException(if (size != file.size) "size_mismatch" else "sha256_mismatch")
            }
            SystemFileSystem.atomicMove(part, target)
            done += file.size
            diagnostics.log(DiagnosticEvents.MODEL_DOWNLOAD_DONE, "model=$label file=${file.path.substringAfterLast('/')} bytes=${file.size}")
        }
    }

    private fun progress(bytes: Long): Float = if (totalBytes == 0L) 1f else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    companion object {
        /** Suffix of a file being downloaded. */
        const val PART_SUFFIX = ".part"
    }
}
