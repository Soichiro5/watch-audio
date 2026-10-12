package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/** A chunk whose segments have no passage embeddings for a model yet (#24). */
data class ChunkToEmbed(val chunkId: String, val startedAt: Long, val receivedAt: Long)

/**
 * A passage embedding to store: the chunk's segments `[firstIndex, firstIndex + segmentCount)` in
 * (start, id) order, joined and embedded; [vector] holds the int8 values and [scale] turns them
 * back into the unit vector.
 */
class NewPassage(val firstIndex: Int, val segmentCount: Int, val startAt: Long, val scale: Float, val vector: ByteArray)

/** A stored passage vector, as the search's vector cache reads it. */
class PassageVector(val id: Long, val scale: Float, val vector: ByteArray)

/** One deleted passage, in the order of the deletion log ([seq]). */
data class PassageDeletion(val seq: Long, val passageId: Long)

/**
 * What a vector cache checks before trusting itself: the [token] of this database and the highest
 * pruned deletion ([deletionFloor]).
 */
data class EmbeddingSyncState(val token: String, val deletionFloor: Long)

/** A stored passage with its segments, oldest first, for showing a semantic search result. */
data class PassageRecord(val id: Long, val chunkId: String, val startAt: Long, val segments: List<SegmentRecord>)

/** How far the passages of transcribed chunks are embedded with one model. */
data class EmbeddingProgress(
    /** Transcribed chunks that have segments. */
    val chunks: Int,
    /** Those of them without passages for the model yet. */
    val pending: Int,
) {
    /** 0..1; 1 when there is nothing to embed. */
    val fraction: Float get() = if (chunks == 0) 1f else (chunks - pending).toFloat() / chunks
}

/**
 * Passage embeddings for semantic search (#24): what still needs embedding, storing a chunk's
 * passages, and reading them back for the vector cache and for results. Embeddings are derived
 * from the segments and never leave the device (not in backups or exports).
 */
interface EmbeddingQueries {
    /**
     * Transcribed chunks with segments and no passages for [model], newest first. With
     * [receivedSince], only chunks the phone received at or after that time.
     */
    fun chunksToEmbed(model: String, limit: Int, receivedSince: Long? = null): List<ChunkToEmbed>

    /** The chunk's segments in (start, id) order: the order passage indexes refer to. */
    fun chunkSegments(chunkId: String): List<SegmentRecord>

    /**
     * Replaces the chunk's passages (of every model) with [passages] for [model], unless its
     * segments are no longer exactly [segmentIds] (they changed after they were read; the chunk
     * comes up again in [chunksToEmbed]). @return true if stored.
     */
    fun savePassages(chunkId: String, model: String, segmentIds: List<Long>, passages: List<NewPassage>): Boolean

    fun embeddingProgress(model: String): EmbeddingProgress

    fun embeddingSyncState(): EmbeddingSyncState

    /** Vectors of [model] with ids above [afterId], in id order, at most [limit]. */
    fun passageVectors(model: String, afterId: Long, limit: Int): List<PassageVector>

    /** Deleted passages logged after [afterSeq], in log order, at most [limit]. */
    fun passageDeletions(afterSeq: Long, limit: Int): List<PassageDeletion>

    /** Removes the deletion log up to [upToSeq] once a cache has applied it (raises the floor). */
    fun pruneDeletions(upToSeq: Long)

    /** The passages [ids] that still exist, with their segments, in the order of [ids]. */
    fun passages(ids: List<Long>): List<PassageRecord>

    /** For each of [segmentIds] that is in a passage of [model], that passage's id. */
    fun passagesOfSegments(segmentIds: List<Long>, model: String): Map<Long, Long>

    /** Deletes up to [limit] passages of models other than [model] (a model change). @return how many. */
    fun deletePassagesOfOtherModels(model: String, limit: Int): Int
}

internal class EmbeddingSql(private val db: SQLiteConnection) : EmbeddingQueries {

    override fun chunksToEmbed(model: String, limit: Int, receivedSince: Long?): List<ChunkToEmbed> {
        val modelKey = modelKey(model) ?: -1L
        return db.queryList(
            """
            SELECT c.uuid, c.started_at, c.received_at FROM audio_chunk c
            WHERE c.state = ${ChunkState.TRANSCRIBED.sql} AND c.received_at >= ?
              AND EXISTS (SELECT 1 FROM transcript_segment s WHERE s.chunk_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM passage_embedding p WHERE p.chunk_id = c.id AND p.model_id = ?)
            ORDER BY c.started_at DESC LIMIT ?
            """.trimIndent(),
            receivedSince ?: Long.MIN_VALUE,
            modelKey,
            limit,
        ) { ChunkToEmbed(getText(0), getLong(1), getLong(2)) }
    }

    override fun chunkSegments(chunkId: String): List<SegmentRecord> = db.queryList(
        "SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE c.uuid = ? ORDER BY s.start_at, s.id",
        chunkId,
    ) { toSegment() }

