package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Passage embeddings (#24, migration 9): what to embed, storing, invalidation and the deletion log. */
class EmbeddingQueriesTest {
    private val dir = TempDir("embedding-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val model = "model-a"

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun transcribed(id: String, startedAt: Long, receivedAt: Long = 0, vararg texts: String) {
        store.registerChunk(id, startedAt, receivedAt = receivedAt)
        store.transaction {
            saveTranscript(id, texts.mapIndexed { i, text -> NewSegment(startedAt + i * 1_000L, startedAt + i * 1_000L + 900, text, "ja") }, "fake")
        }
    }

    private fun passage(first: Int, count: Int, value: Byte = 1) =
        NewPassage(first, count, first * 1_000L, 0.5f, ByteArray(4) { value })

    @Test
    fun chunksToEmbedAreTranscribedOnesWithSegmentsNewestFirst() = runTest {
        transcribed("old", 0, 10, "a", "b")
        transcribed("new", 600_000, 20, "c")
        transcribed("empty", 1_200_000, 30)
        store.registerChunk("pending", 1_800_000)
        assertEquals(listOf("new", "old"), store.read { chunksToEmbed(model, 10) }.map { it.chunkId })
        assertEquals(listOf("new"), store.read { chunksToEmbed(model, 10, receivedSince = 15) }.map { it.chunkId })
        assertEquals(EmbeddingProgress(chunks = 2, pending = 2), store.read { embeddingProgress(model) })

        val ids = store.read { chunkSegments("old") }.map { it.id }
        assertTrue(store.transaction { savePassages("old", model, ids, listOf(passage(0, 2))) })
        assertEquals(listOf("new"), store.read { chunksToEmbed(model, 10) }.map { it.chunkId })
        assertEquals(EmbeddingProgress(chunks = 2, pending = 1), store.read { embeddingProgress(model) })
        assertEquals(0.5f, store.read { embeddingProgress(model) }.fraction)
        // Another model still has everything to do.
        assertEquals(2, store.read { chunksToEmbed("model-b", 10) }.size)
    }

    @Test
    fun savingChecksTheSegmentsDidNotChange() = runTest {
        transcribed("a", 0, 0, "one", "two", "three")
        val segments = store.read { chunkSegments("a") }
        assertEquals(listOf("one", "two", "three"), segments.map { it.text })
        // The transcript was replaced after the segments were read.
        store.transaction { saveTranscript("a", listOf(NewSegment(0, 900, "new", "ja")), "fake") }
        assertFalse(store.transaction { savePassages("a", model, segments.map { it.id }, listOf(passage(0, 3))) })
        assertEquals(listOf("a"), store.read { chunksToEmbed(model, 10) }.map { it.chunkId })
        assertFalse(store.transaction { savePassages("gone", model, emptyList(), emptyList()) })
    }

    @Test
    fun changingTheSegmentsDeletesThePassagesAndLogsTheDeletion() = runTest {
        transcribed("a", 0, 0, "one", "two", "three", "four")
        val ids = store.read { chunkSegments("a") }.map { it.id }
        store.transaction { savePassages("a", model, ids, listOf(passage(0, 2, 1), passage(2, 2, 2))) }
        val stored = store.read { passageVectors(model, 0, 10) }
        assertEquals(2, stored.size)
        assertContentEquals(ByteArray(4) { 2 }, stored[1].vector)
        assertEquals(0.5f, stored[1].scale)

        // Re-transcribing the end of the chunk (a boundary join) invalidates every passage of it.
        store.transaction { replaceSegments("a", 2_000, 10_000, listOf(NewSegment(2_000, 2_900, "THREE", "ja")), "fake") }
        assertTrue(store.read { passageVectors(model, 0, 10) }.isEmpty())
        assertEquals(stored.map { it.id }.toSet(), store.read { passageDeletions(0, 10) }.map { it.passageId }.toSet())
        assertEquals(listOf("a"), store.read { chunksToEmbed(model, 10) }.map { it.chunkId })
    }

    @Test
    fun passagesComeBackWithTheirSegmentsAndKeywordHitsMapToThem() = runTest {
        transcribed("a", 0, 0, "s0", "s1", "s2", "s3", "s4")
        val segments = store.read { chunkSegments("a") }
        store.transaction { savePassages("a", model, segments.map { it.id }, listOf(passage(0, 2), passage(2, 3))) }
        val ids = store.read { passageVectors(model, 0, 10) }.map { it.id }
        val records = store.read { passages(ids.reversed() + 999) }
        assertEquals(ids.reversed(), records.map { it.id })
        assertEquals(listOf("s2", "s3", "s4"), records[0].segments.map { it.text })
        assertEquals(listOf("s0", "s1"), records[1].segments.map { it.text })
        assertEquals("a", records[0].chunkId)
        assertEquals(2_000, records[0].startAt)

        val mapping = store.read { passagesOfSegments(segments.map { it.id } + 12345, model) }
        assertEquals(listOf(ids[0], ids[0], ids[1], ids[1], ids[1]), segments.map { mapping[it.id] })
        assertTrue(store.read { passagesOfSegments(segments.map { it.id }, "other") }.isEmpty())
    }

    @Test
    fun aModelChangeLeavesOldPassagesToBeDeletedInBatches() = runTest {
        transcribed("a", 0, 0, "one")
        transcribed("b", 600_000, 0, "two")
        store.transaction {
            savePassages("a", "old", chunkSegments("a").map { it.id }, listOf(passage(0, 1)))
            savePassages("b", "old", chunkSegments("b").map { it.id }, listOf(passage(0, 1)))
        }
        assertEquals(2, store.read { chunksToEmbed("new", 10) }.size)
        assertTrue(store.read { hasPassagesOfOtherModels("new") })
        assertFalse(store.read { hasPassagesOfOtherModels("old") })
        assertEquals(1, store.transaction { deletePassagesOfOtherModels("new", 1) })
        assertEquals(1, store.transaction { deletePassagesOfOtherModels("new", 10) })
        assertEquals(0, store.transaction { deletePassagesOfOtherModels("new", 10) })
        assertFalse(store.read { hasPassagesOfOtherModels("new") })
        // Saving for the new model replaces whatever the chunk had.
        store.transaction { savePassages("a", "old", chunkSegments("a").map { it.id }, listOf(passage(0, 1))) }
        store.transaction { savePassages("a", "new", chunkSegments("a").map { it.id }, listOf(passage(0, 1))) }
        assertTrue(store.read { passageVectors("old", 0, 10) }.isEmpty())
        assertEquals(1, store.read { passageVectors("new", 0, 10) }.size)
    }

    @Test
    fun theDeletionLogIsPrunedAndDeletingEverythingRaisesTheFloor() = runTest {
        val token = store.read { embeddingSyncState() }.token
        assertEquals(32, token.length)
        assertEquals(0, store.read { embeddingSyncState() }.deletionFloor)
        transcribed("a", 0, 0, "one", "two")
        store.transaction { savePassages("a", model, chunkSegments("a").map { it.id }, listOf(passage(0, 1), passage(1, 1))) }
        store.transaction { savePassages("a", model, chunkSegments("a").map { it.id }, listOf(passage(0, 2))) }
        val log = store.read { passageDeletions(0, 10) }
        assertEquals(2, log.size)
        assertEquals(1, store.read { passageDeletions(log[0].seq, 10) }.size)
        store.transaction { pruneDeletions(log[0].seq) }
        assertEquals(listOf(log[1]), store.read { passageDeletions(0, 10) })
        assertEquals(log[0].seq, store.read { embeddingSyncState() }.deletionFloor)

        store.transaction { deleteAllChunks() }
        assertTrue(store.read { passageDeletions(0, 10) }.isEmpty())
        val floor = store.read { embeddingSyncState() }.deletionFloor
        assertTrue(floor > log[1].seq, "floor $floor")
        assertEquals(token, store.read { embeddingSyncState() }.token)
        // New deletions continue above the floor.
        transcribed("b", 0, 0, "x")
        store.transaction { savePassages("b", model, chunkSegments("b").map { it.id }, listOf(passage(0, 1))) }
        store.transaction { deleteChunks(listOf("b")) }
        assertTrue(store.read { passageDeletions(0, 10) }.single().seq > floor)
    }

    @Test
    fun eachDatabaseGetsItsOwnToken() = runTest {
        val other = LocalStore.open(dir.resolve("other.db").toString())
        try {
            assertNotEquals(store.read { embeddingSyncState() }.token, other.read { embeddingSyncState() }.token)
        } finally {
            other.close()
        }
    }

    @Test
    fun aVersion8DatabaseMigrates() = runTest {
        val path = dir.resolve("v8.db").toString()
        BundledSQLiteDriver().open(path).also { db ->
            MIGRATIONS.take(8).flatten().forEach { db.execSQL(it.trimIndent()) }
            db.execSQL("PRAGMA user_version = 8")
        }.close()
        LocalStore.open(path).use { migrated ->
            migrated.registerChunk("a", 0)
            migrated.transaction { saveTranscript("a", listOf(NewSegment(0, 900, "text", "ja")), "fake") }
            assertEquals(listOf("a"), migrated.read { chunksToEmbed(model, 10) }.map { it.chunkId })
        }
    }
}
