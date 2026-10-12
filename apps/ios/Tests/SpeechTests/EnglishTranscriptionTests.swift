import AVFoundation
import ComposeApp
import Foundation
import Speech
import XCTest

/// #170: English next to Japanese through the app's transcription path in the app process: a Swift
/// `IosSpeechTranscriber`, the shared routing (`SegmentRouting` in `IosTranscriberAdapter`) and the
/// shared pipeline storing into SQLite, reached through `IosTranscriptionCheck` in the app's Kotlin
/// framework (a temporary database, not the app's).
///
/// The audio is prepared by CI (`.github/scripts/make-mixed-speech.sh`: `say` with a Japanese and an
/// English voice, in the watch's format) into `build/mixed-speech/` (`MIXED_SPEECH_DIR` overrides
/// it), with the time and language of each sentence in `mixed.txt`.
///
/// - The `testSpeech…` cases use the real recognizer: `SpeechAnalyzerTranscriber` with
///   SpeechTranscriber, or DictationTranscriber where only that runs. GitHub's simulators have
///   neither (SpeechTranscriber is unavailable; DictationTranscriber lists its locales but offers no
///   audio format, and analysis fails with "Audio format is not supported"), so these skip there
///   with the reason, and the device check is #60.
/// - The `testScripted…` cases replace only the recognizer with `ScriptedTranscriber`, which answers
///   from `mixed.txt` the way the models behave (the Japanese model writes next to nothing for
///   English; the English model writes low-confidence words for Japanese), and run everything else
///   for real, including the Swift ↔ Kotlin bridge.
///
/// What each pass wrote goes to `build/swift-evidence/speech-english*.txt` for the job summary.
final class EnglishTranscriptionTests: XCTestCase {
    func testSpeechJapaneseEnglishJapaneseIsStoredAsJaEnJa() async throws {
        let report = EvidenceReport(name: "speech-english-mixed")
        let engine = try await Self.engine(report)
        let audio = try Self.audio("mixed", report)
        let sentences = try Self.sentences(report)
        // What each model makes of the whole recording (evidence for the routing's thresholds).
        for language in ["ja", "en"] {
            try await Self.logPass(audio, language: language, engine: engine, report)
        }
        let transcriber = SpeechAnalyzerTranscriber(engine: engine)
        try await assertJapaneseEnglishJapanese(audio, sentences, transcriber, report)
        report.write()
    }

    func testSpeechJapaneseOnlyAudioIsTheSameWithEnglishOnAndOff() async throws {
        let report = EvidenceReport(name: "speech-english-japanese-only")
        let engine = try await Self.engine(report)
        let audio = try Self.audio("japanese", report)
        try await assertSameWithEnglishOnAndOff(audio, SpeechAnalyzerTranscriber(engine: engine), report)
        report.write()
    }

    func testScriptedJapaneseEnglishJapaneseIsStoredAsJaEnJa() async throws {
        let report = EvidenceReport(name: "speech-english-scripted-mixed")
        let audio = try Self.audio("mixed", report)
        let sentences = try Self.sentences(report)
        let transcriber = ScriptedTranscriber(sentences: sentences)
        try await assertJapaneseEnglishJapanese(audio, sentences, transcriber, report)
        report.log("English pass input: \(transcriber.englishRequests.map { $0.map { "\($0.fromMs)–\($0.toMs) ms" }.joined(separator: ", ") })")
        XCTAssertEqual(transcriber.englishRequests.count, 1, "one English pass per chunk")
        report.write()
    }

    func testScriptedJapaneseOnlyAudioIsTheSameWithEnglishOnAndOff() async throws {
        let report = EvidenceReport(name: "speech-english-scripted-japanese-only")
        let audio = try Self.audio("mixed", report)
        // Only the Japanese sentences of mixed.m4a, spoken in a Japanese-only recording.
        let japanese = try Self.sentences(report).filter { $0.lang == "ja" }
        let transcriber = ScriptedTranscriber(sentences: japanese)
        try await assertSameWithEnglishOnAndOff(audio, transcriber, report)
        report.log("English pass input: \(transcriber.englishRequests.map { $0.map { "\($0.fromMs)–\($0.toMs) ms" }.joined(separator: ", ") })")
        report.write()
    }

