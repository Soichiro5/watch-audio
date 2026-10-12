package app.watchaudio.android

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.watchaudio.android.asr.AndroidSherpaTranscriber
import app.watchaudio.android.asr.ModelManifest
import app.watchaudio.android.search.AndroidEmbedding
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.ort.OrtApi
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.search.HybridSearch
import app.watchaudio.core.search.SemanticIndex
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Semantic search on Android (#24): the embedding model runs through ONNX Runtime's C API (JNA)
 * on the very `libonnxruntime.so` sherpa-onnx ships, in the same process as the speech
 * recognizer; it computes the same vectors as the JVM (reference vectors in
 * `assets/embedding-reference.json`, cosine >= 0.99), ranks queries the JVM decides by a clear
 * margin the same way, and finds a conversation by meaning through the app's embedding step and
 * hybrid search. Logs `EMBED_CHECK` (timings, agreement with the JVM) and `EMBED_RANK` / `EMBED_SEARCH`.
 *
 * The model comes from `/data/local/tmp/watchaudio-test/models/ruri-v3-30m` (push-models.sh) and is
 * removed again afterwards, so the other tests run without semantic search as before.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun embedsOnTheAsrsOnnxRuntimeAndFindsAParaphrase() = runBlocking {
        val asr = SpeechFixtures.ensureModels()
        val installed = installEmbeddingModel(asr.root)
        val dir = File(context.cacheDir, "embedding-test-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            // Speech recognition first: sherpa-onnx's JNI library loads libonnxruntime.so.
            LocalStore.open(File(dir, "asr.db").path).use { store ->
                val audioRoot = Path(File(dir, "audio").path)
                SpeechFixtures.ingest(Ingestor(store, audioRoot), "emb-" + UUID.randomUUID(), "embedding-test", System.currentTimeMillis() - 3_600_000, SpeechFixtures.speech(1))
                val transcriber = AndroidSherpaTranscriber(asr)
                try {
                    assertEquals(1, TranscriptionPipeline(store, audioRoot, transcriber).processPending().transcribed)
                } finally {
                    transcriber.release()
                }
            }

            // The embedding model on the same runtime.
            val ort = OrtApi.load("onnxruntime")
            assertEquals("1.28.2", ort.runtimeVersion, "the runtime sherpa-onnx ships")
            val embedder = AndroidEmbedding.embedder(asr.root)
            val reference = Reference.load()

            // (a) The same model: every reference text embeds to the vector the JVM computed
            // (tools/harness DeviceEmbeddingFixtureTest), queries and passages alike.
            val loadStarted = SystemClock.uptimeMillis()
            val firstQuery = reference.texts.first { it.query }
            embedder.embedQuery(firstQuery.text)
            val loadMs = SystemClock.uptimeMillis() - loadStarted
            val passageStarted = SystemClock.uptimeMillis()
            val passageVectors = embedder.embedDocuments(reference.texts.filter { !it.query }.map { it.text })
            val passageMs = (SystemClock.uptimeMillis() - passageStarted) / passageVectors.size
            var passageIndex = 0
            val vectors = reference.texts.map { text ->
                if (text.query) embedder.embedQuery(text.text) else passageVectors[passageIndex++]
            }
            val queryStarted = SystemClock.uptimeMillis()
            embedder.embedQuery(firstQuery.text)
            val queryMs = SystemClock.uptimeMillis() - queryStarted
            val agreement = reference.texts.indices.map { dot(vectors[it], reference.texts[it].vector) }
            Evidence.log(
                "EMBED_CHECK ort=${ort.runtimeVersion} load_and_first_query_ms=$loadMs passage_ms=$passageMs query_ms=$queryMs " +
                    "cos_vs_jvm_min=%.6f cos_vs_jvm=%s".format(agreement.min(), agreement.joinToString(",") { "%.5f".format(it) }),
            )
            for (i in reference.texts.indices) {
                assertEquals(RuriV3.SPEC.dimension, vectors[i].size)
                assertTrue(abs(dot(vectors[i], vectors[i]) - 1.0) < 1e-3, "not a unit vector")
                assertTrue(agreement[i] >= 0.99, "'${reference.texts[i].text}' differs from the JVM: cosine ${agreement[i]}")
            }

            // (b) Rankings that the JVM decides by a clear margin (>= 0.05) come out the same here.
            for ((query, passage) in reference.ranking) {
                val scores = reference.passages.map { dot(vectors[query], vectors[it]) }
                Evidence.log("EMBED_RANK '${reference.texts[query].text}' scores=${scores.joinToString(",") { "%.4f".format(it) }}")
                assertEquals(reference.passages.indexOf(passage), scores.indices.maxBy { scores[it] }, "'${reference.texts[query].text}': $scores")
            }

            // The app's pipeline step and hybrid search on a database of its own: a query without any
            // keyword in the transcripts finds its conversation by meaning.
            LocalStore.open(File(dir, "search.db").path).use { store ->
                val start = System.currentTimeMillis() - 7 * 86_400_000L
                reference.passages.forEachIndexed { i, p ->
                    val id = "passage-$i"
                    val at = start + i * 3_600_000L
                    store.transaction {
                        insertChunk(manifest(id, at), "$id.m4a", at)
                        val parts = reference.texts[p].text.split(' ')
                        saveTranscript(id, parts.mapIndexed { j, part -> NewSegment(at + j * 3_000L, at + j * 3_000L + 2_000, part, "ja") }, "test")
                    }
                }
                val index = SemanticIndex(store, embedder, File(dir, "index").path)
                val outcome = EmbeddingStep(store, embedder, index).run(onBattery = false, nowMs = System.currentTimeMillis()) { true }
                assertEquals(reference.passages.size, outcome.embeddedChunks)
                val (query, passage) = reference.ranking.first { (q, _) -> reference.texts[q].text == SEARCH_QUERY }
                val hits = HybridSearch.search(store, index, reference.texts[query].text).hits
                Evidence.log("EMBED_SEARCH query='$SEARCH_QUERY' hits=${hits.map { it.segment.chunkId to it.byMeaning }}")
                assertTrue(hits.none { it.byKeyword }, "the query shares no keyword with the transcripts")
                assertEquals("passage-${reference.passages.indexOf(passage)}", hits.first().segment.chunkId)
                assertTrue(hits.first().byMeaning)
            }
            embedder.release()
        } finally {
            dir.deleteRecursively()
            if (installed) File(asr.root, RuriV3.DIRECTORY).deleteRecursively()
        }
    }

    /** Copies the pushed embedding model into the app's models directory. @return true if this test installed it. */
    private fun installEmbeddingModel(modelsRoot: File): Boolean {
        val files = ModelManifest.embeddingFiles.map { File(modelsRoot, it.path) }
        if (files.all { it.isFile && it.length() > 0 }) return false
        if (Evidence.deviceFileSize("${SpeechFixtures.DEVICE_DIR}/${RuriV3.DIRECTORY}/${RuriV3.MODEL_FILE}") == null) {
            if (Evidence.argument("requireModels") == "true") fail("embedding model was not pushed to ${SpeechFixtures.DEVICE_DIR}")
            assumeTrue("embedding model not pushed (see push-models.sh)", false)
        }
        for (source in ModelManifest.embeddingFiles) {
            Evidence.copyFromDevice("${SpeechFixtures.DEVICE_DIR}/${source.path}", File(modelsRoot, source.path))
        }
        return true
    }

    private fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] }

    /**
     * `assets/embedding-reference.json`: texts with the vectors the JVM computes for them, and which
     * passage each ranking query must put first (kept in step by tools/harness DeviceEmbeddingFixtureTest).
     */
    private class Reference(val texts: List<Text>, val ranking: List<Pair<Int, Int>>) {
        class Text(val query: Boolean, val text: String, val vector: FloatArray)

        val passages: List<Int> get() = texts.indices.filter { !texts[it].query }

        companion object {
            fun load(): Reference {
                val json = InstrumentationRegistry.getInstrumentation().context.assets.open("embedding-reference.json").use { it.readBytes().decodeToString() }
                val root = Json.parseToJsonElement(json).jsonObject
                val texts = root["texts"]!!.jsonArray.map { element ->
                    val entry = element.jsonObject
                    Text(
                        entry["query"]!!.jsonPrimitive.boolean,
                        entry["text"]!!.jsonPrimitive.content,
                        entry["vector"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                    )
                }
                val ranking = root["ranking"]!!.jsonArray.map { element ->
                    element.jsonObject.let { it["query"]!!.jsonPrimitive.int to it["passage"]!!.jsonPrimitive.int }
                }
                return Reference(texts, ranking)
            }
        }
    }

    private companion object {
        /** A ranking query of the reference without any word of the passages (meaning only). */
        const val SEARCH_QUERY = "サーバー代がいくら増えるって言ってた？"
    }

    private fun manifest(id: String, startedAt: Long) = ChunkManifest(
        chunkId = id,
        sessionId = "embedding-test",
        deviceId = "test",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = startedAt,
        durationMs = 300_000L,
        timeZone = "Asia/Tokyo",
        audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00"),
        endReason = ChunkEndReason.ROTATION,
    )
}
