package app.watchaudio.core.pipeline

import app.watchaudio.core.platform.PlatformLock
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * The phone's on-device event log (#77): when chunks arrived, how long transcription took, model
 * downloads, gap alerts, crashes. It lets a stall or slowdown be traced after the fact, e.g. in a
 * multi-day device test, without `adb logcat` or Xcode attached.
 *
 * Privacy: callers pass ids, counts, durations and error *types* only, never transcript text or
 * audio. The log stays on the device; it leaves only when the user exports it.
 */
interface Diagnostics {
    /** Appends one event. [detail] is free-form `key=value` pairs; never fails, never throws. */
    fun log(event: String, detail: String = "")

    companion object {
        val NONE: Diagnostics = object : Diagnostics {
            override fun log(event: String, detail: String) {}
        }
    }
}

/** Event names written to [Diagnostics]; kept stable so logs from different builds compare. */
object DiagnosticEvents {
    const val APP_START = "app_start"
    const val PREVIOUS_EXIT = "previous_exit"
    const val CRASH = "crash"

    const val CHUNK_RECEIVED = "chunk_received"
    const val CHUNK_REJECTED = "chunk_rejected"
    const val ACK_SENT = "ack_sent"
    const val ACK_FAILED = "ack_failed"

    const val WORKER_START = "worker_start"
    const val WORKER_STOP = "worker_stop"
    const val TRANSCRIPTION_STARTED = "transcription_started"
    const val TRANSCRIPTION_FINISHED = "transcription_finished"
    const val TRANSCRIPTION_FAILED = "transcription_failed"

    /** What the battery/thermal policy decided for a run, and why it paused (#75). */
    const val TRANSCRIPTION_POLICY = "transcription_policy"
    const val RETENTION_PURGE = "retention_purge"

    const val MODEL_DOWNLOAD_START = "model_download_start"
    const val MODEL_DOWNLOAD_PROGRESS = "model_download_progress"
    const val MODEL_DOWNLOAD_DONE = "model_download_done"
    const val MODEL_DOWNLOAD_FAILED = "model_download_failed"
    const val MODEL_READY = "model_ready"

    const val GAP_ALERT = "gap_alert"
    const val GAP_ALERT_BLOCKED = "gap_alert_blocked"
    const val GAP_ALERT_SCHEDULED = "gap_alert_scheduled"

    /** A deletion from the status tab failed (#33); the UI shows a fixed text, not the error (#135). */
    const val STORAGE_ACTION_FAILED = "storage_action_failed"
    const val DIAGNOSTICS_EXPORT_FAILED = "diagnostics_export_failed"

    /** The user exported their data (#155): counts only (days, chunks, audio files), never content. */
    const val DATA_EXPORTED = "data_exported"
    const val DATA_EXPORT_FAILED = "data_export_failed"

    /** An encrypted backup (#40) was written / restored: counts only, never content or the passphrase. */
    const val BACKUP_CREATED = "backup_created"
    const val BACKUP_FAILED = "backup_failed"
    const val BACKUP_RESTORED = "backup_restored"
    const val BACKUP_RESTORE_FAILED = "backup_restore_failed"

    /** A saved backup could not be recorded as holding its audio (#156): the error type only. */
    const val BACKUP_RECORD_FAILED = "backup_record_failed"

    /** What a full backup and a diff would hold (#164) could not be read: the error type only. */
    const val BACKUP_PREVIEW_FAILED = "backup_preview_failed"

    /** The user offloaded backed-up audio (#156): counts only (chunks, bytes). */
    const val AUDIO_OFFLOADED = "audio_offloaded"
}

/**
 * [Diagnostics] as a small CSV file (`timestamp,event,detail`), like the watches' logs.
 *
 * Size is capped: once [file] reaches [maxBytes] it is renamed to `<name>.1` (replacing the
 * previous one), so at most about 2 × [maxBytes] are kept. Thread-safe; I/O errors are swallowed
 * so logging can never break the caller.
 *
 * @param exportDirectory where the platform puts the copies it hands to the share sheet; [clear]
 *   empties it too. Null when the host never exports.
 */
