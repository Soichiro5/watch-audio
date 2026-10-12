package app.watchaudio.android.search

import app.watchaudio.core.embedding.EncoderRuntime
import app.watchaudio.core.embedding.OnnxEmbedder
import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.embedding.UnigramTokenizer
import app.watchaudio.core.ort.LongTensor
import app.watchaudio.core.ort.OrtApi
import app.watchaudio.core.ort.OrtSession
import java.io.File

/**
 * The embedding model of semantic search (#24) on Android: the downloaded files under
 * `files/models/ruri-v3-30m` ([app.watchaudio.android.asr.ModelManifest.embeddingFiles]), the
 * pure-Kotlin tokenizer, and ONNX Runtime's C API through JNA ([OrtApi]) on the
 * `libonnxruntime.so` that sherpa-onnx ships in the APK (one runtime for ASR and embeddings; see
 * docs/platforms/android-phone.md). Everything runs on the device; nothing is sent.
 *
 * One intra-op thread: passages are short (~3 ms each on a desktop core), and ONNX Runtime's
 * thread pool spins while waiting, which costs battery in the background.
 */
object AndroidEmbedding {
    fun embedder(modelsRoot: File): OnnxEmbedder {
        val dir = File(modelsRoot, RuriV3.DIRECTORY)
        return OnnxEmbedder(
            spec = RuriV3.SPEC,
            loadTokenizer = { UnigramTokenizer.fromJson(File(dir, RuriV3.TOKENIZER_FILE).readText()) },
            openRuntime = { OrtEncoder(OrtApi.load("onnxruntime").openSession(File(dir, RuriV3.MODEL_FILE).path, threads = 1)) },
        )
    }
}

/** [EncoderRuntime] over an [OrtSession]: the model's `last_hidden_state`. */
internal class OrtEncoder(private val session: OrtSession) : EncoderRuntime {
    override fun lastHiddenState(inputIds: LongArray, attentionMask: LongArray, batch: Int, length: Int): FloatArray {
        val shape = longArrayOf(batch.toLong(), length.toLong())
        return session.run(
            linkedMapOf("input_ids" to LongTensor(inputIds, shape), "attention_mask" to LongTensor(attentionMask, shape)),
            "last_hidden_state",
        ).values
    }

    override fun close() = session.close()
}
