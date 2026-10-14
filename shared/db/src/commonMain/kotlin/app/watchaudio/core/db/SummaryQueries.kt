package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/** Length of the hour an [HourSummaryRecord] covers: `[hourStart, hourStart + HOUR_MS)`. */
const val HOUR_MS = 3_600_000L

/** What became of an hour's summary (#27); stored in `hour_summary.state`. */
enum class HourSummaryState {
    /** Summarized: [HourSummaryRecord.summary] and its highlights. */
    DONE,

    /** Too little speech to summarize; the LLM was not called. */
    LITTLE_SPEECH,

    /** The model declined the content (guardrail); not retried. */
    REFUSED,

    /** The last attempt failed ([HourSummaryRecord.lastError] says how); retried when the user asks. */
    FAILED,
}

/** One highlight of an hour: what happened and the segments it is based on (never empty). */
data class HighlightRecord(val title: String, val body: String, val segmentIds: List<Long>)

/** A stored hour summary (a row of `hour_summary` with its highlights). Times are epoch ms. */
data class HourSummaryRecord(
    val hourStart: Long,
    /** IANA time zone that decided the hour (local hours). */
    val timeZone: String,
    val state: HourSummaryState,
    /** Two or three sentences for [HourSummaryState.DONE]; null otherwise. */
    val summary: String?,
    /** `LlmProvider.id` that made it, e.g. `apple-fm`. */
    val provider: String,
    val modelVersion: String?,
    /** Version of the prompts and the chunking (`SummaryPrompts.VERSION`). */
    val promptVersion: Int,
    /** [hourSourceHash] of the segments the summary was made from. */
    val sourceHash: String,
    /** The hour's segments changed since: shown as 「更新待ち」 and made again by the next run. */
    val stale: Boolean,
    /** Failed attempts in a row (0 once it succeeds). */
    val attempts: Int,
    /** Error type of the last failed attempt (never a message with user data), or null. */
    val lastError: String?,
    val createdAt: Long,
    /** In order; segments deleted since have dropped out of [HighlightRecord.segmentIds]. */
    val highlights: List<HighlightRecord> = emptyList(),
) {
    val hourEnd: Long get() = hourStart + HOUR_MS
}

/** An hour summary to store with [SummaryQueries.saveHourSummary]. */
data class NewHourSummary(
    val hourStart: Long,
    val timeZone: String,
    val state: HourSummaryState,
    val summary: String?,
    val provider: String,
    val modelVersion: String?,
    val promptVersion: Int,
    /** [hourSourceHash] of the segments read to make it: the save is refused if they changed since. */
    val sourceHash: String,
    val createdAt: Long,
    val lastError: String? = null,
    val highlights: List<HighlightRecord> = emptyList(),
)

/**
 * Hour summaries (#27, migration 11): what the LLM made of each local hour, the segments it read
 * and the marks that steer it. Summaries are derived data (not in backups); deleting a chunk deletes
 * the summaries of its hours (trigger) and [StorageQueries.deleteAllChunks] deletes them all.
 */
interface SummaryQueries {
    /** Summaries of the hours that start in `[from, to)`, oldest first, with their highlights. */
    fun hourSummariesBetween(from: Long, to: Long): List<HourSummaryRecord>

    /** Segments that started in `[from, to)`, by start time then id: the input of one hour. */
    fun segmentsStartedBetween(from: Long, to: Long): List<SegmentRecord>

    /** Times of the marks in `[from, to)`, oldest first. */
    fun markTimesBetween(from: Long, to: Long): List<Long>

    /**
     * Replaces the summary of [summary]'s hour (its highlights and citations go with it) if the
     * hour's segments are still the ones it was made from ([NewHourSummary.sourceHash]); a failure
     * counts one more attempt, anything else resets the count. Citations of segments outside the
     * hour are dropped, and so is a highlight left without any.
     * @return false (nothing stored) if the segments changed since they were read.
     */
    fun saveHourSummary(summary: NewHourSummary): Boolean

    /** Deletes the summaries of the hours that start in `[from, to)`. @return how many. */
    fun deleteHourSummariesBetween(from: Long, to: Long): Int
}

/**
 * Hash of an hour's input: the ids and texts of [segments] in order (FNV-1a, 64 bits, hex). It only
 * tells whether the segments changed; it is not a security measure.
 */
