package app.watchaudio.core.search

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.embedding.FakeEmbedder
import app.watchaudio.core.embedding.transcribedChunk
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Keyword + meaning search fused with RRF (#24). */
class HybridSearchTest {
    private val dir = TempDir("hybrid-search")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val embedder = FakeEmbedder()
    private val index = SemanticIndex(store, embedder, dir.resolve("index").toString())
    private val now = 1_791_622_800_000L

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun setUp() {
        store.transcribedChunk("trip", now, "沖縄の飛行機は朝の便にしよう", "ホテルは国際通りの近く")
        store.transcribedChunk("dentist", now + 600_000, "歯医者の予約は木曜の午後")
        store.transcribedChunk("work", now + 1_200_000, "会議の資料は飛行機の中で作る")
        EmbeddingStep(store, embedder, index).run(onBattery = false, nowMs = now) { true }
    }

    @Test
    fun withoutMeaningSearchItIsTheKeywordSearch() = runTest {
        setUp()
        val keyword = store.read { search("飛行機", 100) }
        val results = HybridSearch.search(store, semantic = null, query = "飛行機")
        assertEquals(keyword.map { it.segment.id to it.snippet }, results.hits.map { it.segment.id to it.snippet })
        assertTrue(results.hits.all { it.byKeyword && !it.byMeaning })
        assertEquals(false, results.keywordLimited)
        assertTrue(HybridSearch.search(store, index, "  ").hits.isEmpty())
    }

    @Test
    fun aPassageFoundBothWaysComesFirstAndMeaningOnlyHitsShowThePassage() = runTest {
        setUp()
        // Keyword hits for 飛行機, newest first: "work", then "trip". The meaning search ranks the
        // trip passage first (the query is close to its text), so it leads the fused list.
        val results = HybridSearch.search(store, index, "沖縄の飛行機は朝の便", minScore = 0f)
        val first = results.hits.first()
        assertEquals("trip", first.segment.chunkId)
        assertTrue(first.byKeyword && first.byMeaning)
        assertEquals("[沖縄の飛行機は朝の便]にしよう", first.snippet)

        val meaningOnly = results.hits.filter { !it.byKeyword }
        assertTrue(meaningOnly.isNotEmpty())
        assertTrue(meaningOnly.all { it.byMeaning && '[' !in it.snippet })
        // One row per passage or keyword segment.
        assertEquals(results.hits.size, results.hits.map { it.segment.id }.distinct().size)
    }

    @Test
    fun weakMeaningHitsAreDropped() = runTest {
        setUp()
        val results = HybridSearch.search(store, index, "歯医者の予約", minScore = 0.99f)
        assertEquals(listOf("dentist"), results.hits.map { it.segment.chunkId })
        assertTrue(results.hits.single().byKeyword)
    }

    @Test
    fun aFailingMeaningSearchLeavesTheKeywordResults() = runTest {
        setUp()
        val broken = object : SemanticSearch {
            override val modelId = embedder.spec.id

            override suspend fun status() = SemanticStatus.Ready

            override suspend fun search(query: String, limit: Int): List<VectorHit> = error("model broken")
        }
        assertEquals(listOf("dentist"), HybridSearch.search(store, broken, "歯医者").hits.map { it.segment.chunkId })
    }
}
