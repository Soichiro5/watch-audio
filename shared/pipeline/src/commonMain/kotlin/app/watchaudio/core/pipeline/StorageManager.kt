package app.watchaudio.core.pipeline

import app.watchaudio.core.db.AudioUsage
import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.platform.IoDispatcher
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Storage facts only the platform knows. Implemented by each phone app. */
interface StorageEnvironment {
    /** Bytes used by downloaded models, or null when the OS manages them (iOS Speech assets). */
    fun modelBytes(): Long?

    /** Bytes still available to the app on the volume holding its data, or null if unknown. */
    fun freeBytes(): Long?

    /** For tests and hosts without a platform behind them. */
    object Unknown : StorageEnvironment {
        override fun modelBytes(): Long? = null
        override fun freeBytes(): Long? = null
    }
}

/** Where the phone's storage goes. */
data class StorageBreakdown(
    /** Audio files still on the device (from the database; purged audio excluded). */
    val audioBytes: Long,
    /** The database: transcripts, search index and metadata. */
    val databaseBytes: Long,
    /** Downloaded models, or null when the OS manages them. */
    val modelBytes: Long?,
    /** Free space on the device, or null if unknown. */
    val freeBytes: Long?,
    /** Chunks whose audio was deleted by the retention setting (their transcript is kept). */
    val audioPurgedChunks: Int,
) {
    val totalBytes: Long get() = audioBytes + databaseBytes + (modelBytes ?: 0)

    /** True when the device is about to run out of space for new recordings. */
    val isLowOnSpace: Boolean get() = freeBytes != null && freeBytes < LOW_FREE_SPACE_BYTES

    companion object {
        /** Warn below 1 GB free (about a week of audio at the default bitrate). */
        const val LOW_FREE_SPACE_BYTES = 1L shl 30
    }
}

/**
 * Storage management (#33): applies the audio retention setting, reports usage and deletes data
 * on request. "Keep everything" is the default: transcripts are never deleted by retention, and
 * audio only once it is transcribed and older than the user's [AudioRetention].
 *
 * Audio files are deleted before the database records it, so an interrupted run leaves at most
 * a chunk marked as having audio whose file is gone; the next run marks it.
 *
 * Destructive calls ([deleteRange], [deleteAll], a shorter [setRetention]) do no confirming
 * themselves: the UI must ask the user first.
 *
 * @param diagnostics the phone's diagnostics log, which [deleteAll] clears as well (#159).
 */
