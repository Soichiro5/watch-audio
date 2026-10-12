package app.watchaudio.core.pipeline

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.embedding.FakeEmbedder
import app.watchaudio.core.search.SemanticIndex
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testAudio
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The embedding step inside a transcription run (#24): after transcription, under the policy. */
class TranscriptionRunEmbeddingTest {
    private val dir = TempDir("run-embedding")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val now = 1_791_622_800_000L
    private var conditions = DeviceConditions(charging = true, batteryPercent = 80, powerSaveMode = false, thermal = ThermalLevel.NONE)

    private val transcriber = object : Transcriber {
        override val engineId = "fake"

        override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
            val id = parts.single().file.name.removeSuffix(".m4a")
            return listOf(TimedText(1_000, 2_000, "発話 $id", "ja"))
        }
    }
    private val model = object : SpeechModel {
        override suspend fun ensureReady(): ModelState = ModelState.Ready

        override fun recheck() {}
    }
    private val embedder = FakeEmbedder()
    private val pipeline = TranscriptionPipeline(store, dir.path, transcriber)

    private fun run(withEmbedding: Boolean = true) = TranscriptionRun(
        pipeline,
        store,
        StorageManager(store, dir.path),
        model,
        Diagnostics.NONE,
        gate = { PolicyGatedTranscription(pipeline, store, ProcessingPolicy(), { conditions }, { now }) },
        embedding = if (withEmbedding) EmbeddingStep(store, embedder, SemanticIndex(store, embedder, dir.resolve("index").toString())) else null,
    )

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun addChunks(vararg ids: String) = ids.forEachIndexed { index, id ->
        SystemFileSystem.sink(Path(dir.path, "$id.m4a")).buffered().use { it.write(ByteArray(100)) }
        store.transaction {
            insertChunk(testManifest(id, startedAt = now - 3_600_000L + index * 600_000L, audio = testAudio(id, sizeBytes = 100)), "$id.m4a", now)
        }
    }

    @Test
    fun embedsWhatWasJustTranscribed() = runTest {
        addChunks("a", "b")
        val report = run().run("test")
        assertEquals(2, report.transcription!!.result.transcribed)
        assertEquals(EmbeddingStep.Outcome(2, 2, 0, EmbeddingStep.FollowUp.None), report.embedding)
        assertEquals(listOf("発話 b", "発話 a"), embedder.documents)
        assertEquals(TranscriptionRun.FollowUp.None, report.followUp)
    }

    @Test
    fun doesNotEmbedWhileTheRunIsStoppedOrThePolicyPauses() = runTest {
        addChunks("a")
        // The platform stops the run after the first chunk: nothing is embedded, transcription comes first.
        var checks = 0
        assertNull(run().run("test", shouldContinue = { checks++ < 1 }).embedding)

        // Warm phone: the policy pauses background work.
        conditions = conditions.copy(thermal = ThermalLevel.SEVERE)
        assertNull(run().run("test").embedding)
        assertEquals(emptyList(), embedder.documents)

        // On battery (above the limits) only recent chunks, then the rest waits for the charger.
        conditions = conditions.copy(charging = false, thermal = ThermalLevel.NONE)
        val onBattery = run().run("test").embedding!!
        assertEquals(1, onBattery.embeddedChunks)
    }

    @Test
    fun withoutAnEmbeddingStepNothingChanges() = runTest {
        addChunks("a")
        assertNull(run(withEmbedding = false).run("test").embedding)
    }
}
