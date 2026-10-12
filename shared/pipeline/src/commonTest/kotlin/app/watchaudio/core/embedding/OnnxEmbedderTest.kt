package app.watchaudio.core.embedding

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The tokenizer's rules on a tiny vocabulary and [OnnxEmbedder]'s pooling (#24). The real
 * vocabulary is checked against the Python library in `UnigramTokenizerGoldenTest` (JVM).
 */
class OnnxEmbedderTest {
    /** A tokenizer.json like ruri-v3's, with a handful of pieces. */
    private fun tokenizerJson(normalizer: String = "null", prepend: String = "never") = """
        {"version":"1.0","truncation":null,"padding":null,
         "added_tokens":[{"id":0,"content":"<unk>","single_word":false,"lstrip":false,"rstrip":false,"normalized":false,"special":true},
                         {"id":1,"content":"<s>","single_word":false,"lstrip":false,"rstrip":false,"normalized":false,"special":true},
                         {"id":2,"content":"</s>","single_word":false,"lstrip":false,"rstrip":false,"normalized":false,"special":true},
                         {"id":3,"content":"<pad>","single_word":false,"lstrip":false,"rstrip":false,"normalized":false,"special":true}],
         "normalizer":$normalizer,
         "pre_tokenizer":{"type":"Metaspace","replacement":"▁","prepend_scheme":"$prepend","split":false},
         "post_processor":{"type":"TemplateProcessing","single":[{"SpecialToken":{"id":"<s>","type_id":0}},{"Sequence":{"id":"A","type_id":0}},{"SpecialToken":{"id":"</s>","type_id":0}}]},
         "model":{"type":"Unigram","unk_id":0,"byte_fallback":true,"vocab":[
            ["<unk>",0.0],["<s>",0.0],["</s>",0.0],["<pad>",0.0],
            ["来",-5.0],["週",-5.0],["来週",-6.0],["の",-2.0],["▁",-3.0],["予定",-4.0],["予",-6.0],["定",-6.0],
            ["<0xF0>",0.0],["<0x9F>",0.0],["<0x8D>",0.0],["<0xA3>",0.0],["x",-1.0]]}}
    """.trimIndent()

    private val tokenizer = UnigramTokenizer.fromJson(tokenizerJson())

    @Test
    fun viterbiPicksTheBestScoringSegmentation() {
        // 来週 (-6) beats 来 + 週 (-10); 予定 (-4) beats 予 + 定 (-12).
        assertContentEquals(intArrayOf(1, 6, 7, 9, 2), tokenizer.encode("来週の予定"))
        assertEquals(listOf(6, 8, 7), tokenizer.tokenize("来週 の"))
    }

    @Test
    fun unknownCharactersFallBackToBytesOrUnk() {
        // 🍣 is F0 9F 8D A3: all four byte pieces exist.
        assertEquals(listOf(12, 13, 14, 15, 7), tokenizer.tokenize("🍣の"))
        // ア is not in the vocabulary and its bytes are not either; consecutive unknowns fuse into one <unk>.
        assertEquals(listOf(7, 0, 7), tokenizer.tokenize("のアイの"))
    }

    @Test
    fun addedTokensAreCutOutFirst() {
        assertEquals(listOf(16, 1, 16, 2), tokenizer.tokenize("x<s>x</s>"))
    }

    @Test
    fun truncationKeepsTheEndToken() {
        assertContentEquals(intArrayOf(1, 16, 16, 2), tokenizer.encode("xxxxx", maxLength = 4))
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("x", maxLength = 1) }
    }

    @Test
    fun unsupportedConfigurationsAreRejected() {
        assertFailsWith<IllegalArgumentException> { UnigramTokenizer.fromJson(tokenizerJson(normalizer = """{"type":"NFKC"}""")) }
        assertFailsWith<IllegalArgumentException> { UnigramTokenizer.fromJson(tokenizerJson(prepend = "always")) }
    }

    /** last_hidden_state where token t of each row is the one-hot of (id % dim), times its position + 1. */
    private class FakeRuntime(val dim: Int) : EncoderRuntime {
        val batches = mutableListOf<Pair<Int, Int>>()
        var closed = false

        override fun lastHiddenState(inputIds: LongArray, attentionMask: LongArray, batch: Int, length: Int): FloatArray {
            batches += batch to length
            val out = FloatArray(batch * length * dim)
            for (r in 0 until batch) for (t in 0 until length) {
                val i = r * length + t
                // Padding gets a huge value: pooling must ignore it.
                out[i * dim + (inputIds[i] % dim).toInt()] = if (attentionMask[i] == 0L) 1000f else (t + 1).toFloat()
            }
            return out
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun meanPoolsTheRealTokensAndNormalizes() = runTest {
        val runtime = FakeRuntime(dim = 4)
        val spec = EmbeddingModelSpec("fake", 4, queryPrefix = "x", documentPrefix = "", maxTokens = 16)
        val embedder = OnnxEmbedder(spec, { tokenizer }, { runtime }, maxBatchSize = 4)
        // "来週" → <s>(1) 来週(6) </s>(2): one-hots of 1, 2, 2 weighted 1, 2, 3 → [0, 1, 5, 0] / 3, normalized.
        val vector = embedder.embedDocuments(listOf("来週")).single()
        val norm = sqrt(26f)
        floatArrayOf(0f, 1 / norm, 5 / norm, 0f).forEachIndexed { i, v -> assertEquals(v, vector[i], 1e-6f) }
        // The query prefix "x" (16 → one-hot 0) comes first.
        val query = embedder.embedQuery("来週")
        assertTrue(query[0] > 0f)

        // Texts of different lengths in one padded batch come back in input order, padding ignored.
        val batch = embedder.embedDocuments(listOf("来週の予定", "来週"))
        assertContentEquals(vector, batch[1])
        assertEquals(2 to 5, runtime.batches.last())
        assertEquals(3, embedder.countTokens("来週の予定"))

        embedder.release()
        assertTrue(runtime.closed)
    }

    @Test
    fun runsOneTextAtATimeByDefault() = runTest {
        val runtime = FakeRuntime(dim = 4)
        val embedder = OnnxEmbedder(EmbeddingModelSpec("fake", 4, "", "", 16), { tokenizer }, { runtime })
        embedder.embedDocuments(listOf("来週の予定", "来週", "の"))
        assertEquals(listOf(1, 1, 1), runtime.batches.map { it.first })
    }
}
