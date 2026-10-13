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
 * The files live in [DIRECTORY] under the models root (Android: `files/models`, iOS:
 * `Application Support/WatchAudio/models`, the harness: `tools/harness/models`).
 */
object RuriV3 {
    const val DIRECTORY = "ruri-v3-30m"
    const val MODEL_FILE = "model_quantized.onnx"
    const val TOKENIZER_FILE = "tokenizer.json"

    /**
     * The int8 ONNX export of `onnx-community/ruri-v3-30m-ONNX` and the tokenizer of the original
     * `cl-nagoya/ruri-v3-30m`, each pinned to a commit (the model's hash is its Git LFS SHA-256, the
     * tokenizer's the SHA-256 of the file).
     */
    private const val ONNX_HF = "https://huggingface.co/onnx-community/ruri-v3-30m-ONNX/resolve/1b41a27f31213fc55a9c4653c067770e39406408"
    private const val TOKENIZER_HF = "https://huggingface.co/cl-nagoya/ruri-v3-30m/resolve/24899e5de370b56d179604a007c0d727bf144504"

    /**
     * The files both phones download (~44 MB, Wi-Fi only, once the search tab is first opened):
     * Android's `ModelManifest.embeddingFiles`, iOS's [ModelInstaller]. `tools/harness/download-models.sh`
     * fetches the same (checked by an Android unit test).
     */
    val FILES: List<ModelDownload> = listOf(
        ModelDownload(
            url = "$ONNX_HF/onnx/$MODEL_FILE",
            path = "$DIRECTORY/$MODEL_FILE",
            size = 37_048_575,
            sha256 = "bd07545fa4eb8e7765939536944da1138a7e61441d81633adfa1faf218df6bb1",
        ),
        ModelDownload(
            url = "$TOKENIZER_HF/$TOKENIZER_FILE",
            path = "$DIRECTORY/$TOKENIZER_FILE",
            size = 6_724_873,
            sha256 = "0a94ac9a0a02c067bdef25b72ae9f4ee33f48f552e55988d444f6d25eeb1d062",
        ),
    )

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
 * Runtime on Android, iOS and the JVM), fakes in tests.
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
 * (`shared/ort`), iOS its Objective-C API from Swift (`apps/ios/App/Embedding`, #178); nothing
 * leaves the device.
 */
interface EncoderRuntime : AutoCloseable {
    /**
     * @param inputIds [batch] rows of [length] ids, row after row (padded with the pad id).
     * @param attentionMask 1 for real tokens, 0 for padding, same layout.
     * @return `batch * length * dimension` floats, row after row.
     */
    fun lastHiddenState(inputIds: LongArray, attentionMask: LongArray, batch: Int, length: Int): FloatArray
}