class DiagnosticsLog(
    val file: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val exportDirectory: Path? = null,
) : Diagnostics {
    private val lock = PlatformLock()

    /** The previous, rotated-out file. */
    val previousFile: Path = Path(file.parent ?: Path("."), "${file.name}.1")

    override fun log(event: String, detail: String) {
        val line = listOf(
            Instant.fromEpochMilliseconds(clock()).toString(),
            sanitize(event),
            sanitize(detail),
        ).joinToString(",") + "\n"
        lock.withLock {
            runCatching {
                file.parent?.let { SystemFileSystem.createDirectories(it) }
                val size = SystemFileSystem.metadataOrNull(file)?.size
                if (size != null && size >= maxBytes) {
                    SystemFileSystem.delete(previousFile, mustExist = false)
                    SystemFileSystem.atomicMove(file, previousFile)
                }
                val fresh = SystemFileSystem.metadataOrNull(file) == null
                SystemFileSystem.sink(file, append = true).buffered().use { sink ->
                    if (fresh) sink.writeString(HEADER)
                    sink.writeString(line)
                }
            }
        }
    }

    /** Records an uncaught exception: types and the top stack frames only, no messages. */
    fun recordCrash(thread: String, throwable: Throwable) = log(DiagnosticEvents.CRASH, crashDetail(thread, throwable))

    /**
     * Writes the whole log (rotated part first) as one CSV to [target], for the user to share.
     * @return the number of events written; 0 means there was nothing to export.
     */
    fun exportTo(target: Path): Int = lock.withLock {
        val lines = listOf(previousFile, file).flatMap(::eventLines)
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(target).buffered().use { sink ->
            sink.writeString(HEADER)
            lines.forEach { sink.writeString(it + "\n") }
        }
        lines.size
    }

    /**
     * Deletes the log, its rotated part and earlier exports ("delete all data", #159): the log
     * records when chunks arrived, i.e. when the user was recording. The next [log] starts a new
     * file.
     * @return true if none of them is left.
     */
    fun clear(): Boolean = lock.withLock {
        val exports = exportDirectory
            ?.let { dir -> runCatching { SystemFileSystem.list(dir) }.getOrDefault(emptyList()) }
            .orEmpty()
        (listOf(file, previousFile) + exports).forEach { path ->
            runCatching { SystemFileSystem.delete(path, mustExist = false) }
        }
        (listOf(file, previousFile) + exports).none { SystemFileSystem.exists(it) }
    }

    private fun eventLines(path: Path): List<String> {
        if (SystemFileSystem.metadataOrNull(path) == null) return emptyList()
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        return text.lineSequence().filter { it.isNotBlank() && it + "\n" != HEADER }.toList()
    }

    companion object {
        const val HEADER = "timestamp,event,detail\n"
        const val DEFAULT_MAX_BYTES = 512L * 1024
        const val FILE_NAME = "diagnostics.csv"
        private const val MAX_FIELD_LENGTH = 500
        private const val CRASH_FRAMES = 6

        /** One CSV field: no separators or line breaks, bounded length. */
        internal fun sanitize(value: String): String =
            value.replace(',', ';').replace('\r', ' ').replace('\n', ' ').take(MAX_FIELD_LENGTH)

        /**
         * `thread=<name> type=<class> causes=<class>;… at=<frame> < <frame> …`. Exception messages
         * are left out on purpose: they could quote data (file names, values).
         */
        fun crashDetail(thread: String, throwable: Throwable): String {
            val causes = generateSequence(throwable.cause) { it.cause }.take(5).map { typeName(it) }.toList()
            val frames = throwable.stackTraceToString().lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("at ") }
                .map { it.removePrefix("at ") }
                .take(CRASH_FRAMES)
                .toList()
            return buildList {
                add("thread=$thread")
                add("type=${typeName(throwable)}")
                if (causes.isNotEmpty()) add("causes=${causes.joinToString(";")}")
                if (frames.isNotEmpty()) add("at=${frames.joinToString(" < ")}")
            }.joinToString(" ")
        }

        /** The exception's class, never its message. */
        fun typeName(throwable: Throwable): String = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable"

        /** Real-time factor (processing time / audio length) with two decimals, or null without audio. */
        fun realTimeFactor(elapsedMs: Long, audioMs: Long): Double? =
            if (audioMs <= 0) null else (elapsedMs * 100.0 / audioMs).roundToLong() / 100.0
    }
}

/**
 * Writes [TranscriptionPipeline] progress to [diagnostics]: chunk ids, timings and the error type
 * of a failure, never the transcript.
 */
class DiagnosticsPipelineObserver(private val diagnostics: Diagnostics) : TranscriptionPipeline.Observer {
    override fun started(chunkId: String) = diagnostics.log(DiagnosticEvents.TRANSCRIPTION_STARTED, "chunk=$chunkId")

    override fun finished(outcome: TranscriptionPipeline.ChunkOutcome) {
        val timing = buildString {
            append("chunk=${outcome.chunkId} audio_ms=${outcome.audioMs} elapsed_ms=${outcome.elapsedMs}")
            DiagnosticsLog.realTimeFactor(outcome.elapsedMs, outcome.audioMs)?.let { append(" rtf=$it") }
        }
        if (outcome.transcribed) {
            diagnostics.log(DiagnosticEvents.TRANSCRIPTION_FINISHED, timing)
        } else {
            diagnostics.log(DiagnosticEvents.TRANSCRIPTION_FAILED, "$timing error=${outcome.errorType}")
        }
    }
}
