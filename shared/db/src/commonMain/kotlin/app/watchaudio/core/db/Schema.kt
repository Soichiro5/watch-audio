package app.watchaudio.core.db

/**
 * Ordered migrations; `PRAGMA user_version` records how many have run.
 * Never edit a released migration — append a new one.
 */
internal val MIGRATIONS: List<List<String>> = listOf(
    // 1: chunks, marks, transcripts and full-text search
    listOf(
        """
        CREATE TABLE audio_chunk (
            id               TEXT PRIMARY KEY,
            session_id       TEXT    NOT NULL,
            device_id        TEXT    NOT NULL,
            source           TEXT    NOT NULL,
            started_at       INTEGER NOT NULL,
            ended_at         INTEGER NOT NULL,
            time_zone        TEXT    NOT NULL,
            audio_path       TEXT,
            codec            TEXT,
            size_bytes       INTEGER,
            sha256           TEXT,
            end_reason       TEXT    NOT NULL,
            state            TEXT    NOT NULL,
            attempts         INTEGER NOT NULL DEFAULT 0,
            last_error       TEXT,
            received_at      INTEGER NOT NULL,
            manifest_json    TEXT    NOT NULL
        )
        """,
        "CREATE INDEX idx_chunk_started_at ON audio_chunk(started_at)",
        "CREATE INDEX idx_chunk_state ON audio_chunk(state)",
        """
        CREATE TABLE mark (
            chunk_id TEXT    NOT NULL REFERENCES audio_chunk(id) ON DELETE CASCADE,
            at       INTEGER NOT NULL,
            PRIMARY KEY (chunk_id, at)
        )
        """,
        """
        CREATE TABLE transcript_segment (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            chunk_id  TEXT    NOT NULL REFERENCES audio_chunk(id) ON DELETE CASCADE,
            start_at  INTEGER NOT NULL,
            end_at    INTEGER NOT NULL,
            text      TEXT    NOT NULL,
            lang      TEXT,
            engine    TEXT    NOT NULL
        )
        """,
        "CREATE INDEX idx_segment_start_at ON transcript_segment(start_at)",
        "CREATE INDEX idx_segment_chunk ON transcript_segment(chunk_id)",
        // Trigram tokenizer: substring search that works for Japanese without a morphological analyzer.
        """
        CREATE VIRTUAL TABLE segment_fts USING fts5(
            text, content='transcript_segment', content_rowid='id', tokenize='trigram'
        )
        """,
        """
        CREATE TRIGGER segment_ai AFTER INSERT ON transcript_segment BEGIN
            INSERT INTO segment_fts(rowid, text) VALUES (new.id, new.text);
        END
        """,
        """
        CREATE TRIGGER segment_ad AFTER DELETE ON transcript_segment BEGIN
            INSERT INTO segment_fts(segment_fts, rowid, text) VALUES ('delete', old.id, old.text);
        END
        """,
    ),
    // 2: app settings (key-value). Idempotent so that parallel branches adding the same table merge cleanly.
    listOf(
        "CREATE TABLE IF NOT EXISTS app_setting (key TEXT PRIMARY KEY, value TEXT)",
    ),
    // 3: audio retention. The transcript stays; only the audio file of a transcribed chunk is deleted.
    listOf(
        "ALTER TABLE audio_chunk ADD COLUMN audio_purged_at INTEGER",
        """
        CREATE INDEX idx_chunk_purgeable ON audio_chunk(ended_at)
        WHERE audio_path IS NOT NULL AND audio_purged_at IS NULL
        """,
    ),
    // 4: search speed (#23). The LIKE fallback for 1-2 character queries scans this index
    // newest-first and filters on text without reading the table; it also serves every
    // start_at range query the old index did.
    listOf(
        "CREATE INDEX idx_segment_start_text ON transcript_segment(start_at, text)",
        "DROP INDEX idx_segment_start_at",
    ),
    // 5: search at the segment volume after #68 (#72: ~3 million short segments a year). Scanning
    // every text for a 1-2 character query became too slow, so those queries read the trigram
    // index instead: its vocabulary lists the trigrams that start with the query. Each text is
    // indexed with two trailing newlines so that every occurrence, even at the end, starts a
    // trigram. The index no longer matches the table's text, so it is contentless (rows are
    // deleted by rowid). The (start_at, text) index is no longer needed for scans and goes back to
    // start_at only, which saves ~40 bytes per segment.
    listOf(
        "DROP TRIGGER segment_ai",
        "DROP TRIGGER segment_ad",
        "DROP TABLE segment_fts",
        """
        CREATE VIRTUAL TABLE segment_fts USING fts5(
            text, content='', contentless_delete=1, tokenize='trigram'
        )
        """,
        "CREATE VIRTUAL TABLE segment_fts_terms USING fts5vocab(segment_fts, instance)",
        "INSERT INTO segment_fts(rowid, text) SELECT id, text || char(10, 10) FROM transcript_segment",
        """
        CREATE TRIGGER segment_ai AFTER INSERT ON transcript_segment BEGIN
            INSERT INTO segment_fts(rowid, text) VALUES (new.id, new.text || char(10, 10));
        END
        """,
        """
        CREATE TRIGGER segment_ad AFTER DELETE ON transcript_segment BEGIN
            DELETE FROM segment_fts WHERE rowid = old.id;
        END
        """,
        "CREATE INDEX idx_segment_start_at ON transcript_segment(start_at)",
        "DROP INDEX idx_segment_start_text",
    ),
    // 6: integer references (#94: a year of segments took ~318 bytes each). Segments and marks
    // point at the chunk by an integer key instead of the watch's UUID, which stays only in
    // audio_chunk.uuid, and segments name their transcriber through the engine table instead of
    // repeating the engine id on every row. audio_chunk gets an INTEGER PRIMARY KEY so the key
    // survives VACUUM (a plain rowid may be renumbered). The three tables are rebuilt: the old ones
    // are renamed to *_v5, copied, then dropped children first, so that no foreign key points at
    // the old chunk table when it is dropped (with foreign keys on, that would cascade-delete).
    // Segment ids are kept, so the contentless search index stays valid without reindexing, and
    // the AUTOINCREMENT counter carries over.
    listOf(
        "ALTER TABLE transcript_segment RENAME TO transcript_segment_v5",
        "ALTER TABLE mark RENAME TO mark_v5",
        "ALTER TABLE audio_chunk RENAME TO audio_chunk_v5",
        """
        CREATE TABLE audio_chunk (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid             TEXT    NOT NULL UNIQUE,
            session_id       TEXT    NOT NULL,
            device_id        TEXT    NOT NULL,
            source           TEXT    NOT NULL,
            started_at       INTEGER NOT NULL,
            ended_at         INTEGER NOT NULL,
            time_zone        TEXT    NOT NULL,
            audio_path       TEXT,
            codec            TEXT,
            size_bytes       INTEGER,
            sha256           TEXT,
            end_reason       TEXT    NOT NULL,
            state            TEXT    NOT NULL,
            attempts         INTEGER NOT NULL DEFAULT 0,
            last_error       TEXT,
            received_at      INTEGER NOT NULL,
            manifest_json    TEXT    NOT NULL,
            audio_purged_at  INTEGER
        )
        """,
        """
        INSERT INTO audio_chunk (
            uuid, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, codec, size_bytes,
            sha256, end_reason, state, attempts, last_error, received_at, manifest_json, audio_purged_at
        )
        SELECT
            id, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, codec, size_bytes,
            sha256, end_reason, state, attempts, last_error, received_at, manifest_json, audio_purged_at
        FROM audio_chunk_v5 ORDER BY started_at, id
        """,
        """
        CREATE TABLE mark (
            chunk_id INTEGER NOT NULL REFERENCES audio_chunk(id) ON DELETE CASCADE,
            at       INTEGER NOT NULL,
            PRIMARY KEY (chunk_id, at)
        )
        """,
        "INSERT INTO mark (chunk_id, at) SELECT c.id, m.at FROM mark_v5 m JOIN audio_chunk c ON c.uuid = m.chunk_id",
        "CREATE TABLE engine (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
        "INSERT INTO engine (name) SELECT DISTINCT engine FROM transcript_segment_v5 ORDER BY engine",
        """
        CREATE TABLE transcript_segment (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            chunk_id  INTEGER NOT NULL REFERENCES audio_chunk(id) ON DELETE CASCADE,
            start_at  INTEGER NOT NULL,
            end_at    INTEGER NOT NULL,
            text      TEXT    NOT NULL,
            lang      TEXT,
            engine_id INTEGER NOT NULL REFERENCES engine(id)
        )
        """,
        """
        INSERT INTO sqlite_sequence (name, seq)
        SELECT 'transcript_segment', seq FROM sqlite_sequence WHERE name = 'transcript_segment_v5'
        """,
        """
        INSERT INTO transcript_segment (id, chunk_id, start_at, end_at, text, lang, engine_id)
        SELECT s.id, c.id, s.start_at, s.end_at, s.text, s.lang, e.id
        FROM transcript_segment_v5 s
        JOIN audio_chunk c ON c.uuid = s.chunk_id
        JOIN engine e ON e.name = s.engine
        ORDER BY s.id
        """,
        // Drops the old indexes and the segment_ai / segment_ad triggers with their tables.
        "DROP TABLE transcript_segment_v5",
        "DROP TABLE mark_v5",
        "DROP TABLE audio_chunk_v5",
        "CREATE INDEX idx_chunk_started_at ON audio_chunk(started_at)",
        "CREATE INDEX idx_chunk_state ON audio_chunk(state)",
        """
        CREATE INDEX idx_chunk_purgeable ON audio_chunk(ended_at)
        WHERE audio_path IS NOT NULL AND audio_purged_at IS NULL
        """,
        "CREATE INDEX idx_segment_start_at ON transcript_segment(start_at)",
        "CREATE INDEX idx_segment_chunk ON transcript_segment(chunk_id)",
        """
        CREATE TRIGGER segment_ai AFTER INSERT ON transcript_segment BEGIN
            INSERT INTO segment_fts(rowid, text) VALUES (new.id, new.text || char(10, 10));
        END
        """,
        """
        CREATE TRIGGER segment_ad AFTER DELETE ON transcript_segment BEGIN
            DELETE FROM segment_fts WHERE rowid = old.id;
        END
        """,
    ),
)
