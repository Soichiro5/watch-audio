package app.watchaudio.android.asr

import app.watchaudio.core.pipeline.AsrModelFile
import app.watchaudio.core.pipeline.AsrModelSpec
import java.io.File

/**
 * Where the on-device speech models live under [root] (`filesDir/models`): the files of the ASR
 * model [spec] in its own directory, next to the shared Silero VAD model.
 *
 * ```
 * models/
 *   silero_vad.onnx
 *   sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/   (AsrModelSpec.id)
 *     encoder-epoch-99-avg-1.int8.onnx, decoder-…, joiner-…, tokens.txt
 * ```
 *
 * The layout matches `tools/harness/models`, so the same directory can be pushed with adb.
 */
class ModelFiles(val root: File, val spec: AsrModelSpec) {
    val asrDir = File(root, spec.id)
    val vad = File(root, VAD)

    /** The [role] file of the ASR model. */
    fun asr(role: AsrModelFile): File = File(root, spec.path(role))

    /** The files of the ASR model ([AsrModelSpec.kind] decides which). */
    val asrFiles: List<File> get() = spec.kind.files.map(::asr)

    /** Every file the transcriber needs. */
    val all: List<File> get() = asrFiles + vad

    /** Where [source] is placed under [root]. */
    fun fileFor(source: ModelSource): File = File(root, source.path)

    fun hasAsr(): Boolean = asrFiles.all(::isPresent)

    fun hasVad(): Boolean = isPresent(vad)

    fun isComplete(): Boolean = hasAsr() && hasVad()

    /** A file counts as present once it is non-empty; files only appear through an atomic rename. */
    fun isPresent(file: File) = file.isFile && file.length() > 0

    companion object {
        const val VAD = AsrModelSpec.VAD_FILE
    }
}
