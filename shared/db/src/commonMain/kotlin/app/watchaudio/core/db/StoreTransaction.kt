package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/**
 * Queries available inside [LocalStore.transaction] / [LocalStore.read], one interface per
 * concern: [ChunkQueries], [TranscriptQueries], [SearchQueries], [StorageQueries],
 * [SettingQueries], [ExportQueries] and [RestoreQueries]. Column lists and row mappers are shared in `Rows.kt`.
 */
class StoreTransaction private constructor(db: SQLiteConnection, private val searchSql: SearchSql) :
    ChunkQueries by ChunkSql(db),
    TranscriptQueries by TranscriptSql(db),
    SearchQueries by searchSql,
    StorageQueries by StorageSql(db),
    SettingQueries by SettingSql(db),
    ExportQueries by ExportSql(db),
    RestoreQueries by RestoreSql(db) {

    internal constructor(db: SQLiteConnection) : this(db, SearchSql(db))

    /** [search] with the window of the 1-2 character LIKE scan as a parameter (for tests). */
    internal fun search(query: String, limit: Int, recentSegments: Int): List<SearchHit> =
        searchSql.search(query, limit, recentSegments)
}
