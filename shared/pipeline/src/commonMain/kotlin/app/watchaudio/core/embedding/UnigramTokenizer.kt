package app.watchaudio.core.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SentencePiece Unigram tokenizer that reproduces Hugging Face `tokenizers` (`Tokenizer.encode`)
 * for the embedding model's `tokenizer.json` (#24, ruri-v3): pure Kotlin, so Android and iOS
 * produce the same ids without a native tokenizer library.
 *
 * Supported configuration (anything else is rejected by [fromJson], so a model change is noticed):
 * no normalizer; a Metaspace pre-tokenizer that only replaces spaces (`prepend_scheme` never, no
 * split); a Unigram model with byte fallback; a `<s> A </s>` template. The steps mirror the Rust
 * implementation: added tokens are cut out first (leftmost-longest), spaces become `▁`, the best
 * segmentation is found by Viterbi over the piece scores (ties keep the first candidate, unknown
 * characters cost the lowest score minus 10 and consecutive ones are fused), then unknown pieces
 * fall back to their UTF-8 bytes (`<0xE3>`) and the template adds the special tokens.
 * Checked token-for-token against the Python library (`UnigramTokenizerGoldenTest`).
 */
class UnigramTokenizer private constructor(
    private val ids: Map<String, Int>,
    private val scores: DoubleArray,
    private val maxPieceLength: Int,
    private val unkId: Int,
    private val unkScore: Double,
    private val byteIds: IntArray?,
    private val addedTokens: List<Pair<String, Int>>,
    /** Id of the template's first token (`<s>`). */
    val bosId: Int,
    /** Id of the template's last token (`</s>`). */
    val eosId: Int,
    /** Id of the padding token (`<pad>`), for batches. */
    val padId: Int,
) {
    /** Number of pieces in the vocabulary. */
    val vocabularySize: Int get() = scores.size

    /**
     * Ids of [text] with `<s>` and `</s>`, at most [maxLength] in total (the text's tokens are cut
     * at the end, as the library's truncation does).
     */
    fun encode(text: String, maxLength: Int = Int.MAX_VALUE): IntArray {
        require(maxLength >= 2) { "maxLength must leave room for <s> and </s>" }
        val body = tokenize(text)
        val kept = minOf(body.size, maxLength - 2)
        val out = IntArray(kept + 2)
        out[0] = bosId
        for (i in 0 until kept) out[i + 1] = body[i]
        out[kept + 1] = eosId
        return out
    }

    /** Ids of [text] without the template's special tokens. */
    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>(text.length)
        var start = 0
        while (start < text.length) {
            val (at, token) = nextAddedToken(text, start) ?: break
            if (at > start) encodeSection(text.substring(start, at), out)
            out += token.second
            start = at + token.first.length
        }
        if (start < text.length) encodeSection(text.substring(start), out)
        return out
    }

    /** The leftmost added token at or after [from] (the longest one if several start there). */
    private fun nextAddedToken(text: String, from: Int): Pair<Int, Pair<String, Int>>? {
        if (addedTokens.isEmpty()) return null
        var i = from
        while (i < text.length) {
            var best: Pair<String, Int>? = null
            for (token in addedTokens) {
                if (text.startsWith(token.first, i) && token.first.length > (best?.first?.length ?: 0)) best = token
            }
            if (best != null) return i to best
            i++
        }
        return null
    }

    /** Metaspace, Viterbi and byte fallback for a section between added tokens. */
    private fun encodeSection(section: String, out: MutableList<Int>) {
        val text = section.replace(' ', METASPACE)
        for (piece in viterbi(text)) {
            val id = ids[piece]
            if (id != null) {
                out += id
                continue
            }
            val bytes = byteIds?.let { table -> piece.encodeToByteArray().map { table[it.toInt() and 0xff] } }
            if (bytes != null && bytes.all { it >= 0 }) out += bytes else out += unkId
        }
    }

    /** Best segmentation of [text] into pieces (unknown runs fused into one string). */
    private fun viterbi(text: String): List<String> {
        val n = text.length
        if (n == 0) return emptyList()
        val bestScore = DoubleArray(n + 1)
        val startsAt = IntArray(n + 1) { -1 }
        val tokenAt = IntArray(n + 1) { -1 }
        var start = 0
        while (start < n) {
            val here = bestScore[start]
            val charLength = if (text[start].isHighSurrogate() && start + 1 < n && text[start + 1].isLowSurrogate()) 2 else 1
            var hasSingle = false
            val longest = minOf(maxPieceLength, n - start)
            for (length in 1..longest) {
                val end = start + length
                val id = ids[text.substring(start, end)] ?: continue
                val candidate = here + scores[id]
                if (startsAt[end] < 0 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    startsAt[end] = start
                    tokenAt[end] = id
                }
                if (length == charLength) hasSingle = true
            }
            if (!hasSingle) {
                val end = start + charLength
                val candidate = here + unkScore
                if (startsAt[end] < 0 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    startsAt[end] = start
                    tokenAt[end] = unkId
                }
            }
            start += charLength
        }
        val pieces = ArrayList<String>()
        val unknown = ArrayList<String>()
        var end = n
        while (end > 0) {
            val from = startsAt[end]
            val piece = text.substring(from, end)
            if (tokenAt[end] == unkId) {
                unknown += piece
            } else {
                if (unknown.isNotEmpty()) {
                    pieces += unknown.asReversed().joinToString("")
                    unknown.clear()
                }
                pieces += piece
            }
            end = from
        }
        if (unknown.isNotEmpty()) pieces += unknown.asReversed().joinToString("")
        pieces.reverse()
        return pieces
    }

    companion object {
        private const val METASPACE = '▁'

        /** The unknown-character penalty below the lowest piece score (`K_UNK_PENALTY` in `tokenizers`). */
        private const val UNK_PENALTY = 10.0

        /** Reads a Hugging Face `tokenizer.json`; throws [IllegalArgumentException] for an unsupported setup. */
        fun fromJson(json: String): UnigramTokenizer {
            val root = Json.parseToJsonElement(json).jsonObject
            require(root["normalizer"].let { it == null || it is JsonNull }) { "normalizers are not supported" }
            val pre = root["pre_tokenizer"]?.jsonObject ?: throw IllegalArgumentException("Metaspace pre-tokenizer expected")
            require(pre.string("type") == "Metaspace") { "Metaspace pre-tokenizer expected" }
            require(pre.string("replacement") == METASPACE.toString()) { "unsupported Metaspace replacement" }
            require(pre.string("prepend_scheme") == "never") { "only prepend_scheme=never is supported" }
            require(pre["split"]?.jsonPrimitive?.booleanOrNull == false) { "only split=false is supported" }

            val model = root["model"]?.jsonObject ?: throw IllegalArgumentException("model missing")
            require(model.string("type") == "Unigram") { "Unigram model expected" }
            val vocab = model["vocab"]!!.jsonArray
            val ids = HashMap<String, Int>(vocab.size * 2)
            val scores = DoubleArray(vocab.size)
            var maxLength = 1
            var minScore = Double.MAX_VALUE
            vocab.forEachIndexed { id, entry ->
                val pair = entry.jsonArray
                val piece = pair[0].jsonPrimitive.content
                val score = pair[1].jsonPrimitive.double
                ids[piece] = id
                scores[id] = score
                if (piece.length > maxLength) maxLength = piece.length
                if (score < minScore) minScore = score
            }
            val unkId = model["unk_id"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("unk_id missing")
            val byteFallback = model["byte_fallback"]?.jsonPrimitive?.boolean ?: false
            val byteIds = if (byteFallback) IntArray(256) { ids[byteToken(it)] ?: -1 } else null

            val added = root["added_tokens"]?.jsonArray.orEmpty().map { token ->
                val entry = token.jsonObject
                require(entry["normalized"]?.jsonPrimitive?.boolean != true) { "normalized added tokens are not supported" }
                require(entry["lstrip"]?.jsonPrimitive?.boolean != true && entry["rstrip"]?.jsonPrimitive?.boolean != true) {
                    "stripping added tokens are not supported"
                }
                require(entry["single_word"]?.jsonPrimitive?.boolean != true) { "single-word added tokens are not supported" }
                (entry.string("content") ?: throw IllegalArgumentException("added token without content")) to entry["id"]!!.jsonPrimitive.int
            }

            val (bos, eos) = template(root["post_processor"]?.jsonObject ?: throw IllegalArgumentException("post_processor missing"), ids)
            val pad = ids["<pad>"] ?: throw IllegalArgumentException("<pad> missing")
            return UnigramTokenizer(ids, scores, maxLength, unkId, minScore - UNK_PENALTY, byteIds, added, bos, eos, pad)
        }

        /** The ids of the template `<special> A <special>`. */
        private fun template(post: JsonObject, ids: Map<String, Int>): Pair<Int, Int> {
            require(post.string("type") == "TemplateProcessing") { "TemplateProcessing expected" }
            val single = post["single"]!!.jsonArray.map { it.jsonObject }
            require(single.size == 3 && "Sequence" in single[1]) { "template <s> A </s> expected" }
            fun special(item: JsonObject): Int {
                val name = item["SpecialToken"]?.jsonObject?.string("id") ?: throw IllegalArgumentException("special token expected")
                return ids[name] ?: throw IllegalArgumentException("$name missing")
            }
            return special(single[0]) to special(single[2])
        }

        private fun byteToken(byte: Int): String = "<0x" + byte.toString(16).uppercase().padStart(2, '0') + ">"

        private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