    override fun savePassages(chunkId: String, model: String, segmentIds: List<Long>, passages: List<NewPassage>): Boolean {
        val chunkKey = db.chunkKey(chunkId) ?: return false
        val current = db.queryList("SELECT id FROM transcript_segment WHERE chunk_id = ? ORDER BY start_at, id", chunkKey) { getLong(0) }
        if (current != segmentIds) return false
        db.execute("DELETE FROM passage_embedding WHERE chunk_id = ?", chunkKey)
        if (passages.isEmpty()) return true
        db.execute("INSERT OR IGNORE INTO embedding_model (name) VALUES (?)", model)
        val modelKey = modelKey(model)!!
        db.prepare(
            """
            INSERT INTO passage_embedding (chunk_id, model_id, first_index, segment_count, start_at, scale, vector)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            for (passage in passages) {
                require(passage.firstIndex >= 0 && passage.segmentCount > 0 && passage.firstIndex + passage.segmentCount <= current.size) {
                    "passage outside the chunk's segments"
                }
                st.bindLong(1, chunkKey)
                st.bindLong(2, modelKey)
                st.bindLong(3, passage.firstIndex.toLong())
                st.bindLong(4, passage.segmentCount.toLong())
                st.bindLong(5, passage.startAt)
                st.bindDouble(6, passage.scale.toDouble())
                st.bindBlob(7, passage.vector)
                st.step()
                st.reset()
            }
        }
        return true
    }

    override fun embeddingProgress(model: String): EmbeddingProgress {
        val modelKey = modelKey(model) ?: -1L
        return db.queryOne(
            """
            SELECT COUNT(*), COALESCE(SUM(NOT EXISTS (
                SELECT 1 FROM passage_embedding p WHERE p.chunk_id = c.id AND p.model_id = ?
            )), 0)
            FROM audio_chunk c
            WHERE c.state = ${ChunkState.TRANSCRIBED.sql} AND EXISTS (SELECT 1 FROM transcript_segment s WHERE s.chunk_id = c.id)
            """.trimIndent(),
            modelKey,
        ) { EmbeddingProgress(getInt(0), getInt(1)) }!!
    }

    override fun embeddingSyncState(): EmbeddingSyncState =
        db.queryOne("SELECT token, deletion_floor FROM embedding_state WHERE id = 1") { EmbeddingSyncState(getText(0), getLong(1)) }!!

    override fun passageVectors(model: String, afterId: Long, limit: Int): List<PassageVector> {
        val modelKey = modelKey(model) ?: return emptyList()
        return db.queryList(
            "SELECT id, scale, vector FROM passage_embedding WHERE model_id = ? AND id > ? ORDER BY id LIMIT ?",
            modelKey,
            afterId,
            limit,
        ) { PassageVector(getLong(0), getDouble(1).toFloat(), getBlob(2)) }
    }

    override fun passageDeletions(afterSeq: Long, limit: Int): List<PassageDeletion> = db.queryList(
        "SELECT seq, passage_id FROM passage_deletion WHERE seq > ? ORDER BY seq LIMIT ?",
        afterSeq,
        limit,
    ) { PassageDeletion(getLong(0), getLong(1)) }

    override fun pruneDeletions(upToSeq: Long) {
        db.execute("DELETE FROM passage_deletion WHERE seq <= ?", upToSeq)
        db.execute("UPDATE embedding_state SET deletion_floor = MAX(deletion_floor, ?)", upToSeq)
    }

    override fun passages(ids: List<Long>): List<PassageRecord> = ids.mapNotNull { id ->
        val row = db.queryOne(
            """
            SELECT c.uuid, p.chunk_id, p.start_at, p.first_index, p.segment_count
            FROM passage_embedding p JOIN audio_chunk c ON c.id = p.chunk_id WHERE p.id = ?
            """.trimIndent(),
            id,
        ) { PassageRow(getText(0), getLong(1), getLong(2), getLong(3), getLong(4)) } ?: return@mapNotNull null
        val segments = db.queryList(
            "SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.chunk_id = ? ORDER BY s.start_at, s.id LIMIT ? OFFSET ?",
            row.chunkKey,
            row.segmentCount,
            row.firstIndex,
        ) { toSegment() }
        PassageRecord(id, row.chunkId, row.startAt, segments)
    }

    private class PassageRow(val chunkId: String, val chunkKey: Long, val startAt: Long, val firstIndex: Long, val segmentCount: Long)

    override fun passagesOfSegments(segmentIds: List<Long>, model: String): Map<Long, Long> {
        val modelKey = modelKey(model) ?: return emptyMap()
        val out = LinkedHashMap<Long, Long>()
        db.prepare(
            """
            WITH seg AS (
                SELECT s.chunk_id AS chunk_id, (
                    SELECT COUNT(*) FROM transcript_segment t
                    WHERE t.chunk_id = s.chunk_id AND (t.start_at < s.start_at OR (t.start_at = s.start_at AND t.id < s.id))
                ) AS idx
                FROM transcript_segment s WHERE s.id = ?
            )
            SELECT p.id FROM passage_embedding p JOIN seg ON p.chunk_id = seg.chunk_id
            WHERE p.model_id = ? AND p.first_index <= seg.idx AND seg.idx < p.first_index + p.segment_count
            """.trimIndent(),
        ).use { st ->
            for (segmentId in segmentIds) {
                st.bindLong(1, segmentId)
                st.bindLong(2, modelKey)
                if (st.step()) out[segmentId] = st.getLong(0)
                st.reset()
            }
        }
        return out
    }

    override fun deletePassagesOfOtherModels(model: String, limit: Int): Int {
        val modelKey = modelKey(model) ?: -1L
        return db.update(
            "DELETE FROM passage_embedding WHERE id IN (SELECT id FROM passage_embedding WHERE model_id != ? LIMIT ?)",
            modelKey,
            limit,
        )
    }

    private fun modelKey(model: String): Long? = db.queryOne("SELECT id FROM embedding_model WHERE name = ?", model) { getLong(0) }
}
