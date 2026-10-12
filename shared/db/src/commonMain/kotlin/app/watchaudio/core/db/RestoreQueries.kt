package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform

/** A chunk read from a backup (#40), to store as it was on the device that wrote the backup. */
data class RestoredChunk(
    val id: String,
    val sessionId: String,
    val deviceId: String,
    val source: SourcePlatform,
    val startedAt: Long,
    val endedAt: Long,
    val timeZone: String,
    /** Relative to the audio root, as the ingest would name it; null for a silent chunk. */
    val audioPath: String?,
    val codec: AudioCodec?,
    val sizeBytes: Long?,
    val sha256: String?,
    val endReason: ChunkEndReason,
    val state: ChunkState,
    val attempts: Int,
    val lastError: String?,
    val receivedAt: Long,
    val manifestJson: String,
    /** Set when the audio is not on this device (deleted by retention, or not in the backup). */
    val audioPurgedAt: Long?,
    /** When the audio was last in a backup the user has ([ChunkRecord.audioBackedUpAt]), or null. */
    val audioBackedUpAt: Long? = null,
)

/** A transcript segment read from a backup, with the transcriber that produced it. */
data class RestoredSegment(
    val startAt: Long,
    val endAt: Long,
    val text: String,
    val lang: String?,
    val engine: String,
)

/**
 * Writes for restoring a backup (#40). Chunks are matched by their UUID, so restoring the same
 * backup again (or one that overlaps what is stored) adds nothing twice.
 */
interface RestoreQueries {
    /**
     * Inserts [chunk] unless a chunk with its UUID is stored.
     * @return true if inserted, false if it was already there (the stored one is kept).
     */
    fun insertRestoredChunk(chunk: RestoredChunk): Boolean

    /** Adds the marks at [ats] (epoch ms) to the stored chunk [chunkId]; @return how many were new. */
    fun addMarks(chunkId: String, ats: List<Long>): Int

    /** Adds [segments] to the stored chunk [chunkId], leaving its other segments and its state alone. */
    fun addSegments(chunkId: String, segments: List<RestoredSegment>)

    /**
     * Records that the audio file of the stored chunk [chunkId], which was not on the phone
     * (offloaded or deleted by retention), is back at its [ChunkRecord.audioPath], restored from a
     * backup created at [backupCreatedAt] (which therefore holds it).
     * @return true if the chunk was missing its audio and is now marked as having it.
     */
    fun markAudioRestored(chunkId: String, backupCreatedAt: Long): Boolean

    /**
     * Replaces the transcript of the stored chunk [chunkId] with [segments] and marks it
     * TRANSCRIBED; the caller decides that the stored transcript may be replaced.
     */
    fun replaceTranscript(chunkId: String, segments: List<RestoredSegment>)
}

internal class RestoreSql(private val db: SQLiteConnection) : RestoreQueries {

    override fun insertRestoredChunk(chunk: RestoredChunk): Boolean = db.update(
        """
        INSERT OR IGNORE INTO audio_chunk (
            uuid, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, codec, size_bytes,
            sha256, end_reason, state, attempts, last_error, received_at, manifest_json, audio_purged_at, audio_backed_up_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        chunk.id,
        chunk.sessionId,
        chunk.deviceId,
        chunk.source.name,
        chunk.startedAt,
        chunk.endedAt,
        chunk.timeZone,
        chunk.audioPath,
        chunk.codec?.name,
        chunk.sizeBytes,
        chunk.sha256,
        chunk.endReason.name,
        chunk.state.name,
        chunk.attempts,
        chunk.lastError,
        chunk.receivedAt,
        chunk.manifestJson,
        chunk.audioPurgedAt,
        chunk.audioBackedUpAt,
    ) > 0

    override fun markAudioRestored(chunkId: String, backupCreatedAt: Long): Boolean = db.update(
        """
        UPDATE audio_chunk SET audio_purged_at = NULL, audio_backed_up_at = MAX(COALESCE(audio_backed_up_at, 0), ?)
        WHERE uuid = ? AND audio_path IS NOT NULL AND audio_purged_at IS NOT NULL
        """.trimIndent(),
        backupCreatedAt,
        chunkId,
    ) > 0

    override fun addMarks(chunkId: String, ats: List<Long>): Int {
        val key = db.chunkKey(chunkId) ?: return 0
        return ats.sumOf { at -> db.update("INSERT OR IGNORE INTO mark (chunk_id, at) VALUES (?, ?)", key, at) }
    }

    override fun addSegments(chunkId: String, segments: List<RestoredSegment>) {
        val key = requireNotNull(db.chunkKey(chunkId)) { "Chunk $chunkId is not stored" }
        insertSegments(key, segments)
    }

    override fun replaceTranscript(chunkId: String, segments: List<RestoredSegment>) {
        val key = requireNotNull(db.chunkKey(chunkId)) { "Chunk $chunkId is not stored" }
        db.execute("DELETE FROM transcript_segment WHERE chunk_id = ?", key)
        insertSegments(key, segments)
        db.execute("UPDATE audio_chunk SET state = ${ChunkState.TRANSCRIBED.sql}, last_error = NULL WHERE id = ?", key)
    }

    private fun insertSegments(chunkKey: Long, segments: List<RestoredSegment>) {
        if (segments.isEmpty()) return
        val engines = segments.map { it.engine }.distinct().associateWith { db.engineKey(it) }
        db.prepare(
            "INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, lang, engine_id) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            segments.forEach { segment ->
                st.bindAll(arrayOf<Any?>(chunkKey, segment.startAt, segment.endAt, segment.text, segment.lang, engines.getValue(segment.engine)))
                st.step()
                st.reset()
            }
        }
    }
}
