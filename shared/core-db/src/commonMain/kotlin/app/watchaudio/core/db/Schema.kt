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
    // 2: small key-value settings (onboarding, alert preferences, storage management)
    listOf(
        "CREATE TABLE IF NOT EXISTS app_setting (key TEXT PRIMARY KEY, value TEXT)",
    ),
)
