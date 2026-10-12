package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform

// Column lists and their row mappers: the positions in each list and in its mapper must match,
// so both live here. Every query that returns a record selects through these.

// Integer keys stay inside this module (#94): a chunk is known outside by its watch-assigned
// UUID (audio_chunk.uuid, ChunkRecord.id, SegmentRecord.chunkId) and an engine by its name.

/** Columns of `audio_chunk` read by [toChunk], in order. */
internal const val CHUNK_COLUMNS =
    "uuid, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, end_reason, state, attempts, " +
        "last_error, size_bytes, audio_purged_at, audio_backed_up_at"

/** Columns of [SEGMENTS] read by [toSegment], in order. */
internal const val SEGMENT_COLUMNS = "s.id, c.uuid, s.start_at, s.end_at, s.text, s.lang, e.name"

/**
 * Segments (`s`) with their chunk (`c`) and engine (`e`), for [SEGMENT_COLUMNS]. Filter and sort
 * on `s`; the joins are primary-key lookups per returned row.
 */
internal const val SEGMENTS =
    "transcript_segment s JOIN audio_chunk c ON c.id = s.chunk_id JOIN engine e ON e.id = s.engine_id"

/** SQL string literal of a state (`'RECEIVED'`), so queries spell states the way [ChunkState] does. */
internal val ChunkState.sql: String get() = "'$name'"

internal fun SQLiteStatement.toChunk() = ChunkRecord(
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
    sizeBytes = if (isNull(12)) null else getLong(12),
    audioPurgedAt = if (isNull(13)) null else getLong(13),
    audioBackedUpAt = if (isNull(14)) null else getLong(14),
)

internal fun SQLiteStatement.toSegment() = SegmentRecord(
    id = getLong(0),
    chunkId = getText(1),
    startAt = getLong(2),
    endAt = getLong(3),
    text = getText(4),
    lang = if (isNull(5)) null else getText(5),
    engine = getText(6),
)

/** Integer key of the chunk with [uuid], or null if it is not stored. */
internal fun SQLiteConnection.chunkKey(uuid: String): Long? =
    queryOne("SELECT id FROM audio_chunk WHERE uuid = ?", uuid) { getLong(0) }

/** Key of the [engine] row, added on first use. */
internal fun SQLiteConnection.engineKey(engine: String): Long {
    execute("INSERT OR IGNORE INTO engine (name) VALUES (?)", engine)
    return queryOne("SELECT id FROM engine WHERE name = ?", engine) { getLong(0) }!!
}

/** Runs [sql] with [args] bound in order (String, Long, Int or null) and returns the rows mapped by [row]. */
internal fun <T> SQLiteConnection.queryList(sql: String, vararg args: Any?, row: SQLiteStatement.() -> T): List<T> =
    prepare(sql).use { st ->
        st.bindAll(args)
        buildList { while (st.step()) add(st.row()) }
    }

/** Like [queryList], but returns the first row only, or null. */
internal fun <T> SQLiteConnection.queryOne(sql: String, vararg args: Any?, row: SQLiteStatement.() -> T): T? =
    prepare(sql).use { st ->
        st.bindAll(args)
        if (st.step()) st.row() else null
    }

/** Runs a statement that returns no rows. */
internal fun SQLiteConnection.execute(sql: String, vararg args: Any?) {
    prepare(sql).use { st ->
        st.bindAll(args)
        st.step()
    }
}

/** [execute], then @return the number of rows the statement changed. */
internal fun SQLiteConnection.update(sql: String, vararg args: Any?): Int {
    execute(sql, *args)
    return prepare("SELECT changes()").use { it.step(); it.getInt(0) }
}

/** Binds [args] to parameters 1, 2, ... in order: String, Long, Int or null. */
internal fun SQLiteStatement.bindAll(args: Array<out Any?>) = args.forEachIndexed { i, arg ->
    when (arg) {
        null -> bindNull(i + 1)
        is String -> bindText(i + 1, arg)
        is Long -> bindLong(i + 1, arg)
        is Int -> bindLong(i + 1, arg.toLong())
        else -> throw IllegalArgumentException("Cannot bind ${arg::class}")
    }
}