    /// The conversion to a module's audio format (`SpeechTranscription.analyzerInput`) keeps the
    /// recording's length, whatever the target's sample format and rate.
    func testConvertingTheAudioKeepsItsLength() throws {
        let report = EvidenceReport(name: "speech-english-convert")
        let audio = try Self.audio("mixed", report)
        let targets = [
            AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true),
            AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false),
        ].compactMap { $0 }
        for target in targets {
            let url = FileManager.default.temporaryDirectory.appending(path: "convert-\(UUID().uuidString).caf")
            defer { try? FileManager.default.removeItem(at: url) }
            try SpeechTranscription.convert(try AVAudioFile(forReading: audio.url), to: target, at: url)
            let converted = try AVAudioFile(forReading: url, commonFormat: target.commonFormat, interleaved: target.isInterleaved)
            let durationMs = Int64(Double(converted.length) / converted.processingFormat.sampleRate * 1000)
            report.log("converted to \(converted.processingFormat): \(durationMs) ms (source \(audio.durationMs) ms)")
            XCTAssertEqual(converted.processingFormat, target)
            XCTAssertEqual(Double(durationMs), Double(audio.durationMs), accuracy: 50)
        }
        report.write()
    }

    /// The chunk transcribed with English on is stored as Japanese, English, Japanese, each segment
    /// in the language of the sentence it lies in, with the English sentence's words.
    private func assertJapaneseEnglishJapanese(
        _ audio: Audio,
        _ sentences: [Sentence],
        _ transcriber: IosSpeechTranscriber & Sendable,
        _ report: EvidenceReport
    ) async throws {
        let clock = ContinuousClock()
        let started = clock.now
        let stored = try await Self.store(audio, transcriber: transcriber, english: true)
        report.log("stored with English on in \(clock.now - started):")
        Self.log(stored, report)

        XCTAssertFalse(stored.isEmpty, "nothing stored")
        let order = stored.compactMap(\.lang).reduce(into: [String]()) { runs, lang in
            if runs.last != lang { runs.append(lang) }
        }
        report.log("language order: \(order.joined(separator: ", "))")
        XCTAssertEqual(order, ["ja", "en", "ja"], "languages of the stored segments in order")
        for segment in stored {
            let spoken = sentences.max { overlap($0, segment) < overlap($1, segment) }
            XCTAssertEqual(segment.lang, spoken?.lang, "segment \(segment.startMs)–\(segment.endMs) \"\(segment.text)\" lies in a \(spoken?.lang ?? "-") sentence")
        }
        let englishText = stored.filter { $0.lang == "en" }.map(\.text).joined(separator: " ")
        if let expected = sentences.first(where: { $0.lang == "en" })?.text {
            let recall = Self.wordRecall(expected: expected, recognized: englishText)
            report.log(String(format: "English word recall: %.2f", recall))
            XCTAssertGreaterThanOrEqual(recall, 0.5, "recognized \"\(englishText)\" for \"\(expected)\"")
        }
        let engines = Set(stored.map(\.engine))
        report.log("engines: \(engines.sorted())")
        XCTAssertEqual(engines, [transcriber.engineId(language: "ja") + "+" + transcriber.engineId(language: "en")])
        report.log("RESULT: stored as \(order.joined(separator: ", "))")
    }

    /// The chunk is stored the same (times, text, language `ja`) with English off and on.
    private func assertSameWithEnglishOnAndOff(_ audio: Audio, _ transcriber: IosSpeechTranscriber & Sendable, _ report: EvidenceReport) async throws {
        let clock = ContinuousClock()
        var started = clock.now
        let off = try await Self.store(audio, transcriber: transcriber, english: false)
        report.log("English off, \(clock.now - started):")
        Self.log(off, report)
        started = clock.now
        let on = try await Self.store(audio, transcriber: transcriber, english: true)
        report.log("English on, \(clock.now - started):")
        Self.log(on, report)

        XCTAssertFalse(off.isEmpty, "nothing stored")
        XCTAssertEqual(on.map(\.startMs), off.map(\.startMs))
        XCTAssertEqual(on.map(\.endMs), off.map(\.endMs))
        XCTAssertEqual(on.map(\.text), off.map(\.text))
        XCTAssertEqual(Set(on.compactMap(\.lang)), ["ja"])
        report.log("RESULT: \(on.map(\.text) == off.map(\.text) ? "same" : "different") with English on and off")
    }

    // MARK: - Helpers

    struct Sentence: Sendable {
        let lang: String
        let startMs: Int64
        let endMs: Int64
        let text: String
    }

    private func overlap(_ sentence: Sentence, _ segment: Stored) -> Int64 {
        min(sentence.endMs, segment.endMs) - max(sentence.startMs, segment.startMs)
    }

    /// SpeechTranscriber where the simulator runs it, else DictationTranscriber; skips without
    /// either for both Japanese and English.
    private static func engine(_ report: EvidenceReport) async throws -> SpeechTranscription.Engine {
        report.log("OS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        report.log("simulator: \(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "-")")
        report.log("SpeechTranscriber.isAvailable: \(SpeechTranscriber.isAvailable)")
        let locales = [Locale(identifier: "ja_JP"), Locale(identifier: "en_US")]
        if SpeechTranscriber.isAvailable {
            var supported = true
            for locale in locales {
                if await SpeechTranscriber.supportedLocale(equivalentTo: locale) == nil { supported = false }
            }
            if supported {
                report.log("engine: SpeechTranscriber")
                return .speech
            }
        }
        report.log("DictationTranscriber supported locales: \(await DictationTranscriber.supportedLocales.count)")
        for locale in locales {
            guard await DictationTranscriber.supportedLocale(equivalentTo: locale) != nil else {
                throw report.skip("neither SpeechTranscriber nor DictationTranscriber supports \(locale.identifier) on this simulator")
            }
            let transcriber: DictationTranscriber
            do {
                transcriber = try await SpeechTranscription.makeDictationTranscriber(locale: locale)
            } catch {
                throw report.skip("the DictationTranscriber model for \(locale.identifier) could not be installed: \(SpeechTranscription.describe(error))")
            }
            // A module whose models cannot run here offers no audio format.
            guard await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) != nil else {
                throw report.skip(
                    "SpeechTranscriber is unavailable and DictationTranscriber (\(locale.identifier)) offers no audio format "
                        + "on this simulator (analysis fails with \"Audio format is not supported\"); the real recognizers are checked on a device (#60)"
                )
            }
        }
        report.log("engine: DictationTranscriber (SpeechTranscriber is unavailable here)")
        return .dictation
    }

    private static var directory: URL {
        if let dir = ProcessInfo.processInfo.environment["MIXED_SPEECH_DIR"] { return URL(fileURLWithPath: dir) }
        // apps/ios/Tests/SpeechTests/<this file> -> repository root
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "build/mixed-speech")
    }

    struct Audio {
        let url: URL
        let durationMs: Int64
    }

    private static func audio(_ name: String, _ report: EvidenceReport) throws -> Audio {
        let url = directory.appending(path: "\(name).m4a")
        guard FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) else {
            throw report.skip("no test audio at \(url.path(percentEncoded: false)) (prepared by CI)")
        }
        let file = try AVAudioFile(forReading: url)
        let durationMs = Int64(Double(file.length) / file.fileFormat.sampleRate * 1000)
        report.log("audio: \(name).m4a, \(Int(file.fileFormat.sampleRate)) Hz, \(file.fileFormat.channelCount) ch, \(durationMs) ms")
        return Audio(url: url, durationMs: durationMs)
    }

    /// The sentences of mixed.m4a: "<lang>\t<start ms>\t<end ms>\t<text>" per line.
    private static func sentences(_ report: EvidenceReport) throws -> [Sentence] {
        let url = directory.appending(path: "mixed.txt")
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            throw report.skip("no sentence list at \(url.path(percentEncoded: false)) (prepared by CI)")
        }
        let sentences = text.split(separator: "\n").compactMap { line -> Sentence? in
            let fields = line.split(separator: "\t", maxSplits: 3).map(String.init)
            guard fields.count == 4, let start = Int64(fields[1]), let end = Int64(fields[2]) else { return nil }
            return Sentence(lang: fields[0], startMs: start, endMs: end, text: fields[3])
        }
        for sentence in sentences {
            report.log("spoken: [\(sentence.startMs)–\(sentence.endMs) ms] \(sentence.lang) \(sentence.text)")
        }
        return sentences
    }

    private static func logPass(_ audio: Audio, language: String, engine: SpeechTranscription.Engine, _ report: EvidenceReport) async throws {
        let clock = ContinuousClock()
        let started = clock.now
        let segments = try await SpeechTranscription.transcribe(
            [SpeechTranscription.AudioRange(url: audio.url, fromMs: 0, toMs: audio.durationMs)],
            locale: SpeechAnalyzerTranscriber.locale(for: language),
            engine: engine
        )
        report.log("\(language) pass over the whole recording in \(clock.now - started):")
        for segment in segments {
            let confidence = segment.confidence.map { String(format: "%.2f", $0) } ?? "-"
            report.log("  [\(segment.startMs)–\(segment.endMs) ms] conf \(confidence) \(segment.text)")
        }
    }

    /// A segment stored by `IosTranscriptionCheck`, times in ms from the start of the chunk.
    struct Stored: Sendable {
        let startMs: Int64
        let endMs: Int64
        let text: String
        let lang: String?
        let engine: String
    }

    private static func store(_ audio: Audio, transcriber: IosSpeechTranscriber & Sendable, english: Bool) async throws -> [Stored] {
        let path = audio.url.path(percentEncoded: false)
        let result: Result<[Stored], CheckError> = await withCheckedContinuation { continuation in
            IosTranscriptionCheck.shared.transcribe(
                transcriber: transcriber,
                audioPath: path,
                durationMs: audio.durationMs,
                english: english
            ) { segments, error in
                if let segments {
                    let stored = segments.map { Stored(startMs: $0.startMs, endMs: $0.endMs, text: $0.text, lang: $0.lang, engine: $0.engine) }
                    continuation.resume(returning: .success(stored))
                } else {
                    continuation.resume(returning: .failure(CheckError(message: error ?? "failed")))
                }
            }
        }
        return try result.get()
    }

    private struct CheckError: LocalizedError, Sendable {
        let message: String
        var errorDescription: String? { message }
    }

    private static func log(_ segments: [Stored], _ report: EvidenceReport) {
        for segment in segments {
            report.log("  [\(segment.startMs)–\(segment.endMs) ms] \(segment.lang ?? "-") \(segment.text)")
        }
    }

    /// Share of the expected words (lower case, letters and digits only) found in the recognized
    /// text, counting repeats.
    static func wordRecall(expected: String, recognized: String) -> Double {
        func words(_ text: String) -> [String] {
            text.lowercased().split { !$0.isLetter && !$0.isNumber && $0 != "'" }.map(String.init)
        }
        let wanted = words(expected)
        guard !wanted.isEmpty else { return 1 }
        var available: [String: Int] = [:]
        for word in words(recognized) { available[word, default: 0] += 1 }
        var found = 0
        for word in wanted where (available[word] ?? 0) > 0 {
            available[word]! -= 1
            found += 1
        }
        return Double(found) / Double(wanted.count)
    }
}

