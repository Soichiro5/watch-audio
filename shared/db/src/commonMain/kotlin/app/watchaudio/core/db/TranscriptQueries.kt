package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/** Transcript segments of chunks: saving a transcription and reading it back by time. */
interface TranscriptQueries {
    /** Replaces the chunk's transcript and marks it TRANSCRIBED. */
    fun saveTranscript(chunkId: String, segments: List<NewSegment>, engine: String)

    /**
     * Replaces the chunk's segments that start in `[from, to)` with [segments], leaving the rest
     * of its transcript and its state alone. Used when the audio around a chunk boundary is
     * transcribed again together with the neighbouring chunk.
     */
    fun replaceSegments(chunkId: String, from: Long, to: Long, segments: List<NewSegment>, engine: String)

    /** Segments overlapping `[from, to)`, oldest first. */
    fun segmentsBetween(from: Long, to: Long): List<SegmentRecord>
}

internal class TranscriptSql(private val db: SQLiteConnection) : TranscriptQueries {

    override fun saveTranscript(chunkId: String, segments: List<NewSegment>, engine: String) {
        db.execute("DELETE FROM transcript_segment WHERE chunk_id = ?", chunkId)
        insertSegments(chunkId, segments, engine)
        db.execute("UPDATE audio_chunk SET state = ${ChunkState.TRANSCRIBED.sql}, last_error = NULL WHERE id = ?", chunkId)
    }

    override fun replaceSegments(chunkId: String, from: Long, to: Long, segments: List<NewSegment>, engine: String) {
        db.execute("DELETE FROM transcript_segment WHERE chunk_id = ? AND start_at >= ? AND start_at < ?", chunkId, from, to)
        insertSegments(chunkId, segments, engine)
    }

    private fun insertSegments(chunkId: String, segments: List<NewSegment>, engine: String) {
        val stored = segments.filter { it.text.isNotBlank() }
        if (stored.isEmpty()) return
        db.prepare(
            "INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, lang, engine) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            stored.forEach { segment ->
                st.bindAll(arrayOf<Any?>(chunkId, segment.startAt, segment.endAt, segment.text.trim(), segment.lang, engine))
                st.step()
                st.reset()
            }
        }
    }

    override fun segmentsBetween(from: Long, to: Long): List<SegmentRecord> = db.queryList(
        "SELECT $SEGMENT_COLUMNS FROM transcript_segment WHERE start_at < ? AND end_at > ? ORDER BY start_at",
        to,
        from,
    ) { toSegment() }
}
