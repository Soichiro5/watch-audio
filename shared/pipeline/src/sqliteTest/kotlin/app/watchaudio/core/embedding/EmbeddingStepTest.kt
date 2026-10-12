package app.watchaudio.core.embedding

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.embedding.EmbeddingStep.FollowUp
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.search.SemanticIndex
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The pipeline's `embedded` step (#24). */
class EmbeddingStepTest {
    private val dir = TempDir("embedding-step")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val embedder = FakeEmbedder()
    private val index = SemanticIndex(store, embedder, dir.resolve("index").toString())
    private val log = mutableListOf<Pair<String, String>>()
    private val diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            log += event to detail
        }
    }
    private val hour = 3_600_000L
    private val now = 1_791_622_800_000L

    private fun step(limits: EmbeddingStep.Limits = EmbeddingStep.Limits(), rules: EmbeddingPassages.Rules = EmbeddingPassages.Rules(minTokens = 10, maxTokens = 40)) =
        EmbeddingStep(store, embedder, index, diagnostics, rules, limits)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun embedsNewestChunksFirstJoiningShortSegments() = runTest {
        store.transcribedChunk("old", now - 2 * hour, "今日は", "いい天気", "ですね本当に")
        store.transcribedChunk("new", now - hour, "来週の旅行", "沖縄に行こう")
        val outcome = step().run(onBattery = false, nowMs = now) { true }
        assertEquals(EmbeddingStep.Outcome(embeddedChunks = 2, passages = 2, failedChunks = 0, followUp = FollowUp.None), outcome)
        assertEquals(listOf("来週の旅行 沖縄に行こう", "今日は いい天気 ですね本当に"), embedder.documents)
        assertEquals(0, store.read { embeddingProgress(embedder.spec.id) }.pending)
        // The index caught up, and the model was released.
        assertEquals(2, index.cachedPassages())
        assertEquals(1, embedder.releases)
        assertTrue(log.any { it.first == DiagnosticEvents.EMBEDDING_RUN && it.second.startsWith("chunks=2 passages=2 failed=0") })
        // Nothing left: a second run does nothing.
        assertEquals(EmbeddingStep.Outcome(0, 0, 0, FollowUp.None), step().run(onBattery = false, nowMs = now) { true })
    }

    @Test
    fun changedSegmentsAreEmbeddedAgain() = runTest {
        store.transcribedChunk("a", now, "一つ目の発話", "二つ目の発話")
        step().run(onBattery = false, nowMs = now) { true }
        store.transaction { replaceSegments("a", now + 2_000, now + 10_000, listOf(NewSegment(now + 2_000, now + 3_000, "書き直した発話", "ja")), "fake") }
        assertEquals(1, store.read { embeddingProgress(embedder.spec.id) }.pending)
        step().run(onBattery = false, nowMs = now) { true }
        assertEquals("一つ目の発話 書き直した発話", embedder.documents.last())
        assertEquals(1, index.cachedPassages())
    }

    @Test
    fun onBatteryOnlyRecentChunksAndThenWaitForTheCharger() = runTest {
        store.transcribedChunk("week-old", now - 7 * 24 * hour, "古い会話です")
        store.transcribedChunk("today", now - hour, "今日の会話です")
        val outcome = step().run(onBattery = true, nowMs = now) { true }
        assertEquals(1, outcome.embeddedChunks)
        assertEquals(FollowUp.OnCharge, outcome.followUp)
        assertEquals(listOf("今日の会話です"), embedder.documents)
        // On the charger the backfill continues.
        assertEquals(EmbeddingStep.Outcome(1, 1, 0, FollowUp.None), step().run(onBattery = false, nowMs = now) { true })
    }

    @Test
    fun aRunStopsAtItsLimitAndAsksToRunAgain() = runTest {
        repeat(5) { store.transcribedChunk("c$it", now + it * hour, "発話その$it") }
        val limited = step(EmbeddingStep.Limits(chunksPerRun = 2, batteryChunksPerRun = 1))
        assertEquals(EmbeddingStep.Outcome(2, 2, 0, FollowUp.RunAgain), limited.run(onBattery = false, nowMs = now) { true })
        assertEquals(EmbeddingStep.Outcome(1, 1, 0, FollowUp.OnCharge), limited.run(onBattery = true, nowMs = now + 10 * hour) { true })
        assertEquals(3, store.read { embeddingProgress(embedder.spec.id) }.chunks - store.read { embeddingProgress(embedder.spec.id) }.pending)
    }

    @Test
    fun stopsBetweenChunksWhenAskedTo() = runTest {
        repeat(3) { store.transcribedChunk("c$it", now + it * hour, "発話その$it") }
        var allowed = 1
        val outcome = step().run(onBattery = false, nowMs = now) { allowed-- > 0 }
        assertEquals(EmbeddingStep.Outcome(1, 1, 0, FollowUp.None), outcome)
    }

    @Test
    fun aFailingChunkIsSkippedAndTooManyFailuresStopTheRun() = runTest {
        store.transcribedChunk("broken", now + hour, "壊れた発話")
        store.transcribedChunk("fine", now, "普通の発話")
        embedder.failing += "壊れた"
        val outcome = step().run(onBattery = false, nowMs = now) { true }
        assertEquals(EmbeddingStep.Outcome(1, 1, 1, FollowUp.None), outcome)
        assertTrue(log.any { it.first == DiagnosticEvents.EMBEDDING_FAILED && it.second.startsWith("chunk=broken") })

        repeat(4) { store.transcribedChunk("bad$it", now + (2 + it) * hour, "壊れた発話$it") }
        val stopped = step(EmbeddingStep.Limits(maxFailures = 2)).run(onBattery = false, nowMs = now) { true }
        assertEquals(EmbeddingStep.Outcome(0, 0, 2, FollowUp.None), stopped)
    }

    @Test
    fun aNewModelReplacesTheOldPassages() = runTest {
        store.transcribedChunk("a", now, "同じ会話です")
        step().run(onBattery = false, nowMs = now) { true }
        val newer = FakeEmbedder(embedder.spec.copy(id = "fake-model-2"))
        val newIndex = SemanticIndex(store, newer, dir.resolve("index").toString())
        val outcome = EmbeddingStep(store, newer, newIndex, diagnostics).run(onBattery = false, nowMs = now) { true }
        assertEquals(1, outcome.embeddedChunks)
        assertTrue(store.read { passageVectors(embedder.spec.id, 0, 10) }.isEmpty())
        assertEquals(1, store.read { passageVectors("fake-model-2", 0, 10) }.size)
        assertEquals(1, newIndex.cachedPassages())
    }
}
