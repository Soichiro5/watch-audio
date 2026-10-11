package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.toJson

/** Stored chunks: ingest, lookup by time or session, and the transcription queue. */
interface ChunkQueries {
    /**
     * Inserts a chunk unless one with the same id exists.
     * @return true if inserted, false if it was a duplicate.
     */
    fun insertChunk(manifest: ChunkManifest, audioPath: String?, receivedAt: Long): Boolean

    fun chunk(id: String): ChunkRecord?

    /** Chunks overlapping `[from, to)`, oldest first. */
    fun chunksBetween(from: Long, to: Long): List<ChunkRecord>

    /** Chunks that started in `[from, to)` (so each chunk belongs to exactly one day), oldest first. */
    fun chunksStartedBetween(from: Long, to: Long): List<ChunkRecord>

    /** The chunk that started last before [at] (the newest chunk by default), if any. */
    fun lastChunkBefore(at: Long = Long.MAX_VALUE): ChunkRecord?

    /** The chunk of the same session and device that started last before [chunk], if any. */
    fun previousInSession(chunk: ChunkRecord): ChunkRecord?

    /** The chunk of the same session and device that started first after [chunk], if any. */
    fun nextInSession(chunk: ChunkRecord): ChunkRecord?

    /** When the most recent chunk arrived on this phone (epoch ms), or null if none yet. */
    fun lastReceivedAt(): Long?

    /** Chunks waiting for transcription (new or retryable failures), oldest first. */
    fun pendingChunks(limit: Int, maxAttempts: Int): List<ChunkRecord>

    fun markTranscribing(id: String)

    fun markFailed(id: String, error: String)

    /**
     * Puts failed chunks back in the queue with a fresh attempt budget (user-initiated retry).
     * @return number of chunks re-queued.
     */
    fun requeueFailed(ids: List<String>): Int

    /** Puts chunks left in TRANSCRIBING by a killed process back in the queue. */
    fun resetInterrupted(): Int
}

internal class ChunkSql(private val db: SQLiteConnection) : ChunkQueries {

    override fun insertChunk(manifest: ChunkManifest, audioPath: String?, receivedAt: Long): Boolean {
        val state = if (audioPath == null) ChunkState.NO_AUDIO else ChunkState.RECEIVED
        val inserted = db.update(
            """
            INSERT OR IGNORE INTO audio_chunk (
                id, session_id, device_id, source, started_at, ended_at, time_zone,
                audio_path, codec, size_bytes, sha256, end_reason, state, received_at, manifest_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            manifest.chunkId,
            manifest.sessionId,
            manifest.deviceId,
            manifest.source.name,
            manifest.startedAtEpochMs,
            manifest.endedAtEpochMs,
            manifest.timeZone,
            audioPath,
            manifest.audio?.codec?.name,
            manifest.audio?.sizeBytes,
            manifest.audio?.sha256,
            manifest.endReason.name,
            state.name,
            receivedAt,
            manifest.toJson(),
        ) > 0
        if (inserted) {
            manifest.markOffsetsMs.forEach { offset ->
                db.execute(
                    "INSERT OR IGNORE INTO mark (chunk_id, at) VALUES (?, ?)",
                    manifest.chunkId,
                    manifest.startedAtEpochMs + offset,
                )
            }
        }
        return inserted
    }

    override fun chunk(id: String): ChunkRecord? =
        db.queryOne("SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE id = ?", id) { toChunk() }

    override fun chunksBetween(from: Long, to: Long): List<ChunkRecord> = db.queryList(
        "SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE started_at < ? AND ended_at > ? ORDER BY started_at",
        to,
        from,
    ) { toChunk() }

    override fun chunksStartedBetween(from: Long, to: Long): List<ChunkRecord> = db.queryList(
        "SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE started_at >= ? AND started_at < ? ORDER BY started_at",
        from,
        to,
    ) { toChunk() }

    override fun lastChunkBefore(at: Long): ChunkRecord? = db.queryOne(
        "SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE started_at < ? ORDER BY started_at DESC LIMIT 1",
        at,
    ) { toChunk() }

    override fun previousInSession(chunk: ChunkRecord): ChunkRecord? = neighbourInSession(chunk, before = true)

    override fun nextInSession(chunk: ChunkRecord): ChunkRecord? = neighbourInSession(chunk, before = false)

    private fun neighbourInSession(chunk: ChunkRecord, before: Boolean): ChunkRecord? {
        val (comparison, order) = if (before) "<" to "DESC" else ">" to "ASC"
        return db.queryOne(
            """
            SELECT $CHUNK_COLUMNS FROM audio_chunk
            WHERE session_id = ? AND device_id = ? AND started_at $comparison ?
            ORDER BY started_at $order LIMIT 1
            """.trimIndent(),
            chunk.sessionId,
            chunk.deviceId,
            chunk.startedAt,
        ) { toChunk() }
    }

    override fun lastReceivedAt(): Long? =
        db.queryOne("SELECT MAX(received_at) FROM audio_chunk") { if (isNull(0)) null else getLong(0) }

    override fun pendingChunks(limit: Int, maxAttempts: Int): List<ChunkRecord> = db.queryList(
        """
        SELECT $CHUNK_COLUMNS FROM audio_chunk
        WHERE state = ${ChunkState.RECEIVED.sql} OR (state = ${ChunkState.FAILED.sql} AND attempts < ?)
        ORDER BY started_at LIMIT ?
        """.trimIndent(),
        maxAttempts,
        limit,
    ) { toChunk() }

    override fun markTranscribing(id: String) {
        db.execute(
            "UPDATE audio_chunk SET state = ${ChunkState.TRANSCRIBING.sql}, attempts = attempts + 1, last_error = NULL WHERE id = ?",
            id,
        )
    }

    override fun markFailed(id: String, error: String) {
        db.execute("UPDATE audio_chunk SET state = ${ChunkState.FAILED.sql}, last_error = ? WHERE id = ?", error.take(500), id)
    }

    override fun requeueFailed(ids: List<String>): Int = ids.sumOf { id ->
        db.update(
            "UPDATE audio_chunk SET state = ${ChunkState.RECEIVED.sql}, attempts = 0 WHERE id = ? AND state = ${ChunkState.FAILED.sql}",
            id,
        )
    }

    override fun resetInterrupted(): Int = db.update(
        "UPDATE audio_chunk SET state = ${ChunkState.RECEIVED.sql} WHERE state = ${ChunkState.TRANSCRIBING.sql}",
    )
}
