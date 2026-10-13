package app.watchaudio.android.asr

import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.pipeline.AsrModelFile
import app.watchaudio.core.pipeline.AsrModelSpec

/**
 * One model file to download: [url] (download only, nothing is sent), its [path] under the
 * models root (see [ModelFiles]), the exact [size] in bytes and its lowercase hex [sha256].
 */
data class ModelSource(val url: String, val path: String, val size: Long, val sha256: String)

/**
 * The files the app downloads (the ASR model [spec] and the Silero VAD), fetched one by one so that only the int8 model (~161 MB in
 * total) is transferred instead of the ~713 MB sherpa-onnx release archive with fp32 models.
 *
 * The ASR files come from the Hugging Face repository of the sherpa-onnx maintainer that the
 * release archive was built from, pinned to a commit so the bytes cannot change under us. The
 * hashes are the Git LFS SHA-256 of that commit and match the files in the release archive
 * (and the official `reazon-research/reazonspeech-k2-v2` repository).
 *
 * [englishFiles] are fetched the same way, only while 「英語も文字起こしする」 is on (#81), and
 * [embeddingFiles] (semantic search, #24) automatically on an unmetered network.
 *
 * `tools/harness/download-models.sh` must download the same files, [englishFiles] included
 * (checked by a unit test).
 */
object ModelManifest {
    /** The ASR model the app downloads; the sizes and hashes below are of its files. */
    val spec: AsrModelSpec = AsrModelSpec.JA_REAZONSPEECH

    private const val HF_REPO = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2"
    private const val HF_COMMIT = "7ebed8f70b8ef67f656aa08e3084e5885202d0e6"
    private const val HF = "$HF_REPO/resolve/$HF_COMMIT"
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val files: List<ModelSource> = listOf(
        ModelSource(
            url = "$RELEASES/${AsrModelSpec.VAD_FILE}",
            path = AsrModelSpec.VAD_FILE,
            size = 643_854,
            sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        ),
        asrFile(AsrModelFile.TOKENS, size = 45_754, sha256 = "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"),
        asrFile(AsrModelFile.DECODER, size = 2_959_337, sha256 = "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485"),
        asrFile(AsrModelFile.JOINER, size = 2_696_970, sha256 = "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de"),
        asrFile(AsrModelFile.ENCODER, size = 154_670_139, sha256 = "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"),
    )

    /** Bytes transferred by a first download (~161 MB). */
    val totalBytes: Long get() = files.sumOf { it.size }

    /** The [role] file of [spec], from the pinned Hugging Face commit. */
    private fun asrFile(role: AsrModelFile, size: Long, sha256: String) =
        ModelSource(url = "$HF/${spec.fileName(role)}", path = spec.path(role), size = size, sha256 = sha256)

    /**
     * The extra model for 「英語も文字起こしする」 (#81), downloaded only while it is on: SenseVoice
     * Small int8 (~240 MB), routed in front of [spec] ([app.watchaudio.core.pipeline.LanguageRouting]).
     */
    val englishSpec: AsrModelSpec = AsrModelSpec.SENSE_VOICE

    /**
     * The sherpa-onnx maintainer's Hugging Face repository the `sherpa-onnx-sense-voice-…` release
     * archive was made from, pinned to a commit; the hashes are its Git LFS SHA-256 (the model) and
     * the SHA-256 of the file (tokens), equal to the files in the release archive.
     */
    private const val SENSE_VOICE_HF =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"

    val englishFiles: List<ModelSource> = listOf(
        englishFile(AsrModelFile.TOKENS, size = 315_894, sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
        englishFile(AsrModelFile.MODEL, size = 239_233_841, sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
    )

    /** Bytes of the English model download (~240 MB). */
    val englishBytes: Long get() = englishFiles.sumOf { it.size }

    private fun englishFile(role: AsrModelFile, size: Long, sha256: String) =
        ModelSource(url = "$SENSE_VOICE_HF/${englishSpec.fileName(role)}", path = englishSpec.path(role), size = size, sha256 = sha256)

    /**
     * The embedding model of semantic search (#24, [RuriV3.FILES], shared with iOS): the int8 ONNX
     * export and the tokenizer, each pinned to a commit. ~44 MB, downloaded automatically, Wi-Fi only.
     */
    val embeddingFiles: List<ModelSource> = RuriV3.FILES.map { ModelSource(it.url, it.path, it.size, it.sha256) }

    /** Bytes of the embedding model download (~44 MB). */
    val embeddingBytes: Long get() = embeddingFiles.sumOf { it.size }
}
