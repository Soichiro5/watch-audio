package app.watchaudio.core.backup

import app.watchaudio.core.db.DiffChunk
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.SavedBackups
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.export.ExportPlan
import app.watchaudio.core.export.ExportProgress
import app.watchaudio.core.export.ExportRange
import app.watchaudio.core.export.ExportRequest
import app.watchaudio.core.export.ExportSelection
import app.watchaudio.core.export.ExportSummary
import app.watchaudio.core.platform.IoDispatcher
import app.watchaudio.core.platform.secureRandomBytes
import app.watchaudio.core.timeline.localDateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.io.RawSink
import kotlinx.io.files.Path

/** A backup ready to be written ([BackupWriter.plan]). */
data class BackupPlan(
    /** What the archive holds: everything, or for a diff only [ExportPlan.selection]. */
    val export: ExportPlan,
    /** The backup epoch the backup covers (`BackupQueries.beginBackup`), recorded once it is saved. */
    val epoch: Long,
) {
    val kind: BackupKind get() = if (export.selection != null) BackupKind.DIFF else BackupKind.FULL

    /** When the backup is made (its `createdAt`). */
    val createdAt: Long get() = export.request.exportedAt
}

/** What the backup card shows before the user makes a backup (#164); sizes are estimates. */
data class BackupPreview(
    /** The backups saved on this device so far. */
    val saved: SavedBackups,
    /** Chunks a full backup holds. */
    val fullChunks: Int,
    /** About how large a full backup would be. */
    val fullBytes: Long,
    /** What a diff would hold now, or null when no diff can be made ([SavedBackups.diffPossible]). */
    val diff: DiffPreview?,
)

/** What a diff backup would hold (#164). */
data class DiffPreview(
    /** Chunks added or changed since the last saved backup (and, with audio, not yet backed up). */
    val chunks: Int,
    /** Audio files it would write: those in no saved backup yet. */
    val audioFiles: Int,
    /** About how large the file would be. */
    val bytes: Long,
)

/**
 * Writes an encrypted backup (#40) of the device's data: the export archive ([DataExporter] with
 * [ExportRequest.forBackup]) encrypted with a key derived from the user's passphrase
 * ([BackupCipher]). A full backup holds every day; a diff (#164) only the chunks that changed
 * since the last backup the user saved, and the audio that was in no saved backup yet. The sink is
 * the place the user picked; the passphrase and the keys stay in memory on this device and are not
 * stored anywhere.
 */
