package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Migration 6 (#94): chunks and engines referenced by integer keys. Starts from a database as the
 * app wrote it at schema version 5 (UUID chunk ids and engine names on every segment).
 */
class IntegerRefsMigrationTest {
    private val dir = TempDir("integer-refs")

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    private fun SQLiteConnection.texts(sql: String): List<String> =
        prepare(sql).use { st -> buildList { while (st.step()) add(if (st.isNull(0)) "NULL" else st.getText(0)) } }

    /**
     * Three chunks inserted out of time order (transcribed with purged audio and two marks,
     * failed, silent), segments from two engines, and a deleted newest segment so that the
     * AUTOINCREMENT counter is ahead of the largest id.
     */
    private fun version5Database(): String {
        val path = dir.resolve("v5.db").toString()
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            MIGRATIONS.take(5).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 5")
            db.execSQL(
                """
                INSERT INTO audio_chunk (id, session_id, device_id, source, started_at, ended_at, time_zone, audio_path, codec,
                    size_bytes, sha256, end_reason, state, attempts, last_error, received_at, manifest_json, audio_purged_at)
                VALUES
                    ('$FAILED', 's1', 'watch', 'WEAROS', 300000, 600000, 'Asia/Tokyo', '2026/10/11/$FAILED.m4a', 'AAC',
                        2000, 'bb', 'USER_STOP', 'FAILED', 3, 'boom', 20, '{}', NULL),
                    ('$TRANSCRIBED', 's1', 'watch', 'WEAROS', 0, 300000, 'Asia/Tokyo', '2026/10/11/$TRANSCRIBED.m4a', 'AAC',
                        1000, 'aa', 'ROTATION', 'TRANSCRIBED', 1, NULL, 10, '{}', 99),
                    ('$SILENT', 's2', 'watch', 'WATCHOS', 600000, 900000, 'UTC', NULL, NULL,
                        NULL, NULL, 'CHARGING', 'NO_AUDIO', 0, NULL, 30, '{}', NULL)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO mark (chunk_id, at) VALUES ('$TRANSCRIBED', 1000), ('$TRANSCRIBED', 2000)")
            db.execSQL(
                """
                INSERT INTO transcript_segment (chunk_id, start_at, end_at, text, lang, engine) VALUES
                    ('$TRANSCRIBED', 1000, 2000, '明日の会議は十時から', 'ja', '$ENGINE_A'),
                    ('$TRANSCRIBED', 3000, 4000, '歯医者の予約', NULL, '$ENGINE_A'),
                    ('$FAILED', 301000, 302000, '会議の資料を送ります', 'ja', '$ENGINE_B'),
                    ('$FAILED', 303000, 304000, '消える発話', 'ja', '$ENGINE_B')
                """.trimIndent(),
            )
            db.execSQL("DELETE FROM transcript_segment WHERE id = 4")
        }
        return path
    }

    @Test
    fun keepsChunksMarksAndSegmentsWithTheirReferences() = runTest {
        val path = version5Database()
        LocalStore.open(path).use { store ->
            assertEquals(
                ChunkRecord(
                    id = TRANSCRIBED, sessionId = "s1", deviceId = "watch", source = SourcePlatform.WEAROS,
                    startedAt = 0, endedAt = 300_000, timeZone = "Asia/Tokyo", audioPath = "2026/10/11/$TRANSCRIBED.m4a",
                    endReason = ChunkEndReason.ROTATION, state = ChunkState.TRANSCRIBED, attempts = 1, lastError = null,
                    sizeBytes = 1_000, audioPurgedAt = 99,
                ),
                store.read { chunk(TRANSCRIBED) },
            )
            assertEquals(
                ChunkRecord(
                    id = FAILED, sessionId = "s1", deviceId = "watch", source = SourcePlatform.WEAROS,
                    startedAt = 300_000, endedAt = 600_000, timeZone = "Asia/Tokyo", audioPath = "2026/10/11/$FAILED.m4a",
                    endReason = ChunkEndReason.USER_STOP, state = ChunkState.FAILED, attempts = 3, lastError = "boom",
                    sizeBytes = 2_000, audioPurgedAt = null,
                ),
                store.read { chunk(FAILED) },
            )
            assertEquals(ChunkState.NO_AUDIO, store.read { chunk(SILENT) }!!.state)
            assertEquals(listOf(TRANSCRIBED, FAILED, SILENT), store.read { chunksBetween(0, Long.MAX_VALUE) }.map { it.id })

            // Segment ids, chunk UUIDs and engine names read back as before.
            assertEquals(
                listOf(
                    SegmentRecord(1, TRANSCRIBED, 1_000, 2_000, "明日の会議は十時から", "ja", ENGINE_A),
                    SegmentRecord(2, TRANSCRIBED, 3_000, 4_000, "歯医者の予約", null, ENGINE_A),
                    SegmentRecord(3, FAILED, 301_000, 302_000, "会議の資料を送ります", "ja", ENGINE_B),
                ),
                store.read { segmentsBetween(0, Long.MAX_VALUE) },
            )

            // The search index still points at the same segments, on every path.
            assertEquals(listOf(FAILED, TRANSCRIBED), store.read { search("会議") }.map { it.segment.chunkId })
            assertEquals(listOf(3L, 1L), store.read { search("会議", 50, recentSegments = 0) }.map { it.segment.id })
            assertEquals(listOf("会議の[資料を]送ります"), store.read { search("資料を") }.map { it.snippet })
            assertTrue(store.read { search("消える発話") }.isEmpty())

            // New segments continue after the deleted one and reuse the stored engine.
            store.transaction { replaceSegments(FAILED, 305_000, Long.MAX_VALUE, listOf(NewSegment(305_000, 306_000, "追加の発話", "ja")), ENGINE_B) }
            assertEquals(5L, store.read { search("追加の") }.single().segment.id)

            // Deleting a chunk still removes its segments, marks and index entries.
            assertEquals(1, store.transaction { deleteChunks(listOf(TRANSCRIBED)) })
            assertTrue(store.read { search("歯医者") }.isEmpty())
            assertEquals(listOf(3L, 5L), store.read { segmentsBetween(0, Long.MAX_VALUE) }.map { it.id })
            assertEquals(2, store.read { stats() }.chunks)
        }

        BundledSQLiteDriver().open(path).use { db ->
            assertEquals(listOf("6"), db.texts("PRAGMA user_version"))
            assertEquals(listOf("ok"), db.texts("PRAGMA integrity_check"))
            assertEquals(emptyList(), db.texts("PRAGMA foreign_key_check"))
            assertEquals(emptyList(), db.texts("SELECT name FROM sqlite_master WHERE name LIKE '%_v5'"))
            // One row per engine, numbered in name order.
            assertEquals(listOf(ENGINE_B, ENGINE_A), db.texts("SELECT name FROM engine ORDER BY id"))
            assertEquals(emptyList(), db.texts("SELECT at FROM mark"), "marks of the deleted chunk")
            assertEquals(
                listOf("idx_chunk_purgeable", "idx_chunk_started_at", "idx_chunk_state", "idx_segment_chunk", "idx_segment_start_at"),
                db.texts("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%' ORDER BY name"),
            )
            assertEquals(
                listOf("segment_ad", "segment_ai"),
                db.texts("SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'transcript_segment' ORDER BY name"),
            )
            // The chunk key is an INTEGER PRIMARY KEY (a rowid alias), so VACUUM cannot renumber it.
            assertEquals(listOf("INTEGER"), db.texts("SELECT type FROM pragma_table_info('audio_chunk') WHERE pk = 1"))
        }
    }

    @Test
    fun marksMoveToTheIntegerKey() = runTest {
        val path = version5Database()
        LocalStore.open(path).close()
        BundledSQLiteDriver().open(path).use { db ->
            assertEquals(
                listOf("$TRANSCRIBED 1000", "$TRANSCRIBED 2000"),
                db.texts("SELECT c.uuid || ' ' || m.at FROM mark m JOIN audio_chunk c ON c.id = m.chunk_id ORDER BY m.at"),
            )
            assertEquals(listOf("integer"), db.texts("SELECT DISTINCT typeof(chunk_id) FROM transcript_segment"))
        }
    }

    @Test
    fun anEmptyVersion5DatabaseMigrates() = runTest {
        val path = dir.resolve("empty.db").toString()
        BundledSQLiteDriver().open(path).use { db ->
            MIGRATIONS.take(5).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 5")
        }
        LocalStore.open(path).use { store ->
            store.registerChunk("a", 0)
            store.transaction { saveTranscript("a", listOf(NewSegment(1_000, 2_000, "最初の発話", "ja")), ENGINE_A) }
            assertEquals(SegmentRecord(1, "a", 1_000, 2_000, "最初の発話", "ja", ENGINE_A), store.read { search("最初の") }.single().segment)
        }
    }

    private companion object {
        const val TRANSCRIBED = "0b6f2b8e-6a4e-4d0b-9c39-2f1e8f4d7a01"
        const val FAILED = "7c1d9e3a-1f2b-4c5d-8e6f-a0b1c2d3e4f5"
        const val SILENT = "f3e2d1c0-b9a8-4765-8432-10fedcba9876"
        const val ENGINE_A = "sherpa-onnx/zipformer-ja-reazonspeech-2024-08-01"
        const val ENGINE_B = "apple-speech-analyzer"
    }
}
