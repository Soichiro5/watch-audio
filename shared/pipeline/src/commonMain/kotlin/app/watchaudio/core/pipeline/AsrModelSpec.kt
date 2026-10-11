package app.watchaudio.core.pipeline

/**
 * How a sherpa-onnx offline recognizer is built from its files. Each kind needs a fixed set of
 * [files]; new kinds (e.g. a multilingual CTC model for #81) are added here and to the
 * recognizer factories of Android (`AndroidSherpaTranscriber`) and the harness
 * (`SherpaTranscriber`).
 */
enum class RecognizerKind(val files: Set<AsrModelFile>) {
    /** Zipformer transducer (encoder, decoder and joiner) with greedy search. */
    TRANSDUCER(setOf(AsrModelFile.ENCODER, AsrModelFile.DECODER, AsrModelFile.JOINER, AsrModelFile.TOKENS)),
}

/** The role of one file of an offline recognizer model. */
enum class AsrModelFile { ENCODER, DECODER, JOINER, TOKENS }

/**
 * One on-device speech recognition model for the sherpa-onnx path: everything that is specific
 * to the model rather than to the platform.
 *
 * Files live under `<models root>/<[id]>/` on Android and in the harness, next to the shared
 * Silero VAD model ([VAD_FILE] in the models root).
 */
data class AsrModelSpec(
    /** Directory name of the model under the models root (the sherpa-onnx release name). */
    val id: String,
    /** Stored with each segment ([Transcriber.engineId]). */
    val engineId: String,
    /**
     * BCP 47 language of the produced text, stored with each segment, or null for a model that
     * reports the language itself ([Recognition.lang]).
     */
    val language: String?,
    val kind: RecognizerKind,
    /** File name, inside the model directory, of each file the [kind] needs. */
    val files: Map<AsrModelFile, String>,
) {
    init {
        require(files.keys == kind.files) { "$kind needs ${kind.files}, got ${files.keys}" }
    }

    /** The name of the [role] file inside the model directory. */
    fun fileName(role: AsrModelFile): String = files.getValue(role)

    /** The path of the [role] file relative to the models root (`<id>/<name>`). */
    fun path(role: AsrModelFile): String = "$id/${fileName(role)}"

    companion object {
        /** Silero VAD model, in the models root and shared by every [AsrModelSpec]. */
        const val VAD_FILE = "silero_vad.onnx"

        /** ReazonSpeech Zipformer (int8), Japanese: the model the apps ship with. */
        val JA_REAZONSPEECH = AsrModelSpec(
            id = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
            engineId = "sherpa-zipformer-ja-reazonspeech-int8",
            language = "ja",
            kind = RecognizerKind.TRANSDUCER,
            files = mapOf(
                AsrModelFile.ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
                AsrModelFile.DECODER to "decoder-epoch-99-avg-1.int8.onnx",
                AsrModelFile.JOINER to "joiner-epoch-99-avg-1.int8.onnx",
                AsrModelFile.TOKENS to "tokens.txt",
            ),
        )

        /** The model the Android app downloads and the harness evaluates by default. */
        val DEFAULT = JA_REAZONSPEECH
    }
}