class BackupWriter(
    private val store: LocalStore,
    audioRoot: Path,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
    /** PBKDF2 rounds of the backups it writes; only tests lower it. */
    private val iterations: Int = BackupCipher.DEFAULT_ITERATIONS,
) {
    private val exporter = DataExporter(store, audioRoot, dispatcher)

    /** What a full backup and a diff would hold now, with audio if [includeAudio]. Changes nothing. */
    suspend fun preview(includeAudio: Boolean): BackupPreview = store.read {
        val saved = savedBackups()
        val stats = stats()
        val diff = saved.lastEpoch?.takeIf { saved.diffPossible }?.let { diffChunks(it, includeAudio) }
        BackupPreview(
            saved = saved,
            fullChunks = stats.chunks,
            fullBytes = estimatedBytes(stats.chunks, stats.segments, if (includeAudio) stats.audioBytes else 0),
            diff = diff?.let { chunks ->
                val audio = chunks.mapNotNull { it.audioBytes }
                DiffPreview(chunks.size, audio.size, estimatedBytes(chunks.size, chunks.sumOf { it.segments }, audio.sum()))
            },
        )
    }

    /**
     * The backup of [kind] made at [now], with audio if [includeAudio]; null when there is nothing
     * to back up (for a diff: nothing changed since the last saved backup). [timeZone] only decides
     * how the days are grouped inside the file. Ends the current backup epoch, so every later
     * change goes into the next diff.
     * @throws IllegalStateException for a diff when none can be made ([SavedBackups.diffPossible]).
     */
    suspend fun plan(includeAudio: Boolean, timeZone: TimeZone, now: Long, kind: BackupKind = BackupKind.FULL): BackupPlan? {
        val request = ExportRequest(ExportRange.ALL, includeAudio, timeZone, now, forBackup = true)
        if (kind == BackupKind.FULL) {
            val epoch = store.transaction { beginBackup() }
            return exporter.plan(request)?.let { BackupPlan(it, epoch) }
        }
        val (epoch, saved, chunks) = store.transaction {
            val saved = savedBackups()
            check(saved.diffPossible) { "no saved backup to build a diff on" }
            Triple(beginBackup(), saved, diffChunks(saved.lastEpoch!!, includeAudio))
        }
        if (chunks.isEmpty()) return null
        val days = chunks.map { localDateOf(it.startedAt, timeZone) }.distinct().sorted()
        val selection = ExportSelection(
            days = days,
            chunks = chunks.mapTo(mutableSetOf()) { it.id },
            audio = chunks.filter { it.audioBytes != null }.mapTo(mutableSetOf(), DiffChunk::id),
            base = BackupBase(createdAt = saved.lastAt!!, fullCreatedAt = saved.fullAt!!, diffsBefore = saved.diffsSinceFull),
        )
        return BackupPlan(ExportPlan(request, days.first(), days.last(), selection), epoch)
    }

    /**
     * Encrypts [plan] with [passphrase] into [sink] (flushed, not closed). Throws if the store,
     * an audio file or the sink fails; the partial file then has no final chunk and is rejected
     * when restored, and the caller discards it.
     *
     * @param chunkSize plaintext bytes per authenticated chunk; only tests lower it.
     * @param random salt and nonce source; only tests fix it.
     */
    suspend fun write(
        plan: BackupPlan,
        passphrase: String,
        sink: RawSink,
        onProgress: (ExportProgress) -> Unit = {},
        chunkSize: Int = BackupCipher.DEFAULT_CHUNK_SIZE,
        random: (Int) -> ByteArray = ::secureRandomBytes,
    ): ExportSummary {
        require(plan.export.request.forBackup) { "not a backup plan" }
        val encrypting = withContext(dispatcher) { BackupCipher.encrypt(sink, passphrase, iterations, chunkSize, random) }
        try {
            val summary = exporter.write(plan.export, encrypting, onProgress)
            withContext(dispatcher) { encrypting.finish() }
            return summary
        } finally {
            encrypting.close()
        }
    }

    /**
     * Records that the backup of [plan] was saved: the audio files [summary] wrote are in it
     * (#156), so that they may be offloaded and later diffs leave them out, and it is the backup
     * the next diff builds on (#164). Call it only once the platform reports that the file reached
     * the place the user chose; a backup that was cancelled or failed must not count. Marks the
     * audio in batches, so a long backup does not hold the database for long, and records the
     * backup last, so a failure part way leaves the next diff based on the previous one.
     * @return number of chunks whose audio was marked.
     */
    suspend fun recordSaved(plan: BackupPlan, summary: ExportSummary): Int {
        val marked = summary.audioChunkIds.chunked(RECORD_BATCH_SIZE).sumOf { ids ->
            store.transaction { markAudioBackedUp(ids, plan.createdAt) }
        }
        store.transaction { recordBackupSaved(plan.createdAt, plan.kind == BackupKind.DIFF, plan.epoch) }
        return marked
    }

    companion object {
        private const val RECORD_BATCH_SIZE = 500

        /** Archive bytes per chunk besides its segments: its JSON in the day and backup files, and Markdown. */
        private const val BYTES_PER_CHUNK = 1_500L

        /** Archive bytes per transcript segment: JSON and a Markdown line. */
        private const val BYTES_PER_SEGMENT = 300L

        private fun estimatedBytes(chunks: Int, segments: Int, audioBytes: Long): Long =
            if (chunks == 0) 0 else chunks * BYTES_PER_CHUNK + segments * BYTES_PER_SEGMENT + audioBytes

        /**
         * `watch-audio-backup-2026-10-12.wabackup` for a full backup made on that day in [timeZone],
         * `watch-audio-backup-2026-10-12-diff.wabackup` for a diff.
         */
        fun fileName(now: Long, timeZone: TimeZone, kind: BackupKind = BackupKind.FULL): String {
            val suffix = if (kind == BackupKind.DIFF) "-diff" else ""
            return "watch-audio-backup-${localDateOf(now, timeZone)}$suffix.$BACKUP_FILE_EXTENSION"
        }
    }
}