fun hourSourceHash(segments: List<SegmentRecord>): String {
    var hash = FNV_OFFSET
    fun mix(byte: Int) {
        hash = (hash xor (byte.toLong() and 0xff)) * FNV_PRIME
    }
    for (segment in segments) {
        var id = segment.id
        repeat(8) {
            mix((id and 0xff).toInt())
            id = id ushr 8
        }
        segment.text.encodeToByteArray().forEach { mix(it.toInt()) }
        mix(0)
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
private const val FNV_PRIME = 0x100000001b3L

internal class SummarySql(private val db: SQLiteConnection) : SummaryQueries {

    override fun hourSummariesBetween(from: Long, to: Long): List<HourSummaryRecord> {
        val rows = db.queryList(
            """
            SELECT id, hour_start, time_zone, state, summary, provider, model_version, prompt_version, source_hash, stale,
                attempts, last_error, created_at
            FROM hour_summary WHERE hour_start >= ? AND hour_start < ? ORDER BY hour_start
            """.trimIndent(),
            from,
            to,
        ) {
            getLong(0) to HourSummaryRecord(
                hourStart = getLong(1),
                timeZone = getText(2),
                state = HourSummaryState.valueOf(getText(3)),
                summary = if (isNull(4)) null else getText(4),
                provider = getText(5),
                modelVersion = if (isNull(6)) null else getText(6),
                promptVersion = getInt(7),
                sourceHash = getText(8),
                stale = getInt(9) != 0,
                attempts = getInt(10),
                lastError = if (isNull(11)) null else getText(11),
                createdAt = getLong(12),
            )
        }
        return rows.map { (id, record) -> record.copy(highlights = highlights(id)) }
    }

    private fun highlights(summaryId: Long): List<HighlightRecord> {
        val highlights = db.queryList(
            "SELECT id, title, body FROM summary_highlight WHERE summary_id = ? ORDER BY ord",
            summaryId,
        ) { Triple(getLong(0), getText(1), getText(2)) }
        return highlights.map { (id, title, body) ->
            val segments = db.queryList(
                """
                SELECT c.segment_id FROM highlight_citation c JOIN transcript_segment s ON s.id = c.segment_id
                WHERE c.highlight_id = ? ORDER BY s.start_at, s.id
                """.trimIndent(),
                id,
            ) { getLong(0) }
            HighlightRecord(title, body, segments)
        }
    }

    override fun segmentsStartedBetween(from: Long, to: Long): List<SegmentRecord> = db.queryList(
        "SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.start_at >= ? AND s.start_at < ? ORDER BY s.start_at, s.id",
        from,
        to,
    ) { toSegment() }

    override fun markTimesBetween(from: Long, to: Long): List<Long> =
        db.queryList("SELECT at FROM mark WHERE at >= ? AND at < ? ORDER BY at", from, to) { getLong(0) }

    override fun saveHourSummary(summary: NewHourSummary): Boolean {
        val segments = segmentsStartedBetween(summary.hourStart, summary.hourStart + HOUR_MS)
        if (hourSourceHash(segments) != summary.sourceHash) return false
        // Citations must point into this hour; a highlight without one is not stored.
        val inHour = segments.mapTo(HashSet()) { it.id }
        val highlights = summary.highlights
            .map { it.copy(segmentIds = it.segmentIds.filter { id -> id in inHour }.distinct()) }
            .filter { it.segmentIds.isNotEmpty() }
        val previousAttempts = db.queryOne(
            "SELECT attempts FROM hour_summary WHERE hour_start = ?",
            summary.hourStart,
        ) { getInt(0) } ?: 0
        val attempts = if (summary.state == HourSummaryState.FAILED) previousAttempts + 1 else 0
        db.execute("DELETE FROM hour_summary WHERE hour_start = ?", summary.hourStart)
        db.execute(
            """
            INSERT INTO hour_summary (hour_start, time_zone, state, summary, provider, model_version, prompt_version, source_hash,
                stale, attempts, last_error, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """.trimIndent(),
            summary.hourStart,
            summary.timeZone,
            summary.state.name,
            summary.summary,
            summary.provider,
            summary.modelVersion,
            summary.promptVersion,
            summary.sourceHash,
            attempts,
            summary.lastError,
            summary.createdAt,
        )
        val summaryId = db.queryOne("SELECT last_insert_rowid()") { getLong(0) }!!
        highlights.forEachIndexed { ord, highlight ->
            db.execute(
                "INSERT INTO summary_highlight (summary_id, ord, title, body) VALUES (?, ?, ?, ?)",
                summaryId,
                ord,
                highlight.title,
                highlight.body,
            )
            val highlightId = db.queryOne("SELECT last_insert_rowid()") { getLong(0) }!!
            highlight.segmentIds.forEach { segmentId ->
                db.execute("INSERT INTO highlight_citation (highlight_id, segment_id) VALUES (?, ?)", highlightId, segmentId)
            }
        }
        return true
    }

    override fun deleteHourSummariesBetween(from: Long, to: Long): Int =
        db.update("DELETE FROM hour_summary WHERE hour_start >= ? AND hour_start < ?", from, to)
}
