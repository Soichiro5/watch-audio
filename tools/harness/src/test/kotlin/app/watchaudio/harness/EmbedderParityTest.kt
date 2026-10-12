package app.watchaudio.harness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The app's embedder (pure-Kotlin tokenizer + ONNX Runtime's C API through JNA, #24) gives the same
 * vectors as the Python stack of the M0-6 spike (HF tokenizers + onnxruntime) for the same model
 * files: `testdata/ruri-reference.json` from `research/embedding-benchmark/scripts/make_embedding_reference.py`.
 * Embedding several texts in one call must not change a vector either (they run one at a time:
 * padded batches change the int8 model's activations, see OnnxEmbedder).
 */
class EmbedderParityTest {
    private val models = File(System.getProperty("watchaudio.models") ?: "models")

    private fun modelAvailable(): Boolean {
        if (HarnessEmbedder.available(models)) return true
        check(System.getProperty("watchaudio.requireModels") != "true") { "embedding model missing in $models" }
        println("SKIPPED: run tools/harness/download-models.sh to enable the embedding tests")
        return false
    }

    @Test
    fun vectorsMatchThePythonReference() = runBlocking {
        if (!modelAvailable()) return@runBlocking
        val reference = Json.parseToJsonElement(File("testdata/ruri-reference.json").readText()).jsonObject["texts"]!!.jsonArray.map { it.jsonObject }
        val embedder = HarnessEmbedder.create(models)
        try {
            val documents = reference.filter { !it["query"]!!.jsonPrimitive.boolean }
            val batched = embedder.embedDocuments(documents.map { it["text"]!!.jsonPrimitive.content })
            for (entry in reference) {
                val text = entry["text"]!!.jsonPrimitive.content
                val expected = entry["vector"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                val single = if (entry["query"]!!.jsonPrimitive.boolean) embedder.embedQuery(text) else embedder.embedDocuments(listOf(text)).single()
                assertEquals(256, single.size)
                val cosine = dot(single, expected)
                println("cosine %.6f  %s".format(cosine, text))
                // ONNX Runtime's int8 kernels differ by CPU (AVX2 / AVX-512 VNNI): across machines the
                // same model and input agree to within about 1e-2 (0.998 for sentences, 0.994 for 「うん」 on a CI runner), not exactly; a wrong tokenizer or model is far below.
                assertTrue(cosine > CROSS_MACHINE_COSINE, "cosine $cosine for '$text'")
                if (entry in documents) {
                    val inBatch = batched[documents.indexOf(entry)]
                    assertTrue(dot(inBatch, single) > 0.99999, "embedding several texts changed the vector of '$text'")
                }
            }
        } finally {
            embedder.release()
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] }

    companion object {
        /** Least cosine between vectors of the same model and text computed on different CPUs. */
        const val CROSS_MACHINE_COSINE = 0.99
    }
}
