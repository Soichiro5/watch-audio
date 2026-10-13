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

    /** The segment with row id [id], if stored. */
    fun segment(id: Long): SegmentRecord?

    /**
     * Up to [before] segments before and [after] segments after [segment] in time order
     * (start, then id), across chunks, among segments that started in `[from, to)`; oldest
     * first, [segment] itself included only if it started in the range.
     */
    fun segmentsAround(segment: SegmentRecord, before: Int, after: Int, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<SegmentRecord>
}

internal class TranscriptSql(private val db: SQLiteConnection) : TranscriptQueries {

    override fun saveTranscript(chunkId: String, segments: List<NewSegment>, engine: String) {
        val key = db.chunkKey(chunkId) ?: return requireStoredChunk(chunkId, segments)
        db.execute("DELETE FROM transcript_segment WHERE chunk_id = ?", key)
        insertSegments(key, segments, engine)
        db.execute("UPDATE audio_chunk SET state = ${ChunkState.TRANSCRIBED.sql}, last_error = NULL WHERE id = ?", key)
    }

    override fun replaceSegments(chunkId: String, from: Long, to: Long, segments: List<NewSegment>, engine: String) {
        val key = db.chunkKey(chunkId) ?: return requireStoredChunk(chunkId, segments)
        db.execute("DELETE FROM transcript_segment WHERE chunk_id = ? AND start_at >= ? AND start_at < ?", key, from, to)
        insertSegments(key, segments, engine)
    }

    /** A transcript for a chunk that is not stored (e.g. deleted meanwhile) is an error unless it is empty. */
    private fun requireStoredChunk(chunkId: String, segments: List<NewSegment>) =
        require(segments.none { it.text.isNotBlank() }) { "Chunk $chunkId is not stored" }

    private fun insertSegments(chunkKey: Long, segments: List<NewSegment>, engine: String) {
        val stored = segments.filter { it.text.isNotBlank() }
        if (stored.isEmpty()) return
        val engineId = db.engineKey(engine)
        db.prepare(
            "INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, lang, engine_id) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            stored.forEach { segment ->
                st.bindAll(arrayOf<Any?>(chunkKey, segment.startAt, segment.endAt, segment.text.trim(), segment.lang, engineId))
                st.step()
                st.reset()
            }
        }
    }

    override fun segmentsBetween(from: Long, to: Long): List<SegmentRecord> = db.queryList(
        "SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.start_at < ? AND s.end_at > ? ORDER BY s.start_at",
        to,
        from,
    ) { toSegment() }

    override fun segment(id: Long): SegmentRecord? =
        db.queryOne("SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.id = ?", id) { toSegment() }

    override fun segmentsAround(segment: SegmentRecord, before: Int, after: Int, from: Long, to: Long): List<SegmentRecord> {
        val earlier = db.queryList(
            """
            SELECT $SEGMENT_COLUMNS FROM $SEGMENTS
            WHERE (s.start_at < ? OR (s.start_at = ? AND s.id < ?)) AND s.start_at >= ? AND s.start_at < ?
            ORDER BY s.start_at DESC, s.id DESC LIMIT ?
            """.trimIndent(),
            segment.startAt, segment.startAt, segment.id, from, to, before,
        ) { toSegment() }
        val later = db.queryList(
            """
            SELECT $SEGMENT_COLUMNS FROM $SEGMENTS
            WHERE (s.start_at > ? OR (s.start_at = ? AND s.id > ?)) AND s.start_at >= ? AND s.start_at < ?
            ORDER BY s.start_at, s.id LIMIT ?
            """.trimIndent(),
            segment.startAt, segment.startAt, segment.id, from, to, after,
        ) { toSegment() }
        val self = if (segment.startAt in from until to) listOf(segment) else emptyList()
        return earlier.asReversed() + self + later
    }
}
