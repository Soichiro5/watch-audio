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
///   machines without it: there the model is reported unavailable, or (seen on the iOS 26.5
///   simulator) available but without its assets, so the generation fails outside the model. The
///   test records the reason and skips in both cases; the device checks are in #60.
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
            (CocoaError(.fileNoSuchFile), "error_NSCocoaErrorDomain_4"),
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
            #if targetEnvironment(simulator)
            // GitHub's virtual macOS runners report the model as available, but its assets are not
            // there (the OS's model catalog has none), so the generation fails with an error that is
            // not one of the model's own. Record it and skip; a model error still fails the test.
            if failure.code == "assets_unavailable" || failure.code.hasPrefix("error_") {
                throw report.skip("LLM_GENERATION skipped: the simulator reports the model as available but cannot run it (\(failure.code); no Apple Intelligence assets on this runner). Checked on a device (#60).")
            }
            #endif
            report.write()
            XCTFail("generation failed: \(failure.code)")
        }
    }

    /// #197: answers the hour-summary evaluation's requests on the model, for the scorer on Linux
    /// (`summary-eval --provider replay=<dir>`, docs/research/on-device-llm.md §7.4). Reads
    /// `<dir>/requests.jsonl` and appends one line per request not answered yet to
    /// `<dir>/responses.jsonl`: `{"id", "ok": <answer JSON>}` or `{"id", "error": <code>}`, with
    /// `latencyMs` and `model`. Runs only when `SUMMARY_EVAL_DIR` (xcodebuild:
    /// `TEST_RUNNER_SUMMARY_EVAL_DIR`) names the directory: on a Mac with Apple Intelligence, the
    /// simulator uses the Mac's model. The evidence lists ids and outcomes only, never the text.
    func testAnswersSummaryEvaluationRequests() async throws {
        let report = EvidenceReport(name: "llm-summary-eval")
        guard let path = ProcessInfo.processInfo.environment["SUMMARY_EVAL_DIR"], !path.isEmpty else {
            throw report.skip("SUMMARY_EVAL_DIR not set (the summary evaluation runs on a Mac with Apple Intelligence, #197)")
        }
        let code = OnDeviceLanguageModel.availabilityCode()
        guard code == "available" else {
            throw report.skip("LLM_SUMMARY_EVAL skipped: the on-device model is not available here (\(code))")
        }
        let directory = URL(fileURLWithPath: path)
        let requests = try String(contentsOf: directory.appending(path: "requests.jsonl"), encoding: .utf8)
        let responses = directory.appending(path: "responses.jsonl")
        var answered = Set<String>()
        if let existing = try? String(contentsOf: responses, encoding: .utf8) {
            for line in existing.split(separator: "\n") {
                if let object = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any], let id = object["id"] as? String {
                    answered.insert(id)
                }
            }
        }
        report.log("LLM_SUMMARY_EVAL model_version=\(OnDeviceLanguageModel.modelVersion) context_size=\(OnDeviceLanguageModel.contextSize) already_answered=\(answered.count)")
        var count = 0
        for line in requests.split(separator: "\n") where !line.isEmpty {
            let request = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any])
            let id = try XCTUnwrap(request["id"] as? String)
            guard !answered.contains(id) else { continue }
            let instructions = try XCTUnwrap(request["instructions"] as? String)
            let prompt = try XCTUnwrap(request["prompt"] as? String)
            let schema = try XCTUnwrap(request["schema"] as? String)
            let maxOutputTokens = try XCTUnwrap(request["maxOutputTokens"] as? Int)
            let started = Date()
            let result = await OnDeviceLanguageModel.generate(
                instructions: instructions, prompt: prompt, schemaJson: schema, maxOutputTokens: maxOutputTokens
            )
            let latencyMs = Int(Date().timeIntervalSince(started) * 1000)
            var response: [String: Any] = ["id": id, "latencyMs": latencyMs, "model": OnDeviceLanguageModel.modelVersion]
            switch result {
            case .success(let json): response["ok"] = json
            case .failure(let failure): response["error"] = failure.code
            }
            // One line at a time, so that an interrupted run keeps what it answered.
            var data = try JSONSerialization.data(withJSONObject: response, options: [.sortedKeys])
            data.append(contentsOf: Array("\n".utf8))
            if let handle = try? FileHandle(forWritingTo: responses) {
                _ = try handle.seekToEnd()
                try handle.write(contentsOf: data)
                try handle.close()
            } else {
                try data.write(to: responses)
            }
            answered.insert(id)
            count += 1
            let outcome: String
            switch result {
            case .success: outcome = "ok"
            case .failure(let failure): outcome = failure.code
            }
            report.log("LLM_SUMMARY_EVAL id=\(id) result=\(outcome) ms=\(latencyMs)")
        }
        report.log("LLM_SUMMARY_EVAL answered=\(count)")
        report.write()
    }
}
