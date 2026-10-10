package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.protocol.toJson

/** Queries available inside [LocalStore.transaction] / [LocalStore.read]. */
class StoreTransaction internal constructor(private val db: SQLiteConnection) {

    /**
     * Inserts a chunk unless one with the same id exists.
     * @return true if inserted, false if it was a duplicate.
     */
    fun insertChunk(manifest: ChunkManifest, audioPath: String?, receivedAt: Long): Boolean {
        val state = if (audioPath == null) ChunkState.NO_AUDIO else ChunkState.RECEIVED
        val inserted = db.prepare(
            """
            INSERT OR IGNORE INTO audio_chunk (
                id, session_id, device_id, source, started_at, ended_at, time_zone,
                audio_path, codec, size_bytes, sha256, end_reason, state, received_at, manifest_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.bindText(1, manifest.chunkId)
            st.bindText(2, manifest.sessionId)
            st.bindText(3, manifest.deviceId)
            st.bindText(4, manifest.source.name)
            st.bindLong(5, manifest.startedAtEpochMs)
            st.bindLong(6, manifest.endedAtEpochMs)
            st.bindText(7, manifest.timeZone)
            st.bindTextOrNull(8, audioPath)
            st.bindTextOrNull(9, manifest.audio?.codec?.name)
            manifest.audio?.sizeBytes?.let { st.bindLong(10, it) } ?: st.bindNull(10)
            st.bindTextOrNull(11, manifest.audio?.sha256)
            st.bindText(12, manifest.endReason.name)
            st.bindText(13, state.name)
            st.bindLong(14, receivedAt)
            st.bindText(15, manifest.toJson())
            st.step()
            changes() > 0
        }
        if (inserted) {
            manifest.markOffsetsMs.forEach { offset ->
                db.prepare("INSERT OR IGNORE INTO mark (chunk_id, at) VALUES (?, ?)").use { st ->
                    st.bindText(1, manifest.chunkId)
                    st.bindLong(2, manifest.startedAtEpochMs + offset)
                    st.step()
                }
            }
        }
        return inserted
    }

    fun chunk(id: String): ChunkRecord? =
        db.prepare("SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE id = ?").use { st ->
            st.bindText(1, id)
            if (st.step()) st.toChunk() else null
        }

    /** Chunks overlapping `[from, to)`, oldest first. */
    fun chunksBetween(from: Long, to: Long): List<ChunkRecord> =
        db.prepare("SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE started_at < ? AND ended_at > ? ORDER BY started_at").use { st ->
            st.bindLong(1, to)
            st.bindLong(2, from)
            buildList { while (st.step()) add(st.toChunk()) }
        }

    /** The chunk that started last before [at] (the newest chunk by default), if any. */
    fun lastChunkBefore(at: Long = Long.MAX_VALUE): ChunkRecord? =
        db.prepare("SELECT $CHUNK_COLUMNS FROM audio_chunk WHERE started_at < ? ORDER BY started_at DESC LIMIT 1").use { st ->
            st.bindLong(1, at)
            if (st.step()) st.toChunk() else null
        }

    /** The chunk of the same session and device that started last before [chunk], if any. */
    fun previousInSession(chunk: ChunkRecord): ChunkRecord? = neighbourInSession(chunk, before = true)

    /** The chunk of the same session and device that started first after [chunk], if any. */
    fun nextInSession(chunk: ChunkRecord): ChunkRecord? = neighbourInSession(chunk, before = false)

    private fun neighbourInSession(chunk: ChunkRecord, before: Boolean): ChunkRecord? {
        val (comparison, order) = if (before) "<" to "DESC" else ">" to "ASC"
        return db.prepare(
            """
            SELECT $CHUNK_COLUMNS FROM audio_chunk
            WHERE session_id = ? AND device_id = ? AND started_at $comparison ?
            ORDER BY started_at $order LIMIT 1
            """.trimIndent(),
        ).use { st ->
            st.bindText(1, chunk.sessionId)
            st.bindText(2, chunk.deviceId)
            st.bindLong(3, chunk.startedAt)
            if (st.step()) st.toChunk() else null
        }
    }

    /** Chunks waiting for transcription (new or retryable failures), oldest first. */
    fun pendingChunks(limit: Int, maxAttempts: Int): List<ChunkRecord> =
        db.prepare(
            """
            SELECT $CHUNK_COLUMNS FROM audio_chunk
            WHERE state = 'RECEIVED' OR (state = 'FAILED' AND attempts < ?)
            ORDER BY started_at LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.bindLong(1, maxAttempts.toLong())
            st.bindLong(2, limit.toLong())
            buildList { while (st.step()) add(st.toChunk()) }
        }

    fun markTranscribing(id: String) = update(
        "UPDATE audio_chunk SET state = 'TRANSCRIBING', attempts = attempts + 1, last_error = NULL WHERE id = ?",
        id,
    )

    fun markFailed(id: String, error: String) {
        db.prepare("UPDATE audio_chunk SET state = 'FAILED', last_error = ? WHERE id = ?").use { st ->
            st.bindText(1, error.take(500))
            st.bindText(2, id)
            st.step()
        }
    }

    /**
     * Puts failed chunks back in the queue with a fresh attempt budget (user-initiated retry).
     * @return number of chunks re-queued.
     */
    fun requeueFailed(ids: List<String>): Int = ids.sumOf { id ->
        update("UPDATE audio_chunk SET state = 'RECEIVED', attempts = 0 WHERE id = ? AND state = 'FAILED'", id)
        changes()
    }

    /** Puts chunks left in TRANSCRIBING by a killed process back in the queue. */
    fun resetInterrupted(): Int {
        update("UPDATE audio_chunk SET state = 'RECEIVED' WHERE state = 'TRANSCRIBING'")
        return changes()
    }

    /** Replaces the chunk's transcript and marks it TRANSCRIBED. */
    fun saveTranscript(chunkId: String, segments: List<NewSegment>, engine: String) {
        update("DELETE FROM transcript_segment WHERE chunk_id = ?", chunkId)
        insertSegments(chunkId, segments, engine)
        update("UPDATE audio_chunk SET state = 'TRANSCRIBED', last_error = NULL WHERE id = ?", chunkId)
    }

    /**
     * Replaces the chunk's segments that start in `[from, to)` with [segments], leaving the rest
     * of its transcript and its state alone. Used when the audio around a chunk boundary is
     * transcribed again together with the neighbouring chunk.
     */
    fun replaceSegments(chunkId: String, from: Long, to: Long, segments: List<NewSegment>, engine: String) {
        db.prepare("DELETE FROM transcript_segment WHERE chunk_id = ? AND start_at >= ? AND start_at < ?").use { st ->
            st.bindText(1, chunkId)
            st.bindLong(2, from)
            st.bindLong(3, to)
            st.step()
        }
        insertSegments(chunkId, segments, engine)
    }

    private fun insertSegments(chunkId: String, segments: List<NewSegment>, engine: String) {
        segments.filter { it.text.isNotBlank() }.forEach { segment ->
            db.prepare(
                "INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, lang, engine) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.bindText(1, chunkId)
                st.bindLong(2, segment.startAt)
                st.bindLong(3, segment.endAt)
                st.bindText(4, segment.text.trim())
                st.bindTextOrNull(5, segment.lang)
                st.bindText(6, engine)
                st.step()
            }
        }
    }

    fun segmentsBetween(from: Long, to: Long): List<SegmentRecord> =
        db.prepare("SELECT $SEGMENT_COLUMNS FROM transcript_segment WHERE start_at < ? AND end_at > ? ORDER BY start_at").use { st ->
            st.bindLong(1, to)
            st.bindLong(2, from)
            buildList { while (st.step()) add(st.toSegment()) }
        }

    /**
     * Substring search over transcripts, newest first. Trigram FTS needs 3+ characters, so
     * shorter queries fall back to LIKE.
     */
    fun search(query: String, limit: Int = 50): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return if (q.length >= 3) {
            db.prepare(
                """
                SELECT ${SEGMENT_COLUMNS.prefixed("s")}, snippet(segment_fts, 0, '[', ']', '…', 24)
                FROM segment_fts JOIN transcript_segment s ON s.id = segment_fts.rowid
                WHERE segment_fts MATCH ? ORDER BY s.start_at DESC LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.bindText(1, "\"" + q.replace("\"", "\"\"") + "\"")
                st.bindLong(2, limit.toLong())
                buildList { while (st.step()) add(SearchHit(st.toSegment(), st.getText(7))) }
            }
        } else {
            db.prepare(
                "SELECT $SEGMENT_COLUMNS FROM transcript_segment WHERE text LIKE ? ESCAPE '\\' ORDER BY start_at DESC LIMIT ?",
            ).use { st ->
                st.bindText(1, "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                st.bindLong(2, limit.toLong())
                buildList {
                    while (st.step()) {
                        val segment = st.toSegment()
                        add(SearchHit(segment, segment.text.replace(q, "[$q]")))
                    }
                }
            }
        }
    }

    fun stats(): StoreStats = db.prepare(
        """
        SELECT
            (SELECT COUNT(*) FROM audio_chunk),
            (SELECT COUNT(*) FROM audio_chunk WHERE state IN ('RECEIVED', 'TRANSCRIBING')),
            (SELECT COUNT(*) FROM audio_chunk WHERE state = 'TRANSCRIBED'),
            (SELECT COUNT(*) FROM audio_chunk WHERE state = 'FAILED'),
            (SELECT COUNT(*) FROM transcript_segment),
            (SELECT COALESCE(SUM(size_bytes), 0) FROM audio_chunk),
            (SELECT COALESCE(SUM(ended_at - started_at), 0) FROM audio_chunk WHERE audio_path IS NOT NULL),
            (SELECT MAX(received_at) FROM audio_chunk)
        """.trimIndent(),
    ).use { st ->
        st.step()
        StoreStats(
            chunks = st.getInt(0),
            pendingChunks = st.getInt(1),
            transcribedChunks = st.getInt(2),
            failedChunks = st.getInt(3),
            segments = st.getInt(4),
            audioBytes = st.getLong(5),
            recordedMs = st.getLong(6),
            lastReceivedAt = if (st.isNull(7)) null else st.getLong(7),
        )
    }

    /** When the most recent chunk arrived on this phone (epoch ms), or null if none yet. */
    fun lastReceivedAt(): Long? = db.prepare("SELECT MAX(received_at) FROM audio_chunk").use { st ->
        if (st.step() && !st.isNull(0)) st.getLong(0) else null
    }

    private fun update(sql: String, vararg args: String) {
        db.prepare(sql).use { st ->
            args.forEachIndexed { i, arg -> st.bindText(i + 1, arg) }
            st.step()
        }
    }

    private fun changes(): Int = db.prepare("SELECT changes()").use { it.step(); it.getInt(0) }

    private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) =
        if (value == null) bindNull(index) else bindText(index, value)

    private fun SQLiteStatement.toChunk() = ChunkRecord(
        id = getText(0),
        sessionId = getText(1),
        deviceId = getText(2),
        source = SourcePlatform.valueOf(getText(3)),
        startedAt = getLong(4),
        endedAt = getLong(5),
        timeZone = getText(6),
        audioPath = if (isNull(7)) null else getText(7),
        endReason = ChunkEndReason.valueOf(getText(8)),
        state = ChunkState.valueOf(getText(9)),
        attempts = getInt(10),
        lastError = if (isNull(11)) null else getText(11),
    )

    private fun SQLiteStatement.toSegment() = SegmentRecord(
        id = getLong(0),
        chunkId = getText(1),
        startAt = getLong(2),
        endAt = getLong(3),
        text = getText(4),
        lang = if (isNull(5)) null else getText(5),
        engine = getText(6),
    )

    private companion object {
        const val CHUNK_COLUMNS =
            "id, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, end_reason, state, attempts, last_error"
        const val SEGMENT_COLUMNS = "id, chunk_id, start_at, end_at, text, lang, engine"

        fun String.prefixed(alias: String) = split(", ").joinToString(", ") { "$alias.$it" }
    }
}
