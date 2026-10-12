package app.watchaudio.core.embedding

/**
 * A text embedding model as the app uses it (#24): [id] is stored with every vector, so changing
 * the model (or its files) re-embeds everything ([app.watchaudio.core.db.EmbeddingQueries]).
 */
data class EmbeddingModelSpec(
    val id: String,
    val dimension: Int,
    /** Put in front of a search query (the model was trained with it). */
    val queryPrefix: String,
    /** Put in front of a stored passage. */
    val documentPrefix: String,
    /** Longest input in tokens, special tokens included; longer texts are cut. */
    val maxTokens: Int,
)

/**
 * The model chosen in M0-6 (`docs/research/m0-6-embedding-desktop.md`): `cl-nagoya/ruri-v3-30m`,
 * Japanese, 256 dimensions, the dynamically quantized int8 ONNX export of
 * `onnx-community/ruri-v3-30m-ONNX` (37 MB) with the original `tokenizer.json`. Apache-2.0.
 * The files live in [DIRECTORY] under the models root (Android: `files/models`, the harness:
 * `tools/harness/models`).
 */
object RuriV3 {
    const val DIRECTORY = "ruri-v3-30m"
    const val MODEL_FILE = "model_quantized.onnx"
    const val TOKENIZER_FILE = "tokenizer.json"

    val SPEC = EmbeddingModelSpec(
        // The ONNX export's commit: new model bytes need a new id.
        id = "ruri-v3-30m-int8@1b41a27",
        dimension = 256,
        queryPrefix = "検索クエリ: ",
        documentPrefix = "検索文書: ",
        maxTokens = 512,
    )
}

/**
 * Turns text into unit-length vectors whose dot product measures how close the meanings are (#24).
 * Implementations run on the device only and never send text anywhere: [OnnxEmbedder] (ONNX
 * Runtime on Android and the JVM), fakes in tests. iOS has none yet (keyword search only).
 */
interface Embedder {
    val spec: EmbeddingModelSpec

    /** Tokens [text] takes as part of a passage (without the prefix and special tokens). */
    suspend fun countTokens(text: String): Int

    /** Embeddings of stored passages ([EmbeddingModelSpec.documentPrefix]), in order. */
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>

    /** Embedding of a search query ([EmbeddingModelSpec.queryPrefix]). */
    suspend fun embedQuery(text: String): FloatArray

    /** Frees the model's memory; the next call loads it again. */
    suspend fun release() {}
}

/**
 * The platform part of [OnnxEmbedder]: runs the model's transformer on a padded batch of token ids
 * and returns its last hidden state. Android and the JVM use ONNX Runtime's C API
 * (`shared/ort`); nothing leaves the device.
 */
interface EncoderRuntime : AutoCloseable {
    /**
     * @param inputIds [batch] rows of [length] ids, row after row (padded with the pad id).
     * @param attentionMask 1 for real tokens, 0 for padding, same layout.
     * @return `batch * length * dimension` floats, row after row.
     */
    fun lastHiddenState(inputIds: LongArray, attentionMask: LongArray, batch: Int, length: Int): FloatArray
}
