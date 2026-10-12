import AVFoundation
import ComposeApp
import Foundation
import Speech
import XCTest

/// #170: English next to Japanese through the app's whole transcription path on the iOS simulator:
/// the Swift transcriber (`SpeechAnalyzerTranscriber`), the shared routing (`SegmentRouting` in
/// `IosTranscriberAdapter`) and the shared pipeline storing into SQLite, reached through
/// `IosTranscriptionCheck` in the app's Kotlin framework (a temporary database, not the app's).
///
/// The audio is prepared by CI (`.github/scripts/make-mixed-speech.sh`: `say` with a Japanese and an
/// English voice, in the watch's format) into `build/mixed-speech/` (`MIXED_SPEECH_DIR` overrides
/// it). SpeechTranscriber is used where the simulator has it; GitHub's simulators do not
/// (`isAvailable` is false), so the check falls back to DictationTranscriber, the OS's older
/// on-device dictation models, which run the same routing. What each pass wrote, with confidences
/// and times, goes to `build/swift-evidence/speech-english*.txt` for the job summary. Without
/// either transcriber the tests skip with the reason; the device check is #60.
final class EnglishTranscriptionTests: XCTestCase {
    func testJapaneseEnglishJapaneseIsStoredAsJaEnJa() async throws {
        let report = EvidenceReport(name: "speech-english-mixed")
        let engine = try await Self.engine(report)
        let audio = try Self.audio("mixed", report)
        let sentences = try Self.sentences(report)

        // What each model makes of the whole recording (evidence for the routing's thresholds).
        for language in ["ja", "en"] {
            try await Self.logPass(audio, language: language, engine: engine, report)
        }

        let clock = ContinuousClock()
        let started = clock.now
        let stored = try await Self.store(audio, engine: engine, english: true)
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
        XCTAssertEqual(engines, ["apple-\(engine.rawValue)transcriber-ja+apple-\(engine.rawValue)transcriber-en"])
        report.log("RESULT: stored as \(order.joined(separator: ", ")) (\(engine.rawValue))")
        report.write()
    }

    func testJapaneseOnlyAudioIsTheSameWithEnglishOnAndOff() async throws {
        let report = EvidenceReport(name: "speech-english-japanese-only")
        let engine = try await Self.engine(report)
        let audio = try Self.audio("japanese", report)

        let clock = ContinuousClock()
        var started = clock.now
        let off = try await Self.store(audio, engine: engine, english: false)
        report.log("English off, \(clock.now - started):")
        Self.log(off, report)
        started = clock.now
        let on = try await Self.store(audio, engine: engine, english: true)
        report.log("English on, \(clock.now - started):")
        Self.log(on, report)

        XCTAssertFalse(off.isEmpty, "nothing stored")
        XCTAssertEqual(on.map(\.startMs), off.map(\.startMs))
        XCTAssertEqual(on.map(\.endMs), off.map(\.endMs))
        XCTAssertEqual(on.map(\.text), off.map(\.text))
        XCTAssertEqual(Set(on.compactMap(\.lang)), ["ja"])
        report.log("RESULT: \(on.map(\.text) == off.map(\.text) ? "same" : "different") with English on and off (\(engine.rawValue))")
        report.write()
    }

    // MARK: - Helpers

    private struct Sentence {
        let lang: String
        let startMs: Int64
        let endMs: Int64
        let text: String
    }

    private func overlap(_ sentence: Sentence, _ segment: Stored) -> Int64 {
        min(sentence.endMs, segment.endMs) - max(sentence.startMs, segment.startMs)
    }

