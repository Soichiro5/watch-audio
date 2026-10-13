package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Hour summaries (#27, migration 11): storing, the stale mark, and deletion with the day / everything. */
class SummaryQueriesTest {
    private val dir = TempDir("summary-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private val hour = 10 * HOUR_MS

    /** A chunk with one segment every 10 s; [marks] are offsets in the chunk. */
    private suspend fun transcribed(id: String, startedAt: Long, vararg texts: String, marks: List<Long> = emptyList()) {
        store.registerChunk(id, startedAt, marks = marks)
        store.transaction {
            saveTranscript(id, texts.mapIndexed { i, text -> NewSegment(startedAt + i * 10_000L, startedAt + i * 10_000L + 5_000, text, "ja") }, "fake")
        }
    }

    private suspend fun hourSegments(start: Long = hour) = store.read { segmentsStartedBetween(start, start + HOUR_MS) }

    private fun done(hash: String, highlights: List<HighlightRecord>, start: Long = hour) = NewHourSummary(
        hourStart = start,
        timeZone = "Asia/Tokyo",
        state = HourSummaryState.DONE,
        summary = "会議の日程を決めた。",
        provider = "fake",
        modelVersion = "1",
        promptVersion = 1,
        sourceHash = hash,
        createdAt = 5,
        highlights = highlights,
    )

    @Test
    fun aSummaryIsStoredWithItsHighlightsAndCitations() = runTest {
        transcribed("a", hour + 60_000, "一", "二", "三", marks = listOf(5_000))
        transcribed("before", hour - 400_000, "前の時間")
        val segments = hourSegments()
        assertEquals(listOf("一", "二", "三"), segments.map { it.text })
        assertEquals(listOf(hour + 65_000), store.read { markTimesBetween(hour, hour + HOUR_MS) })
        val outside = store.read { segmentsStartedBetween(hour - HOUR_MS, hour) }.single().id

        val saved = store.transaction {
            saveHourSummary(
                done(
                    hourSourceHash(segments),
                    listOf(
                        HighlightRecord("日程", "来週の水曜に決まった", listOf(segments[2].id, segments[0].id, segments[0].id)),
                        // Only a citation outside the hour: dropped with its highlight.
                        HighlightRecord("前", "前の時間の話", listOf(outside)),
                        HighlightRecord("二", "二番目", listOf(segments[1].id, outside)),
                    ),
                ),
            )
        }
        assertTrue(saved)
        val stored = store.read { hourSummariesBetween(hour, hour + HOUR_MS) }.single()
        assertEquals(HourSummaryState.DONE, stored.state)
        assertEquals("会議の日程を決めた。", stored.summary)
        assertFalse(stored.stale)
        assertEquals(hour + HOUR_MS, stored.hourEnd)
        assertEquals(
            listOf(
                HighlightRecord("日程", "来週の水曜に決まった", listOf(segments[0].id, segments[2].id)),
                HighlightRecord("二", "二番目", listOf(segments[1].id)),
            ),
            stored.highlights,
        )
        assertTrue(store.read { hourSummariesBetween(hour + HOUR_MS, hour + 2 * HOUR_MS) }.isEmpty())
    }

    @Test
    fun savingChecksTheSegmentsDidNotChange() = runTest {
        transcribed("a", hour, "一", "二")
        val segments = hourSegments()
        store.transaction { replaceSegments("a", hour, hour + 300_000, listOf(NewSegment(hour, hour + 900, "新しい", "ja")), "fake") }
        assertFalse(store.transaction { saveHourSummary(done(hourSourceHash(segments), emptyList())) })
        assertTrue(store.read { hourSummariesBetween(hour, hour + HOUR_MS) }.isEmpty())
        assertNotEquals(hourSourceHash(segments), hourSourceHash(hourSegments()))
        val current = hourSourceHash(hourSegments())
        assertTrue(store.transaction { saveHourSummary(done(current, emptyList())) })
    }

    @Test
    fun failuresCountAttemptsAndASuccessResetsThem() = runTest {
        transcribed("a", hour, "一")
        val hash = hourSourceHash(hourSegments())
        val failed = done(hash, emptyList()).copy(state = HourSummaryState.FAILED, summary = null, lastError = "timeout")
        repeat(2) { store.transaction { saveHourSummary(failed) } }
        store.read { hourSummariesBetween(hour, hour + HOUR_MS) }.single().let {
            assertEquals(2, it.attempts)
            assertEquals("timeout", it.lastError)
            assertNull(it.summary)
        }
        store.transaction { saveHourSummary(done(hash, emptyList())) }
        assertEquals(0, store.read { hourSummariesBetween(hour, hour + HOUR_MS) }.single().attempts)
    }

    @Test
    fun changedSegmentsMarkTheHourStaleAndDropDeletedCitations() = runTest {
        transcribed("a", hour, "一", "二")
        transcribed("b", hour + HOUR_MS, "次の時間")
        val segments = hourSegments()
        val next = hourSegments(hour + HOUR_MS)
        store.transaction {
            saveHourSummary(done(hourSourceHash(segments), listOf(HighlightRecord("t", "b", segments.map { it.id }))))
            saveHourSummary(done(hourSourceHash(next), emptyList(), start = hour + HOUR_MS))
        }
        // Re-transcribing the second utterance (a boundary join) replaces one segment of the hour.
        store.transaction { replaceSegments("a", hour + 10_000, hour + 20_000, listOf(NewSegment(hour + 10_000, hour + 15_000, "に", "ja")), "fake") }
        val (first, second) = store.read { hourSummariesBetween(hour, hour + 2 * HOUR_MS) }
        assertTrue(first.stale)
        assertEquals(listOf(segments[0].id), first.highlights.single().segmentIds)
        assertFalse(second.stale, "the next hour did not change")

        // A new segment in the next hour (a late transcript) marks that hour.
        store.transaction { saveTranscript("b", listOf(NewSegment(hour + HOUR_MS + 1_000, hour + HOUR_MS + 2_000, "追加", "ja")), "fake") }
        assertTrue(store.read { hourSummariesBetween(hour + HOUR_MS, hour + 2 * HOUR_MS) }.single().stale)
    }

    @Test
    fun deletingAChunkDeletesTheSummariesOfItsHoursAndDeleteAllDeletesEverything() = runTest {
        // "a" runs over the end of the hour (10:58-11:03), "c" is two hours later.
        transcribed("a", hour + HOUR_MS - 120_000, "一", "二")
        transcribed("c", hour + 3 * HOUR_MS, "三")
        for (start in listOf(hour, hour + HOUR_MS, hour + 3 * HOUR_MS)) {
            val hash = hourSourceHash(hourSegments(start))
            store.transaction { saveHourSummary(done(hash, emptyList(), start = start)) }
        }
        store.transaction { deleteChunks(listOf("a")) }
        assertEquals(listOf(hour + 3 * HOUR_MS), store.read { hourSummariesBetween(0, Long.MAX_VALUE) }.map { it.hourStart })

        store.transaction { deleteAllChunks() }
        assertTrue(store.read { hourSummariesBetween(0, Long.MAX_VALUE) }.isEmpty())
        assertEquals(0, store.read { deleteHourSummariesBetween(0, Long.MAX_VALUE) })
    }

    @Test
    fun theHashFollowsIdsAndTexts() {
        fun segment(id: Long, text: String) = SegmentRecord(id, "c", 0, 1, text, "ja", "e")
        val base = hourSourceHash(listOf(segment(1, "あ"), segment(2, "い")))
        assertEquals(16, base.length)
        assertEquals(base, hourSourceHash(listOf(segment(1, "あ"), segment(2, "い"))))
        assertNotEquals(base, hourSourceHash(listOf(segment(1, "あ"), segment(3, "い"))))
        assertNotEquals(base, hourSourceHash(listOf(segment(1, "あい"))))
        assertNotEquals(base, hourSourceHash(listOf(segment(2, "い"), segment(1, "あ"))))
        assertEquals("cbf29ce484222325", hourSourceHash(emptyList()))
    }

    @Test
    fun aVersion10DatabaseMigrates() = runTest {
        val path = dir.resolve("v10.db").toString()
        BundledSQLiteDriver().open(path).also { db ->
            MIGRATIONS.take(10).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 10")
        }.close()
        LocalStore.open(path).use { migrated ->
            migrated.registerChunk("a", hour)
            migrated.transaction { saveTranscript("a", listOf(NewSegment(hour, hour + 900, "text", "ja")), "fake") }
            val segments = migrated.read { segmentsStartedBetween(hour, hour + HOUR_MS) }
            assertTrue(migrated.transaction { saveHourSummary(done(hourSourceHash(segments), emptyList())) })
            migrated.transaction { deleteChunks(listOf("a")) }
            assertTrue(migrated.read { hourSummariesBetween(0, Long.MAX_VALUE) }.isEmpty())
        }
    }
}
