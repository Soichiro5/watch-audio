import Foundation
import FoundationModels

/// Apple's on-device Foundation Models for hour summaries (#196, docs/research/on-device-llm.md
/// §2.1 and §5). The only file of the app that may use the framework (`tools/privacy-check`,
/// `SINGLE_FILE_FRAMEWORKS`): it uses `SystemLanguageModel`, the model on this device, and nothing
/// that reaches a server (no Private Cloud Compute, no other company's model, no feedback
/// attachments). The model is installed and updated by the OS; the app has no URL for it.
///
/// Pure Swift without the Kotlin framework, so `LanguageModelTests` can check the parts that do not
/// need the model (availability codes, the schema from Kotlin's JSON, error codes) on any simulator.
/// The Kotlin bridge is `LanguageModelPlatform`.
enum OnDeviceLanguageModel {
    /// Summaries are made from Japanese conversations and written in Japanese.
    static let japanese = Locale(identifier: "ja_JP")

    /// The system model with the guardrails meant for transforming the user's own text (summaries),
    /// which also accept sensitive topics in the input (design §8 decision 9).
    static func model() -> SystemLanguageModel {
        SystemLanguageModel(useCase: .general, guardrails: .permissiveContentTransformations)
    }

    /// `available`, or why not (the codes of Kotlin's `IosLlmProvider.availabilityOf`).
    static func availabilityCode() -> String {
        let model = SystemLanguageModel.default
        return availabilityCode(model.availability, supportsJapanese: model.supportsLocale(japanese))
    }

    static func availabilityCode(_ availability: SystemLanguageModel.Availability, supportsJapanese: Bool) -> String {
        switch availability {
        case .available:
            return supportsJapanese ? "available" : "language_not_supported"
        case .unavailable(let reason):
            switch reason {
            case .deviceNotEligible: return "device_not_eligible"
            case .appleIntelligenceNotEnabled: return "apple_intelligence_not_enabled"
            case .modelNotReady: return "model_not_ready"
            @unknown default: return "unavailable_other"
            }
        @unknown default:
            return "unavailable_other"
        }
    }

    /// The model changes with the OS (26.0-26.3, 26.4, 27), so its version is the OS version.
    static var modelVersion: String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "ios-\(version.majorVersion).\(version.minorVersion)"
    }

    /// Instructions, prompt, schema and answer must fit (4,096 tokens on iOS 26; back-deployed).
    static var contextSize: Int {
        SystemLanguageModel.default.contextSize
    }

    /// Tokens of `text` (iOS 26.4 and later), or nil: the shared code then counts 1 character as
    /// 1 token.
    static func tokenCount(_ text: String) async -> Int? {
        if #available(iOS 26.4, *) {
            return try? await SystemLanguageModel.default.tokenCount(for: text)
        }
        return nil
    }

    /// The Kotlin `OutputSchema` JSON was not a schema this file can build.
    struct SchemaError: Error, CustomStringConvertible {
        let description: String
    }

    /// Builds the guided generation schema from Kotlin's `OutputSchema.toJson()`: objects of named
    /// properties, arrays with a maximum length, strings and integers.
    static func generationSchema(json: String) throws -> GenerationSchema {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw SchemaError(description: "not a JSON object") }
        return try GenerationSchema(root: dynamicSchema(root), dependencies: [])
    }

    static func dynamicSchema(_ node: [String: Any]) throws -> DynamicGenerationSchema {
        switch node["type"] as? String {
        case "string":
            return DynamicGenerationSchema(type: String.self)
        case "integer":
            return DynamicGenerationSchema(type: Int.self)
        case "array":
            guard let items = node["items"] as? [String: Any] else { throw SchemaError(description: "array without items") }
            return DynamicGenerationSchema(arrayOf: try dynamicSchema(items), maximumElements: node["maxItems"] as? Int)
        case "object":
            guard let name = node["name"] as? String, let properties = node["properties"] as? [[String: Any]] else {
                throw SchemaError(description: "object without name or properties")
            }
            return DynamicGenerationSchema(
                name: name,
                description: node["description"] as? String,
                properties: try properties.map { property in
                    guard let propertyName = property["name"] as? String, let schema = property["schema"] as? [String: Any] else {
                        throw SchemaError(description: "property without name or schema")
                    }
                    return DynamicGenerationSchema.Property(
                        name: propertyName,
                        description: schema["description"] as? String,
                        schema: try dynamicSchema(schema)
                    )
                }
            )
        default:
            throw SchemaError(description: "unknown type \(String(describing: node["type"]))")
        }
    }

    /// Why a generation failed, as a short code without user data (never the error's description).
    struct Failure: Error, Equatable {
        let code: String
    }

    /// One stateless generation in a new session: JSON shaped by `schemaJson`, or the failure.
    /// Stops when the calling task is cancelled.
    static func generate(instructions: String, prompt: String, schemaJson: String, maxOutputTokens: Int) async -> Result<String, Failure> {
        let schema: GenerationSchema
        do {
            schema = try generationSchema(json: schemaJson)
        } catch {
            return .failure(Failure(code: "invalid_schema"))
        }
        let session = LanguageModelSession(model: model(), tools: [], instructions: instructions)
        do {
            let response = try await session.respond(
                to: prompt,
                schema: schema,
                includeSchemaInPrompt: true,
                options: GenerationOptions(sampling: .greedy, maximumResponseTokens: maxOutputTokens)
            )
            return .success(response.content.jsonString)
        } catch {
            return .failure(Failure(code: errorCode(error)))
        }
    }

    /// The shared code's names for the framework's errors (Kotlin `IosLlmProvider.resultOf`).
    static func errorCode(_ error: Error) -> String {
        if error is CancellationError { return "cancelled" }
        if let error = error as? LanguageModelSession.GenerationError {
            switch error {
            case .exceededContextWindowSize: return "exceeded_context_window"
            case .guardrailViolation: return "guardrail_violation"
            case .refusal: return "refusal"
            case .rateLimited: return "rate_limited"
            case .concurrentRequests: return "concurrent_requests"
            case .assetsUnavailable: return "assets_unavailable"
            case .unsupportedLanguageOrLocale: return "unsupported_language"
            case .unsupportedGuide: return "unsupported_guide"
            case .decodingFailure: return "decoding_failure"
            @unknown default: return "generation_error"
            }
        }
        // Anything else (e.g. the OS's model assets missing): its domain and code, never its text.
        let error = error as NSError
        return "error_\(error.domain)_\(error.code)"
    }
}
