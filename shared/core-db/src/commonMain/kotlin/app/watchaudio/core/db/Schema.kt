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
)
