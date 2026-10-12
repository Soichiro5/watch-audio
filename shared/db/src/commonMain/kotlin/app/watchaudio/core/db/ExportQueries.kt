package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import app.watchaudio.core.protocol.AudioCodec

/** A stored mark: the wearer tapped "Mark" at [at] (epoch ms) during chunk [chunkId]. */
data class MarkRecord(val chunkId: String, val at: Long)

/**
 * What a backup (#40) keeps of a chunk beyond the export: when this phone received it and the
 * manifest exactly as the watch sent it.
 */
data class ChunkBackupInfo(val receivedAt: Long, val manifestJson: String)

/** How a chunk's audio was encoded, as the watch described it in the manifest. */
data class AudioFileInfo(val codec: AudioCodec, val sha256: String)

/**
 * Reads for exporting the user's data (#155), by the chunks that started in `[from, to)` so that
 * every chunk, with its segments and marks, belongs to exactly one exported day. Chunks are known
 * by their UUID; integer keys never leave this module.
 */
interface ExportQueries {
    /** Start (epoch ms) of the oldest chunk that started at or after [at], or null if there is none. */
    fun firstChunkStartAtOrAfter(at: Long): Long?

    /** Segments of the chunks that started in `[from, to)`, by start time. */
    fun segmentsOfChunksStartedBetween(from: Long, to: Long): List<SegmentRecord>

    /** Marks of the chunks that started in `[from, to)`, by time. */
    fun marksOfChunksStartedBetween(from: Long, to: Long): List<MarkRecord>

    /** Codec and checksum of the audio of the chunks that started in `[from, to)`, by chunk UUID. */
    fun audioFilesOfChunksStartedBetween(from: Long, to: Long): Map<String, AudioFileInfo>

    /** [ChunkBackupInfo] of the chunks that started in `[from, to)`, by chunk UUID. */
    fun backupInfoOfChunksStartedBetween(from: Long, to: Long): Map<String, ChunkBackupInfo>
}

internal class ExportSql(private val db: SQLiteConnection) : ExportQueries {

    override fun firstChunkStartAtOrAfter(at: Long): Long? =
        db.queryOne("SELECT MIN(started_at) FROM audio_chunk WHERE started_at >= ?", at) { if (isNull(0)) null else getLong(0) }

    override fun segmentsOfChunksStartedBetween(from: Long, to: Long): List<SegmentRecord> = db.queryList(
        "SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE c.started_at >= ? AND c.started_at < ? ORDER BY s.start_at, s.id",
        from,
        to,
    ) { toSegment() }

    override fun marksOfChunksStartedBetween(from: Long, to: Long): List<MarkRecord> = db.queryList(
        """
        SELECT c.uuid, m.at FROM mark m JOIN audio_chunk c ON c.id = m.chunk_id
        WHERE c.started_at >= ? AND c.started_at < ? ORDER BY m.at, c.uuid
        """.trimIndent(),
        from,
        to,
    ) { MarkRecord(getText(0), getLong(1)) }

    override fun audioFilesOfChunksStartedBetween(from: Long, to: Long): Map<String, AudioFileInfo> = db.queryList(
        """
        SELECT uuid, codec, sha256 FROM audio_chunk
        WHERE started_at >= ? AND started_at < ? AND codec IS NOT NULL AND sha256 IS NOT NULL
        """.trimIndent(),
        from,
        to,
    ) { getText(0) to AudioFileInfo(AudioCodec.valueOf(getText(1)), getText(2)) }.toMap()

    override fun backupInfoOfChunksStartedBetween(from: Long, to: Long): Map<String, ChunkBackupInfo> = db.queryList(
        "SELECT uuid, received_at, manifest_json FROM audio_chunk WHERE started_at >= ? AND started_at < ?",
        from,
        to,
    ) { getText(0) to ChunkBackupInfo(getLong(1), getText(2)) }.toMap()
}
