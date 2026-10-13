package app.watchaudio.app

import platform.Foundation.NSData

/**
 * What semantic search (#178) needs from Swift on iOS, implemented in
 * `apps/ios/App/Embedding/EmbeddingPlatform.swift` and handed to [IosAppGraph.start], like
 * [IosSpeechTranscriber]: ONNX Runtime (its Objective-C API from the pinned Swift package) to run
 * the embedding model, and `URLSession` to download the model's files on Wi-Fi only. Kotlin keeps
 * the logic (tokenizer, pooling, the index, which files to fetch and how they are checked:
 * [IosEmbedding], [app.watchaudio.core.embedding.ModelInstaller]).
 */
interface IosEmbeddingPlatform {
    /** ONNX Runtime's version, e.g. `1.24.2` (diagnostics and the simulator check). */
    val runtimeVersion: String

    /**
     * Opens the ONNX model at [modelPath] (absolute) on the CPU with [threads] intra-op threads.
     * Blocking: call off the main thread.
     */
    fun openEncoder(modelPath: String, threads: Int): IosEncoderOpenResult

    /**
     * Downloads [url] into the file at [partPath], on Wi-Fi only (no cellular, no expensive or
     * constrained network; it waits for one, reporting [onWaiting]). With [offset] > 0 the file
     * already holds the first [offset] bytes and only the rest is asked for (HTTP range); if the
     * server sends the whole file, the file is started over. [onProgress] gets the bytes in the file
     * so far. [callback] gets null once everything arrived, or a short error code without the URL
     * (e.g. `http_404`, `url_error_-1009`), exactly once. Callbacks run on any thread.
     */
    fun download(
        url: String,
        partPath: String,
        offset: Long,
        onProgress: (Long) -> Unit,
        onWaiting: () -> Unit,
        callback: (String?) -> Unit,
    ): IosDownloadHandle
}

/** [IosEmbeddingPlatform.openEncoder]: the session, or the error. */
class IosEncoderOpenResult(val session: IosEncoderSession?, val error: String?)

/** One ONNX Runtime session of the embedding model; used by one thread at a time. */
interface IosEncoderSession {
    /**
     * Runs the model on [batch] rows of [length] tokens: [inputIds] and [attentionMask] hold
     * `batch * length` int64 values each (native byte order). Returns `last_hidden_state` as
     * `batch * length * dimension` float32 values. Blocking.
     */
    fun run(inputIds: NSData, attentionMask: NSData, batch: Int, length: Int): IosEncoderOutput

    /** Frees the session; it is not used afterwards. */
    fun close()
}

/** [IosEncoderSession.run]: the hidden state, or the error. */
class IosEncoderOutput(val hiddenState: NSData?, val error: String?)

/** A running [IosEmbeddingPlatform.download]; cancelling makes its callback report an error. */
interface IosDownloadHandle {
    fun cancel()
}