/// Stands in for the speech models (not the bridge, routing or pipeline) where the simulator has
/// none: answers each pass from the known sentences of the recording, the way the models behave.
/// The Japanese pass writes Japanese sentences as they are and a lone 「はい」 for an English one
/// (Android's Japanese model does that, english-asr.md §1); the English pass writes English
/// sentences as they are and low-confidence English words for Japanese. Parts are mapped to the
/// recording by their times (the silence between stretches is a range past the end of a file).
final class ScriptedTranscriber: NSObject, IosSpeechTranscriber, @unchecked Sendable {
    private let sentences: [EnglishTranscriptionTests.Sentence]
    private let lock = NSLock()
    /// Guarded by `lock`.
    private var englishParts: [[IosAudioPart]] = []

    init(sentences: [EnglishTranscriptionTests.Sentence]) {
        self.sentences = sentences
        super.init()
    }

    /// The parts of each English pass.
    var englishRequests: [[IosAudioPart]] {
        lock.lock()
        defer { lock.unlock() }
        return englishParts
    }

    var language: String { "ja" }

    func engineId(language: String) -> String { "scripted-\(language)" }

    func prepareModel(language: String, callback: @escaping (String?) -> Void) { callback(nil) }

    func releaseModel(language: String, callback: @escaping () -> Void) { callback() }

    func transcribe(parts: [IosAudioPart], language: String, callback: @escaping ([IosTimedText]?, String?) -> Void) {
        if language == "en" {
            lock.lock()
            englishParts.append(parts)
            lock.unlock()
        }
        var segments: [IosTimedText] = []
        var offset: Int64 = 0
        for part in parts {
            for sentence in sentences {
                let from = max(sentence.startMs, part.fromMs)
                let to = min(sentence.endMs, part.toMs)
                guard to > from else { continue }
                let startMs = offset + from - part.fromMs
                let endMs = offset + to - part.fromMs
                switch (language, sentence.lang) {
                case ("ja", "ja"), ("en", "en"):
                    segments.append(IosTimedText(startMs: startMs, endMs: endMs, text: sentence.text, confidence: 0.9))
                case ("ja", _):
                    segments.append(IosTimedText(startMs: startMs, endMs: endMs, text: "はい", confidence: 0.4))
                default:
                    segments.append(IosTimedText(startMs: startMs, endMs: endMs, text: "Oh how are you", confidence: 0.3))
                }
            }
            offset += part.toMs - part.fromMs
        }
        callback(segments.sorted { $0.startMs < $1.startMs }, nil)
    }

    func cancel() {}
}
