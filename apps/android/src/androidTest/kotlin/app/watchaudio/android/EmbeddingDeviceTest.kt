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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Semantic search on Android (#24): the embedding model runs through ONNX Runtime's C API (JNA)
 * on the very `libonnxruntime.so` sherpa-onnx ships, in the same process as the speech
 * recognizer, and finds a conversation by a paraphrase through the app's embedding step and hybrid
 * search. Logs `EMBED_CHECK` with the timings on this device.
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
            val embedder = AndroidEmbedding.embedder(asr.root)
            val loadStarted = SystemClock.uptimeMillis()
            val query = embedder.embedQuery("先週話した旅行の件")
            val loadMs = SystemClock.uptimeMillis() - loadStarted
            val passages = listOf(
                "来月の沖縄だけどさ 飛行機は那覇行きの朝の便でいいかな ホテルは国際通りの近くにしよう",
                "歯医者の予約 来週の木曜の午後三時に取っといたからね",
            )
            val passageStarted = SystemClock.uptimeMillis()
            val vectors = embedder.embedDocuments(passages)
            val passageMs = (SystemClock.uptimeMillis() - passageStarted) / passages.size
            val queryStarted = SystemClock.uptimeMillis()
            embedder.embedQuery("歯医者の予約いつだっけ")
            val queryMs = SystemClock.uptimeMillis() - queryStarted
            val trip = dot(query, vectors[0])
            val dentist = dot(query, vectors[1])
            Evidence.log(
                "EMBED_CHECK ort=${ort.runtimeVersion} load_and_first_query_ms=$loadMs passage_ms=$passageMs query_ms=$queryMs " +
                    "cos_trip=%.3f cos_dentist=%.3f".format(trip, dentist),
            )
            assertEquals("1.28.2", ort.runtimeVersion, "the runtime sherpa-onnx ships")
            assertEquals(RuriV3.SPEC.dimension, query.size)
            assertTrue(abs(dot(query, query) - 1.0) < 1e-3)
            assertTrue(trip > dentist, "trip $trip vs dentist $dentist")

            // The app's pipeline step and hybrid search on a database of its own.
            LocalStore.open(File(dir, "search.db").path).use { store ->
                val start = System.currentTimeMillis() - 7 * 86_400_000L
                listOf("trip" to passages[0], "dentist" to passages[1]).forEachIndexed { i, (id, text) ->
                    val at = start + i * 3_600_000L
                    store.transaction {
                        insertChunk(manifest(id, at), "$id.m4a", at)
                        saveTranscript(id, text.split(' ').mapIndexed { j, part -> NewSegment(at + j * 3_000L, at + j * 3_000L + 2_000, part, "ja") }, "test")
                    }
                }
                val index = SemanticIndex(store, embedder, File(dir, "index").path)
                val outcome = EmbeddingStep(store, embedder, index).run(onBattery = false, nowMs = System.currentTimeMillis()) { true }
                assertEquals(2, outcome.embeddedChunks)
                val hits = HybridSearch.search(store, index, "先週話した旅行の件").hits
                Evidence.log("EMBED_SEARCH hits=${hits.map { it.segment.chunkId to it.byMeaning }}")
                assertEquals("trip", hits.first().segment.chunkId)
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
