import ComposeApp
import Foundation

/// Hour summaries on the iPhone (#196): implements the Kotlin `IosLanguageModel` over
/// `OnDeviceLanguageModel` (Apple's on-device Foundation Models; that file is the only one that
/// uses the framework). The chunking, checking and storing are Kotlin's (`SummaryRun`). Callbacks
/// run on any thread, exactly once.
final class LanguageModelPlatform: NSObject, IosLanguageModel, @unchecked Sendable {
    var availability: String {
        OnDeviceLanguageModel.availabilityCode()
    }

    var modelVersion: String {
        OnDeviceLanguageModel.modelVersion
    }

    var contextSize: Int64 {
        Int64(OnDeviceLanguageModel.contextSize)
    }

    func tokenCount(text: String, callback: @escaping (KotlinLong) -> Void) {
        let reply = UncheckedSendableBox(callback)
        Task {
            let count = await OnDeviceLanguageModel.tokenCount(text)
            reply.value(KotlinLong(value: Int64(count ?? -1)))
        }
    }

    func generate(
        instructions: String,
        prompt: String,
        schemaJson: String,
        maxOutputTokens: Int64,
        callback: @escaping (String?, String?) -> Void
    ) -> any IosLanguageModelTask {
        let reply = UncheckedSendableBox(callback)
        let task = Task {
            let result = await OnDeviceLanguageModel.generate(
                instructions: instructions,
                prompt: prompt,
                schemaJson: schemaJson,
                maxOutputTokens: Int(maxOutputTokens)
            )
            switch result {
            case .success(let json): reply.value(json, nil)
            case .failure(let failure): reply.value(nil, failure.code)
            }
        }
        return LanguageModelTask(task)
    }
}

/// A running generation; cancelling stops the model (its callback then reports `cancelled`).
final class LanguageModelTask: NSObject, IosLanguageModelTask, @unchecked Sendable {
    private let task: Task<Void, Never>

    init(_ task: Task<Void, Never>) {
        self.task = task
        super.init()
    }

    func cancel() {
        task.cancel()
    }
}
