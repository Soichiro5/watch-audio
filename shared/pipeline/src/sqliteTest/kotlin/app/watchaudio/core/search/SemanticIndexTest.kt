package app.watchaudio.core.search

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.embedding.FakeEmbedder
import app.watchaudio.core.embedding.transcribedChunk
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The semantic index on top of the database (#24): sync, deletions, rebuilds and status. */
class SemanticIndexTest {
    private val dir = TempDir("semantic-index")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val embedder = FakeEmbedder()
    private var modelState: ModelState = ModelState.Ready
    private val directory = dir.resolve("index").toString()
    private val index = SemanticIndex(store, embedder, directory, { modelState })
    private val now = 1_791_622_800_000L

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun embedAll() = EmbeddingStep(store, embedder, index = null).run(onBattery = false, nowMs = now) { true }

    @Test
    fun findsTheClosestPassageAndFollowsDeletions() = runTest {
        store.transcribedChunk("trip", now, "沖縄旅行の飛行機を予約した")
        store.transcribedChunk("dentist", now + 600_000, "歯医者の予約は木曜日")
        embedAll()
        assertEquals("trip", top("沖縄旅行の飛行機"))
        assertEquals("dentist", top("歯医者の予約"))
        assertEquals(2, index.cachedPassages())

        // Deleting a chunk reaches the cache through the deletion log.
        store.transaction { deleteChunks(listOf("trip")) }
        assertEquals("dentist", top("沖縄旅行の飛行機"))
        assertEquals(1, index.cachedPassages())
    }

    private suspend fun top(query: String): String? {
        val hit = index.search(query, 1).firstOrNull() ?: return null
        return store.read { passages(listOf(hit.passageId)) }.single().chunkId
    }

    @Test
    fun aNewProcessReusesTheCacheFiles() = runTest {
        store.transcribedChunk("a", now, "最初の会話です")
        embedAll()
        index.sync(prune = true)
        val reopened = SemanticIndex(store, embedder, directory)
        val hits = reopened.search("最初の会話", 1).map { it.passageId }
        assertEquals("最初の会話です", store.read { passages(hits) }.single().segments.single().text)
        assertEquals(1, reopened.cachedPassages())
    }

    @Test
    fun pruningAndDeletingEverythingRebuildTheCache() = runTest {
        store.transcribedChunk("a", now, "一つ目")
        store.transcribedChunk("b", now + 600_000, "二つ目")
        embedAll()
        index.sync()
        store.transaction { deleteChunks(listOf("a")) }
        // Another cache prunes the log past this one's position: this one starts over.
        SemanticIndex(store, embedder, dir.resolve("other").toString()).sync(prune = true)
        assertTrue(store.read { passageDeletions(0, 10) }.isEmpty())
        index.sync()
        assertEquals(1, index.cachedPassages())

        store.transaction { deleteAllChunks() }
        index.sync()
        assertEquals(0, index.cachedPassages())
        assertTrue(index.search("一つ目", 5).isEmpty())
        index.clear()
        assertEquals(0, index.cachedPassages())
    }

    @Test
    fun statusFollowsTheModelAndTheBackfill() = runTest {
        modelState = ModelState.Missing
        assertEquals(SemanticStatus.WaitingForModel(), index.status())
        assertTrue(index.search("何か", 5).isEmpty())
        modelState = ModelState.Downloading(0.25f)
        assertEquals(SemanticStatus.WaitingForModel(0.25f), index.status())
        modelState = ModelState.Ready
        assertEquals(SemanticStatus.Ready, index.status())

        store.transcribedChunk("a", now, "一つ目")
        store.transcribedChunk("b", now + 600_000, "二つ目")
        assertEquals(SemanticStatus.Indexing(0f), index.status())
        EmbeddingStep(store, embedder, index, limits = EmbeddingStep.Limits(chunksPerRun = 1)).run(onBattery = false, nowMs = now) { true }
        assertEquals(SemanticStatus.Indexing(0.5f), index.status())
        embedAll()
        assertEquals(SemanticStatus.Ready, index.status())
    }
}
