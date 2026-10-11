package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/** Store-wide counters, audio retention and deletion (#33). */
interface StorageQueries {
    fun stats(): StoreStats

    /**
     * Transcribed chunks whose audio file still exists and that ended at or before [endedBefore],
     * oldest first: the candidates for deleting audio under a retention setting. Untranscribed
     * chunks are never returned.
     */
    fun purgeableChunks(endedBefore: Long, limit: Int): List<ChunkRecord>

    /** Count and size of all [purgeableChunks]. */
    fun purgeableUsage(endedBefore: Long): AudioUsage

    /**
     * Records that the audio files of [ids] were deleted at [at]. Only transcribed chunks are
     * marked, so a chunk can never lose its audio before it has a transcript.
     * @return number of chunks marked.
     */
    fun markAudioPurged(ids: List<String>, at: Long): Int

    /**
     * Deletes chunks with their transcripts and marks (the search index follows through the
     * triggers). Deleting the audio files is the caller's job.
     * @return number of chunks deleted.
     */
    fun deleteChunks(ids: List<String>): Int

    /** Deletes every chunk, transcript and mark; settings are kept. @return number of chunks deleted. */
    fun deleteAllChunks(): Int

    /** Relative paths of all audio files that should exist (not purged). */
    fun audioPaths(): Set<String>
}

internal class StorageSql(private val db: SQLiteConnection) : StorageQueries {

    override fun stats(): StoreStats = db.queryOne(
        """
        SELECT
            (SELECT COUNT(*) FROM audio_chunk),
            (SELECT COUNT(*) FROM audio_chunk WHERE state IN (${ChunkState.RECEIVED.sql}, ${ChunkState.TRANSCRIBING.sql})),
            (SELECT COUNT(*) FROM audio_chunk WHERE state = ${ChunkState.TRANSCRIBED.sql}),
            (SELECT COUNT(*) FROM audio_chunk WHERE state = ${ChunkState.FAILED.sql}),
            (SELECT COUNT(*) FROM transcript_segment),
            (SELECT COALESCE(SUM(size_bytes), 0) FROM audio_chunk WHERE audio_path IS NOT NULL AND audio_purged_at IS NULL),
            (SELECT COALESCE(SUM(ended_at - started_at), 0) FROM audio_chunk WHERE audio_path IS NOT NULL),
            (SELECT MAX(received_at) FROM audio_chunk),
            (SELECT COUNT(*) FROM audio_chunk WHERE audio_purged_at IS NOT NULL)
        """.trimIndent(),
    ) {
        StoreStats(
            chunks = getInt(0),
            pendingChunks = getInt(1),
            transcribedChunks = getInt(2),
            failedChunks = getInt(3),
            segments = getInt(4),
            audioBytes = getLong(5),
            recordedMs = getLong(6),
            lastReceivedAt = if (isNull(7)) null else getLong(7),
            audioPurgedChunks = getInt(8),
        )
    }!!

    override fun purgeableChunks(endedBefore: Long, limit: Int): List<ChunkRecord> = db.queryList(
        "SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE $PURGEABLE ORDER BY ended_at LIMIT ?",
        endedBefore,
        limit,
    ) { toChunk() }

    override fun purgeableUsage(endedBefore: Long): AudioUsage = db.queryOne(
        "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM audio_chunk WHERE $PURGEABLE",
        endedBefore,
    ) { AudioUsage(chunks = getInt(0), bytes = getLong(1)) }!!

    override fun markAudioPurged(ids: List<String>, at: Long): Int = ids.sumOf { id ->
        db.update(
            "UPDATE audio_chunk SET audio_purged_at = ? WHERE uuid = ? AND state = ${ChunkState.TRANSCRIBED.sql} AND audio_purged_at IS NULL",
            at,
            id,
        )
    }

    override fun deleteChunks(ids: List<String>): Int = ids.sumOf { id -> db.update("DELETE FROM audio_chunk WHERE uuid = ?", id) }

    override fun deleteAllChunks(): Int = db.update("DELETE FROM audio_chunk")

    override fun audioPaths(): Set<String> = db.queryList(
        "SELECT audio_path FROM audio_chunk WHERE audio_path IS NOT NULL AND audio_purged_at IS NULL",
    ) { getText(0) }.toSet()

    private companion object {
        /** Transcribed, audio still present, ended at or before the bound parameter. */
        val PURGEABLE =
            "audio_path IS NOT NULL AND audio_purged_at IS NULL AND ended_at <= ? AND state = ${ChunkState.TRANSCRIBED.sql}"
    }
}
