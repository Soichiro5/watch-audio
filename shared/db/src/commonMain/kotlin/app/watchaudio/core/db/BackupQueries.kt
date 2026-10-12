package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/**
 * The encrypted backups the user saved on this device (#164), as far as the app knows: it
 * cannot see the files afterwards, only that the platform reported them saved.
 */
data class SavedBackups(
    /** Creation time of the last saved backup (full or diff), or null if none was saved. */
    val lastAt: Long? = null,
    /** The last saved backup was a diff. */
    val lastIsDiff: Boolean = false,
    /** The backup epoch the last saved backup covers; a diff holds the changes after it. */
    val lastEpoch: Long? = null,
    /** Creation time of the full backup the last saved one builds on (the last one itself if full). */
    val fullAt: Long? = null,
    /** Diffs saved since the full backup [fullAt]. */
    val diffsSinceFull: Int = 0,
    /**
     * A chunk was deleted after the full backup [fullAt] was planned. A diff cannot carry a
     * deletion (restoring the full backup would bring the data back), so the next backup is full.
     */
    val deletedSinceFull: Boolean = false,
) {
    /** A diff can be made: a backup was saved and nothing was deleted since its full backup. */
    val diffPossible: Boolean get() = lastEpoch != null && fullAt != null && !deletedSinceFull
}

/** A chunk that a diff backup would hold (#164). */
data class DiffChunk(
    /** The chunk's UUID. */
    val id: String,
    val startedAt: Long,
    /** Size of the audio file the diff would write, or null when its audio does not go in. */
    val audioBytes: Long?,
    /** Transcript segments of the chunk (to estimate the size of the file). */
    val segments: Int,
)

/**
 * Change tracking for diff backups (#164). Triggers (migration 8) stamp every changed chunk with
 * the current backup epoch and a version time; these queries end epochs, list what changed, and
 * remember what the user saved.
 */
interface BackupQueries {
    /** What the device knows about the saved backups. */
    fun savedBackups(): SavedBackups

    /**
     * Ends the current backup epoch for a backup that is about to be written and returns it:
     * every change after this call lands in a later epoch, so a diff based on this backup
     * includes it even if the backup itself (written afterwards) already had it.
     */
    fun beginBackup(): Long

    /**
     * Records that the backup created at [createdAt] (a diff if [diff]), covering the epoch
     * [epoch] from [beginBackup], reached the place the user chose. Ignored if a backup created
     * later was already recorded.
     */
    fun recordBackupSaved(createdAt: Long, diff: Boolean, epoch: Long)

    /**
     * Chunks a diff after epoch [afterEpoch] holds, oldest first: those whose record changed
     * after it, and with [withAudio] also those whose audio is on the phone but in no saved
     * backup ([ChunkRecord.audioBackedUpAt] is null), whose audio the diff then writes.
     */
    fun diffChunks(afterEpoch: Long, withAudio: Boolean): List<DiffChunk>

    /** Sets the version time of the stored chunk [chunkId] (from a backup it was restored from). */
    fun setChunkVersion(chunkId: String, updatedAt: Long?)
}

internal class BackupSql(private val db: SQLiteConnection) : BackupQueries {

    override fun savedBackups(): SavedBackups = db.queryOne(
        """
        SELECT saved_at, saved_diff, saved_epoch, full_saved_at, diffs_since_full, full_epoch IS NOT NULL AND deleted_epoch > full_epoch
        FROM backup_state WHERE id = 1
        """.trimIndent(),
    ) {
        SavedBackups(
            lastAt = if (isNull(0)) null else getLong(0),
            lastIsDiff = getLong(1) != 0L,
            lastEpoch = if (isNull(2)) null else getLong(2),
            fullAt = if (isNull(3)) null else getLong(3),
            diffsSinceFull = getInt(4),
            deletedSinceFull = getLong(5) != 0L,
        )
    } ?: SavedBackups()

    override fun beginBackup(): Long {
        val epoch = db.queryOne("SELECT epoch FROM backup_state WHERE id = 1") { getLong(0) }!!
        db.execute("UPDATE backup_state SET epoch = ? WHERE id = 1", epoch + 1)
        return epoch
    }

    override fun recordBackupSaved(createdAt: Long, diff: Boolean, epoch: Long) {
        db.execute(
            """
            UPDATE backup_state SET
                saved_at = ?1,
                saved_diff = ?2,
                saved_epoch = ?3,
                full_saved_at = CASE WHEN ?2 THEN full_saved_at ELSE ?1 END,
                full_epoch = CASE WHEN ?2 THEN full_epoch ELSE ?3 END,
                diffs_since_full = CASE WHEN ?2 THEN diffs_since_full + 1 ELSE 0 END
            WHERE id = 1 AND (saved_at IS NULL OR saved_at <= ?1)
            """.trimIndent(),
            createdAt,
            if (diff) 1 else 0,
            epoch,
        )
    }

    override fun diffChunks(afterEpoch: Long, withAudio: Boolean): List<DiffChunk> {
        val audio = "c.audio_path IS NOT NULL AND c.audio_purged_at IS NULL AND c.audio_backed_up_at IS NULL"
        val withAudioFlag = if (withAudio) 1 else 0
        return db.queryList(
            """
            SELECT c.uuid, c.started_at, CASE WHEN ?2 AND $audio THEN COALESCE(c.size_bytes, 0) END,
                (SELECT COUNT(*) FROM transcript_segment s WHERE s.chunk_id = c.id)
            FROM audio_chunk c
            WHERE c.change_epoch > ?1 OR (?2 AND $audio)
            ORDER BY c.started_at, c.id
            """.trimIndent(),
            afterEpoch,
            withAudioFlag,
        ) { DiffChunk(getText(0), getLong(1), if (isNull(2)) null else getLong(2), getInt(3)) }
    }

    override fun setChunkVersion(chunkId: String, updatedAt: Long?) {
        db.execute("UPDATE audio_chunk SET updated_at = ? WHERE uuid = ?", updatedAt, chunkId)
    }
}