    /// SpeechTranscriber where the simulator has it, else DictationTranscriber; skips without both
    /// Japanese and English.
    private static func engine(_ report: EvidenceReport) async throws -> SpeechTranscription.Engine {
        report.log("OS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        report.log("simulator: \(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "-")")
        report.log("SpeechTranscriber.isAvailable: \(SpeechTranscriber.isAvailable)")
        let japanese = Locale(identifier: "ja_JP")
        let english = Locale(identifier: "en_US")
        if SpeechTranscriber.isAvailable,
           await SpeechTranscriber.supportedLocale(equivalentTo: japanese) != nil,
           await SpeechTranscriber.supportedLocale(equivalentTo: english) != nil {
            report.log("engine: SpeechTranscriber")
            return .speech
        }
        let dictation = await DictationTranscriber.supportedLocales
        report.log("DictationTranscriber supported locales: \(dictation.count)")
        guard await DictationTranscriber.supportedLocale(equivalentTo: japanese) != nil,
              await DictationTranscriber.supportedLocale(equivalentTo: english) != nil
        else {
            throw report.skip("neither SpeechTranscriber nor DictationTranscriber has ja_JP and en_US on this simulator")
        }
        for locale in [japanese, english] {
            do {
                _ = try await SpeechTranscription.makeDictationTranscriber(locale: locale)
            } catch {
                throw report.skip("the DictationTranscriber model for \(locale.identifier) could not be installed: \(SpeechTranscription.describe(error))")
            }
        }
        report.log("engine: DictationTranscriber (SpeechTranscriber is unavailable here)")
        await probeFormats(report)
        return .dictation
    }

    /// Which audio formats DictationTranscriber (ja_JP) accepts on this simulator: its best
    /// available format, and a 3 s analysis of the test audio in each candidate format.
    private static func probeFormats(_ report: EvidenceReport) async {
        let url = directory.appending(path: "japanese.m4a")
        guard let file = try? AVAudioFile(forReading: url) else { return }
        report.log("file format: \(file.fileFormat), processing format: \(file.processingFormat)")
        guard let module = try? await SpeechTranscription.makeDictationTranscriber(locale: Locale(identifier: "ja_JP")) else { return }
        let best = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module])
        report.log("bestAvailableAudioFormat: \(best.map { "\($0)" } ?? "nil")")
        var candidates: [(String, AVAudioFormat?)] = [("as read", nil)]
        if let best { candidates.append(("best", best)) }
        candidates.append(("int16 16k", AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true)))
        candidates.append(("float32 16k", AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)))
        candidates.append(("int16 44.1k", AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 44_100, channels: 1, interleaved: true)))
        for (name, format) in candidates {
            let converted = FileManager.default.temporaryDirectory.appending(path: "probe-\(UUID().uuidString).caf")
            defer { try? FileManager.default.removeItem(at: converted) }
            do {
                let input: AVAudioFile
                if let format {
                    try SpeechTranscription.convert(try AVAudioFile(forReading: url), to: format, at: converted)
                    input = try AVAudioFile(forReading: converted, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
                } else {
                    input = try AVAudioFile(forReading: url)
                }
                let probe = try await SpeechTranscription.makeDictationTranscriber(locale: Locale(identifier: "ja_JP"))
                let analyzer = SpeechAnalyzer(modules: [probe])
                let collector = Task { () throws -> Int in
                    var count = 0
                    for try await result in probe.results where result.isFinal { count += result.text.characters.count }
                    return count
                }
                if let last = try await analyzer.analyzeSequence(from: input) {
                    try await analyzer.finalizeAndFinish(through: last)
                } else {
                    await analyzer.cancelAndFinishNow()
                }
                let characters = try await collector.value
                report.log("format probe \(name) (\(input.processingFormat)): ok, \(characters) characters")
            } catch {
                report.log("format probe \(name): \(SpeechTranscription.describe(error))")
            }
        }
    }

    private static var directory: URL {
        if let dir = ProcessInfo.processInfo.environment["MIXED_SPEECH_DIR"] { return URL(fileURLWithPath: dir) }
        // apps/ios/Tests/SpeechTests/<this file> -> repository root
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "build/mixed-speech")
    }

    private struct Audio {
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
        let text = try String(contentsOf: directory.appending(path: "mixed.txt"), encoding: .utf8)
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
    private struct Stored: Sendable {
        let startMs: Int64
        let endMs: Int64
        let text: String
        let lang: String?
        let engine: String
    }

    private static func store(_ audio: Audio, engine: SpeechTranscription.Engine, english: Bool) async throws -> [Stored] {
        let transcriber = SpeechAnalyzerTranscriber(engine: engine)
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
