package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/** Keyword search over transcripts (#23, #72). */
interface SearchQueries {
    /**
     * Substring search over transcripts, newest first (case-insensitive), at most [limit] hits.
     *
     * Shaped for a year of data (#23, #72, `:shared:db:jvmBenchmark`):
     * - 3+ characters: trigram FTS. Only segment ids are sorted; the [limit] newest rows are
     *   loaded afterwards (with their chunk UUID and engine name, [SEGMENTS]).
     * - 1-2 characters (too short for a trigram match): LIKE over the newest
     *   [SearchSql.SHORT_QUERY_RECENT_SEGMENTS] segments, which is enough for a frequent word. A
     *   rarer one is looked up in the trigram index's vocabulary (every trigram that starts with
     *   the query, see migration 5) instead of scanning every text.
     *
     * Snippets are built in Kotlin for the returned rows only ([highlightSnippet]).
     */
    fun search(query: String, limit: Int = 50): List<SearchHit>
}

internal class SearchSql(private val db: SQLiteConnection) : SearchQueries {

    override fun search(query: String, limit: Int): List<SearchHit> = search(query, limit, SHORT_QUERY_RECENT_SEGMENTS)

    /** [search] with the window of the 1-2 character LIKE scan as a parameter (for tests). */
    fun search(query: String, limit: Int, recentSegments: Int): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val segments = if (q.length >= 3) {
            db.queryList(
                """
                SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.id IN (
                    SELECT t.id FROM segment_fts JOIN transcript_segment t ON t.id = segment_fts.rowid
                    WHERE segment_fts MATCH ? ORDER BY t.start_at DESC LIMIT ?
                ) ORDER BY s.start_at DESC
                """.trimIndent(),
                "\"" + q.replace("\"", "\"\"") + "\"",
                limit,
            ) { toSegment() }
        } else {
            val recent = db.queryList(
                """
                SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.id IN (
                    SELECT id FROM (
                        SELECT id, text FROM transcript_segment ORDER BY start_at DESC LIMIT $recentSegments
                    ) WHERE text LIKE ? ESCAPE '\' LIMIT ?
                ) ORDER BY s.start_at DESC
                """.trimIndent(),
                "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
                limit,
            ) { toSegment() }
            if (recent.size >= limit) {
                recent
            } else {
                // Terms are case-folded trigrams; U+10FFFF sorts after any character that can follow.
                val prefix = q.lowercase()
                db.queryList(
                    """
                    SELECT $SEGMENT_COLUMNS FROM $SEGMENTS WHERE s.id IN (
                        SELECT t.id FROM transcript_segment t WHERE t.id IN (
                            SELECT doc FROM segment_fts_terms WHERE term >= ? AND term < ?
                        ) ORDER BY t.start_at DESC LIMIT ?
                    ) ORDER BY s.start_at DESC
                    """.trimIndent(),
                    prefix,
                    prefix + "􏿿",
                    limit,
                ) { toSegment() }
            }
        }
        return segments.map { SearchHit(it, highlightSnippet(it.text, q)) }
    }

    companion object {
        /**
         * Newest segments scanned with LIKE for a 1-2 character query (~2 days of speech after #68)
         * before falling back to the trigram vocabulary ([search]). A word with fewer hits than the
         * limit in this window has few enough in a year for the vocabulary lookup to be fast.
         */
        const val SHORT_QUERY_RECENT_SEGMENTS = 20_000
    }
}
