import ComposeApp
import Foundation
import OnnxRuntimeBindings

/// Semantic search on iOS (#178): implements the Kotlin `IosEmbeddingPlatform` with ONNX Runtime's
/// Objective-C API (the `onnxruntime` Swift package, pinned in project.yml) for the embedding model
/// and `ModelDownload` for its Wi-Fi-only download. The logic (tokenizer, pooling, the index, which
/// files to fetch and how they are checked) is Kotlin's (`IosEmbedding`, `ModelInstaller`).
final class EmbeddingPlatform: NSObject, IosEmbeddingPlatform, @unchecked Sendable {
    var runtimeVersion: String {
        ORTVersion() ?? "unknown"
    }

    func openEncoder(modelPath: String, threads: Int32) -> IosEncoderOpenResult {
        do {
            return IosEncoderOpenResult(session: try OrtEncoderSession(modelPath: modelPath, threads: threads), error: nil)
        } catch {
            return IosEncoderOpenResult(session: nil, error: error.localizedDescription)
        }
    }

    func download(
        url: String,
        partPath: String,
        offset: Int64,
        onProgress: @escaping (KotlinLong) -> Void,
        onWaiting: @escaping () -> Void,
        callback: @escaping (String?) -> Void
    ) -> any IosDownloadHandle {
        ModelDownload.start(
            url: url,
            partPath: partPath,
            offset: offset,
            onProgress: { onProgress(KotlinLong(value: $0)) },
            onWaiting: onWaiting,
            callback: callback
        )
    }
}

/// One ONNX Runtime session of the embedding model on the CPU (no other execution provider: the
/// vectors must match the other platforms'). The model's input names are those of the
/// `onnx-community/ruri-v3-30m-ONNX` export, as on Android (`AndroidEmbedding.kt`).
final class OrtEncoderSession: NSObject, IosEncoderSession, @unchecked Sendable {
    private let env: ORTEnv
    private var session: ORTSession?

    init(modelPath: String, threads: Int32) throws {
        env = try ORTEnv(loggingLevel: .warning)
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(threads)
        session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
    }

    func run(inputIds: Data, attentionMask: Data, batch: Int32, length: Int32) -> IosEncoderOutput {
        guard let session else { return IosEncoderOutput(hiddenState: nil, error: "the session is closed") }
        do {
            let shape = [NSNumber(value: batch), NSNumber(value: length)]
            let ids = try ORTValue(tensorData: NSMutableData(data: inputIds), elementType: .int64, shape: shape)
            let mask = try ORTValue(tensorData: NSMutableData(data: attentionMask), elementType: .int64, shape: shape)
            let outputs = try session.run(
                withInputs: ["input_ids": ids, "attention_mask": mask],
                outputNames: ["last_hidden_state"],
                runOptions: nil
            )
            guard let hidden = outputs["last_hidden_state"] else {
                return IosEncoderOutput(hiddenState: nil, error: "no last_hidden_state")
            }
            return IosEncoderOutput(hiddenState: try hidden.tensorData() as Data, error: nil)
        } catch {
            return IosEncoderOutput(hiddenState: nil, error: error.localizedDescription)
        }
    }

    func close() {
        session = nil
    }
}
