package app.watchaudio.harness

import app.watchaudio.core.embedding.EncoderRuntime
import app.watchaudio.core.embedding.OnnxEmbedder
import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.embedding.UnigramTokenizer
import app.watchaudio.core.ort.LongTensor
import app.watchaudio.core.ort.OrtApi
import app.watchaudio.core.ort.OrtSession
import java.io.File
import java.nio.file.Files

/**
 * The embedding model of semantic search (#24) as the app runs it, on this machine: the files of
 * `tools/harness/models/ruri-v3-30m` (the app's download, `download-models.sh`), the pure-Kotlin
 * tokenizer and ONNX Runtime's C API ([OrtApi]) on the runtime sherpa-onnx ships for Linux.
 */
object HarnessEmbedder {
    fun modelDir(models: File) = File(models, RuriV3.DIRECTORY)

    fun available(models: File): Boolean =
        File(modelDir(models), RuriV3.MODEL_FILE).isFile && File(modelDir(models), RuriV3.TOKENIZER_FILE).isFile

    fun create(models: File, threads: Int = 1): OnnxEmbedder {
        val dir = modelDir(models)
        return OnnxEmbedder(
            spec = RuriV3.SPEC,
            loadTokenizer = { UnigramTokenizer.fromJson(File(dir, RuriV3.TOKENIZER_FILE).readText()) },
            openRuntime = { OrtEncoder(ortApi().openSession(File(dir, RuriV3.MODEL_FILE).path, threads)) },
        )
    }

    /** ONNX Runtime from sherpa-onnx's Linux native-library jar (the harness's ASR runtime). */
    fun ortApi(): OrtApi = OrtApi.load(runtimeLibrary.path)

    private val runtimeLibrary: File by lazy {
        val resource = "sherpa-onnx/native/linux-x64/libonnxruntime.so"
        val dir = Files.createTempDirectory("harness-ort").toFile().apply { deleteOnExit() }
        File(dir, "libonnxruntime.so").apply {
            deleteOnExit()
            HarnessEmbedder::class.java.classLoader.getResourceAsStream(resource)!!.use { input -> outputStream().use { input.copyTo(it) } }
        }
    }
}

/** [EncoderRuntime] over an [OrtSession]: the model's `last_hidden_state`. Same as the app's. */
class OrtEncoder(private val session: OrtSession) : EncoderRuntime {
    override fun lastHiddenState(inputIds: LongArray, attentionMask: LongArray, batch: Int, length: Int): FloatArray {
        val shape = longArrayOf(batch.toLong(), length.toLong())
        return session.run(
            linkedMapOf("input_ids" to LongTensor(inputIds, shape), "attention_mask" to LongTensor(attentionMask, shape)),
            "last_hidden_state",
        ).values
    }

    override fun close() = session.close()
}
