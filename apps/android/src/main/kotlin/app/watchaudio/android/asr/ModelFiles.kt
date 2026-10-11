package app.watchaudio.android.asr

import app.watchaudio.core.pipeline.AsrSettings
import java.io.File

/**
 * Where the on-device speech models live under [root] (`filesDir/models`):
 *
 * ```
 * models/
 *   silero_vad.onnx
 *   sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/
 *     encoder-epoch-99-avg-1.int8.onnx, decoder-…, joiner-…, tokens.txt
 * ```
 *
 * The layout matches `tools/harness/models`, so the same directory can be pushed with adb.
 */
class ModelFiles(val root: File) {
    val asrDir = File(root, AsrSettings.JA_MODEL_NAME)
    val encoder = File(asrDir, ENCODER)
    val decoder = File(asrDir, DECODER)
    val joiner = File(asrDir, JOINER)
    val tokens = File(asrDir, TOKENS)
    val vad = File(root, VAD)

    /** Every file the transcriber needs. */
    val all: List<File> get() = listOf(encoder, decoder, joiner, tokens, vad)

    /** Where [source] is placed under [root]. */
    fun fileFor(source: ModelSource): File = File(root, source.path)

    fun hasAsr(): Boolean = listOf(encoder, decoder, joiner, tokens).all(::isPresent)

    fun hasVad(): Boolean = isPresent(vad)

    fun isComplete(): Boolean = hasAsr() && hasVad()

    /** A file counts as present once it is non-empty; files only appear through an atomic rename. */
    fun isPresent(file: File) = file.isFile && file.length() > 0

    companion object {
        const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS = "tokens.txt"
        const val VAD = "silero_vad.onnx"
    }
}
