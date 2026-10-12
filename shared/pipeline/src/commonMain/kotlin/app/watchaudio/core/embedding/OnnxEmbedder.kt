package app.watchaudio.core.embedding

import app.watchaudio.core.platform.IoDispatcher
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Embedder] for a sentence-transformers style model exported to ONNX (ruri-v3, #24): prefix,
 * [UnigramTokenizer], the platform's [EncoderRuntime], mean pooling over the real tokens (special
 * tokens included, as sentence-transformers does), L2 normalization.
 *
 * The tokenizer and the runtime are loaded on first use and dropped by [release]. Calls are
 * serialized.
 *
 * Texts run one at a time by default ([maxBatchSize] 1): the int8 model quantizes its activations
 * per run over the whole batch, so padding and the other texts of a batch change a vector (cosine
 * down to 0.96 for a one-word passage padded next to long ones, EmbedderParityTest), and batching
 * gained little in M0-6 (2.1 vs 2.4 ms per text). One at a time, a passage gets the same vector
 * whenever it is embedded, and the same as the Python reference. Larger batches (similar lengths,
 * at most [maxBatchTokens] padded tokens) remain available for float models.
 */
class OnnxEmbedder(
    override val spec: EmbeddingModelSpec,
    private val loadTokenizer: () -> UnigramTokenizer,
    private val openRuntime: () -> EncoderRuntime,
    private val maxBatchSize: Int = 1,
    private val maxBatchTokens: Int = 2048,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
) : Embedder {
    private val mutex = Mutex()
    private var tokenizer: UnigramTokenizer? = null
    private var runtime: EncoderRuntime? = null

    override suspend fun countTokens(text: String): Int = mutex.withLock {
        withContext(dispatcher) { tokenizer().tokenize(text).size }
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts.map { spec.documentPrefix + it })

    override suspend fun embedQuery(text: String): FloatArray = embed(listOf(spec.queryPrefix + text)).single()

    override suspend fun release() = mutex.withLock {
        withContext(dispatcher) {
            runtime?.close()
            runtime = null
            tokenizer = null
        }
    }

    private suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return mutex.withLock {
            withContext(dispatcher) {
                val tokenizer = tokenizer()
                val encoded = texts.map { tokenizer.encode(it, spec.maxTokens) }
                val runtime = runtime ?: openRuntime().also { runtime = it }
                val out = arrayOfNulls<FloatArray>(texts.size)
                for (batch in batches(encoded)) {
                    val vectors = run(runtime, batch.map { encoded[it] }, tokenizer.padId)
                    batch.forEachIndexed { i, index -> out[index] = vectors[i] }
                }
                out.map { it!! }
            }
        }
    }

    private fun tokenizer(): UnigramTokenizer = tokenizer ?: loadTokenizer().also { tokenizer = it }

    /** Indexes of [encoded] grouped into batches of similar length. */
    private fun batches(encoded: List<IntArray>): List<List<Int>> {
        val order = encoded.indices.sortedBy { encoded[it].size }
        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (index in order) {
            val longest = maxOf(encoded[index].size, current.maxOfOrNull { encoded[it].size } ?: 0)
            if (current.isNotEmpty() && (current.size >= maxBatchSize || longest * (current.size + 1) > maxBatchTokens)) {
                batches += current
                current = mutableListOf()
            }
            current += index
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** Mean-pooled, normalized vectors of one padded batch. */
    private fun run(runtime: EncoderRuntime, rows: List<IntArray>, padId: Int): List<FloatArray> {
        val length = rows.maxOf { it.size }
        val ids = LongArray(rows.size * length) { padId.toLong() }
        val mask = LongArray(rows.size * length)
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { t, id ->
                ids[r * length + t] = id.toLong()
                mask[r * length + t] = 1
            }
        }
        val hidden = runtime.lastHiddenState(ids, mask, rows.size, length)
        val dim = spec.dimension
        check(hidden.size == rows.size * length * dim) { "unexpected output size ${hidden.size}" }
        return rows.mapIndexed { r, row ->
            val vector = FloatArray(dim)
            for (t in row.indices) {
                val base = (r * length + t) * dim
                for (d in 0 until dim) vector[d] += hidden[base + d]
            }
            for (d in 0 until dim) vector[d] /= row.size
            normalize(vector)
        }
    }

    companion object {
        /** Scales [vector] to unit length in place (a zero vector stays zero). */
        fun normalize(vector: FloatArray): FloatArray {
            var sum = 0.0
            for (v in vector) sum += v.toDouble() * v
            val norm = sqrt(sum).toFloat()
            if (norm > 0f) for (i in vector.indices) vector[i] /= norm
            return vector
        }
    }
}
