import Foundation
import FoundationModels
import XCTest

/// #196: Apple's on-device Foundation Models for hour summaries, without the Kotlin framework
/// (the test bundle compiles its own copy of `App/Llm/OnDeviceLanguageModel.swift`).
///
/// - The parts that need no model run everywhere: availability codes for every reason, the
///   guided-generation schema built from the JSON Kotlin sends (`shared/pipeline/testdata/summary/
///   hour-summary-schema.json`, checked against `SummaryPrompts.SCHEMA` by the Kotlin
///   `SummarySchemaFixtureTest`), and the error codes the shared code maps to its states.
/// - `testGeneratesASummaryOnTheModel` needs Apple Intelligence. GitHub's macOS runners are virtual
///   machines, where it is not available, so the test records the reason and skips; the device
///   checks are in #60.
///
/// Evidence: `LLM_*` lines in `build/swift-evidence/llm-*.txt` for the job summary.
final class LanguageModelTests: XCTestCase {
    private static let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    private static let schemaFile = repository.appending(path: "shared/pipeline/testdata/summary/hour-summary-schema.json")

    func testAvailabilityIsRecorded() async {
        let report = EvidenceReport(name: "llm-availability")
        let model = SystemLanguageModel.default
        let code = OnDeviceLanguageModel.availabilityCode()
        report.log("OS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        report.log("simulator: \(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "-")")
        report.log("LLM_AVAILABILITY code=\(code) availability=\(model.availability) supports_ja=\(model.supportsLocale(OnDeviceLanguageModel.japanese)) model_version=\(OnDeviceLanguageModel.modelVersion)")
        report.log("LLM_CONTEXT context_size=\(OnDeviceLanguageModel.contextSize)")
        let tokens = await OnDeviceLanguageModel.tokenCount("明日の会議は十時からです")
        report.log("LLM_TOKENS sample_tokens=\(tokens.map(String.init) ?? "unknown (before iOS 26.4 or unavailable)")")
        report.write()
        XCTAssertTrue(
            ["available", "device_not_eligible", "apple_intelligence_not_enabled", "model_not_ready", "language_not_supported", "unavailable_other"]
                .contains(code),
            code
        )
        XCTAssertGreaterThan(OnDeviceLanguageModel.contextSize, 0)
        XCTAssertTrue(OnDeviceLanguageModel.modelVersion.hasPrefix("ios-"))
    }

    func testEveryReasonHasTheSharedCode() {
        let cases: [(SystemLanguageModel.Availability, Bool, String)] = [
            (.available, true, "available"),
            (.available, false, "language_not_supported"),
            (.unavailable(.deviceNotEligible), true, "device_not_eligible"),
            (.unavailable(.appleIntelligenceNotEnabled), true, "apple_intelligence_not_enabled"),
            (.unavailable(.modelNotReady), true, "model_not_ready"),
        ]
        for (availability, japanese, code) in cases {
            XCTAssertEqual(OnDeviceLanguageModel.availabilityCode(availability, supportsJapanese: japanese), code)
        }
    }

    func testTheSchemaFromKotlinBuilds() throws {
        let report = EvidenceReport(name: "llm-schema")
        let json = try String(contentsOf: Self.schemaFile, encoding: .utf8)
        let schema = try OnDeviceLanguageModel.generationSchema(json: json)
        let encoded = String(decoding: try JSONEncoder().encode(schema), as: UTF8.self)
        report.log("LLM_SCHEMA built from \(Self.schemaFile.lastPathComponent): \(encoded)")
        report.write()
        for name in ["summary", "highlights", "title", "body", "lines"] {
            XCTAssertTrue(encoded.contains("\"\(name)\""), "\(name) missing from \(encoded)")
        }
        for broken in ["", "[]", #"{"type": "object"}"#, #"{"type": "array"}"#, #"{"type": "float"}"#,
                       #"{"type": "object", "name": "X", "properties": [{"name": "a"}]}"#] {
            XCTAssertThrowsError(try OnDeviceLanguageModel.generationSchema(json: broken), broken)
        }
    }

    func testErrorsHaveTheSharedCodes() {
        let context = LanguageModelSession.GenerationError.Context(debugDescription: "test")
        let cases: [(Error, String)] = [
            (LanguageModelSession.GenerationError.exceededContextWindowSize(context), "exceeded_context_window"),
            (LanguageModelSession.GenerationError.guardrailViolation(context), "guardrail_violation"),
            (LanguageModelSession.GenerationError.rateLimited(context), "rate_limited"),
            (LanguageModelSession.GenerationError.concurrentRequests(context), "concurrent_requests"),
            (LanguageModelSession.GenerationError.assetsUnavailable(context), "assets_unavailable"),
            (LanguageModelSession.GenerationError.unsupportedLanguageOrLocale(context), "unsupported_language"),
            (LanguageModelSession.GenerationError.decodingFailure(context), "decoding_failure"),
            (CancellationError(), "cancelled"),
            (CocoaError(.fileNoSuchFile), "error_CocoaError"),
        ]
        for (error, code) in cases {
            XCTAssertEqual(OnDeviceLanguageModel.errorCode(error), code, "\(error)")
        }
    }

    func testABrokenSchemaFailsWithoutCallingTheModel() async {
        let result = await OnDeviceLanguageModel.generate(instructions: "i", prompt: "p", schemaJson: "{", maxOutputTokens: 10)
        XCTAssertEqual(result, .failure(OnDeviceLanguageModel.Failure(code: "invalid_schema")))
    }

    func testGeneratesASummaryOnTheModel() async throws {
        let report = EvidenceReport(name: "llm-generation")
        let code = OnDeviceLanguageModel.availabilityCode()
        guard code == "available" else {
            throw report.skip("LLM_GENERATION skipped: the on-device model is not available here (\(code); GitHub's macOS runners are virtual machines without Apple Intelligence). Checked on a device (#60).")
        }
        let json = try String(contentsOf: Self.schemaFile, encoding: .utf8)
        let prompt = """
        [1] 明日の会議は十時からなので、資料は今日中に共有します
        [2]★ 予算の見積もりは先週より少し増えそうだと聞きました
        [3] 来週の水曜日に、もう一度みんなで集まって決めましょう
        """
        let started = Date()
        let result = await OnDeviceLanguageModel.generate(
            instructions: "次の会話を日本語で要約し、要点に根拠の行番号を付けてください。",
            prompt: prompt,
            schemaJson: json,
            maxOutputTokens: 500
        )
        let seconds = Date().timeIntervalSince(started)
        switch result {
        case .success(let answer):
            report.log(String(format: "LLM_GENERATION ok seconds=%.1f", seconds))
            report.log("LLM_ANSWER \(answer)")
            report.write()
            let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(answer.utf8)) as? [String: Any])
            XCTAssertNotNil(object["summary"] as? String)
            XCTAssertNotNil(object["highlights"] as? [Any])
        case .failure(let failure):
            report.log(String(format: "LLM_GENERATION failed code=%@ seconds=%.1f", failure.code, seconds))
            report.write()
            XCTFail("generation failed: \(failure.code)")
        }
    }
}