class StorageManager(
    private val store: LocalStore,
    private val audioRoot: Path,
    private val environment: StorageEnvironment = StorageEnvironment.Unknown,
    private val clock: Clock = Clock.System,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
    private val diagnostics: DiagnosticsLog? = null,
) {
    /** Serializes deletions within the process (background run vs. UI). */
    private val lock = Mutex()

    suspend fun retention(): AudioRetention =
        AudioRetention.fromSetting(store.read { setting(AudioRetention.SETTING_KEY) })

    /** Stores the setting only; call [applyRetention] to delete what it no longer keeps. */
    suspend fun setRetention(retention: AudioRetention) {
        store.transaction { putSetting(AudioRetention.SETTING_KEY, retention.name) }
    }

    /** What [applyRetention] would delete right now under [retention] (for the confirmation). */
    suspend fun previewRetention(retention: AudioRetention): AudioUsage {
        val cutoff = AudioRetentionPolicy.cutoff(retention, now()) ?: return AudioUsage(0, 0)
        return store.read { purgeableUsage(cutoff) }
    }

    /**
     * Deletes the audio files of transcribed chunks older than the retention setting and records
     * that they are gone; transcripts stay. Stops between batches once [shouldContinue] is false.
     */
    suspend fun applyRetention(shouldContinue: () -> Boolean = { true }): PurgeResult = lock.withLock {
        val retention = retention()
        val now = now()
        val cutoff = AudioRetentionPolicy.cutoff(retention, now) ?: return@withLock PurgeResult(0, 0)
        val failed = mutableSetOf<String>()
        var purged = 0
        var bytes = 0L
        while (shouldContinue()) {
            val batch = store.read { purgeableChunks(cutoff, limit = failed.size + BATCH_SIZE) }
                .filter { it.id !in failed && AudioRetentionPolicy.shouldPurge(it, retention, now) }
            if (batch.isEmpty()) break
            val deleted = withContext(dispatcher) {
                batch.filter { chunk ->
                    deleteAudioFile(chunk).also { ok -> if (!ok) failed += chunk.id }
                }
            }
            if (deleted.isEmpty()) continue
            purged += store.transaction { markAudioPurged(deleted.map { it.id }, now) }
            bytes += deleted.sumOf { it.sizeBytes ?: 0 }
        }
        PurgeResult(purgedChunks = purged, freedBytes = bytes)
    }

    /** Current usage; touches the file system, so call it from a refresh, not per frame. */
    suspend fun breakdown(): StorageBreakdown {
        val stats = store.read { stats() }
        val databaseBytes = store.sizeOnDisk()
        return withContext(dispatcher) {
            StorageBreakdown(
                audioBytes = stats.audioBytes,
                databaseBytes = databaseBytes,
                modelBytes = environment.modelBytes(),
                freeBytes = environment.freeBytes(),
                audioPurgedChunks = stats.audioPurgedChunks,
            )
        }
    }

    /**
     * Deletes the chunks that started in `[from, to)` (e.g. one local day) with their audio,
     * transcripts and marks.
     * @return number of chunks deleted.
     */
    suspend fun deleteRange(from: Long, to: Long): Int = lock.withLock {
        val chunks = store.read { chunksStartedBetween(from, to) }
        if (chunks.isEmpty()) return@withLock 0
        // Files first: if interrupted, the rows are still there and the day can be deleted again.
        withContext(dispatcher) { chunks.forEach { deleteAudioFile(it) } }
        val deleted = store.transaction { deleteChunks(chunks.map { it.id }) }
        withContext(dispatcher) { chunks.mapNotNull { it.audioPath }.distinct().forEach { removeEmptyParents(it) } }
        deleted
    }

    /**
     * Deletes every recording and transcript, including audio files no row refers to any more,
     * shrinks the database file and clears the [diagnostics] log (it records when chunks arrived,
     * i.e. when the user was recording; #159). Settings are kept, so the app does not start the
     * onboarding again.
     */
    suspend fun deleteAll(): Unit = lock.withLock {
        store.transaction { deleteAllChunks() }
        withContext(dispatcher) { sweepUnreferencedAudio(keep = store.read { audioPaths() }) }
        store.compact()
        withContext(dispatcher) { diagnostics?.clear() }
    }

    /** @return false only if the file exists and could not be deleted. */
    private fun deleteAudioFile(chunk: ChunkRecord): Boolean {
        val relative = chunk.audioPath ?: return true
        return try {
            SystemFileSystem.delete(Path(audioRoot, relative), mustExist = false)
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Removes the date folders (yyyy/MM/dd) of [relativePath] that became empty. */
    private fun removeEmptyParents(relativePath: String) {
        var dir = Path(audioRoot, relativePath).parent
        while (dir != null && dir != audioRoot && dir.toString().startsWith(audioRoot.toString())) {
            val empty = runCatching { SystemFileSystem.list(dir).isEmpty() }.getOrDefault(false)
            if (!empty) return
            runCatching { SystemFileSystem.delete(dir, mustExist = false) }
            dir = dir.parent
        }
    }

    /**
     * Deletes audio files under the archive's year folders that [keep] does not list (relative
     * paths), and the folders left empty. Only `yyyy/` folders are touched, so other data that
     * shares the root (e.g. the database in a test or preview host) is never affected.
     */
    private fun sweepUnreferencedAudio(keep: Set<String>) {
        if (audioRoot.toString().isEmpty() || !SystemFileSystem.exists(audioRoot)) return
        SystemFileSystem.list(audioRoot)
            .filter { it.name.length == 4 && it.name.all(Char::isDigit) }
            .forEach { year -> sweep(year, year.name, keep) }
    }

    private fun sweep(path: Path, relative: String, keep: Set<String>) {
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) {
            SystemFileSystem.list(path).forEach { sweep(it, "$relative/${it.name}", keep) }
            if (SystemFileSystem.list(path).isEmpty()) runCatching { SystemFileSystem.delete(path, mustExist = false) }
        } else if (relative !in keep) {
            runCatching { SystemFileSystem.delete(path, mustExist = false) }
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    data class PurgeResult(val purgedChunks: Int, val freedBytes: Long)

    private companion object {
        const val BATCH_SIZE = 100
    }
}
