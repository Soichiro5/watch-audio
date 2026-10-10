import AVFoundation
import Foundation
import Speech
import XCTest

/// #21: on-device Japanese transcription with SpeechAnalyzer / SpeechTranscriber (`ja_JP`) on the
/// iOS simulator, for a chunk in the watch's format (16 kHz mono AAC) whose content is known.
///
/// The audio is prepared by CI (`say` with a Japanese voice, converted with `afconvert`) into
/// `build/ja-speech/speech.m4a` with the spoken text in `expected.txt`; `SPEECH_AUDIO` (set as
/// `TEST_RUNNER_SPEECH_AUDIO` for xcodebuild) overrides the path. If the simulator cannot run the
/// Speech framework's transcriber (not available, no Japanese model, model cannot be installed)
/// the tests are skipped with the reason, which also goes to `build/swift-evidence/` for the job
/// summary: that case is verified on a device instead (#60).
final class SpeechTranscriptionTests: XCTestCase {
    private static let japanese = Locale(identifier: "ja_JP")

    func testTranscribesAJapaneseChunkWithTimes() async throws {
        let report = EvidenceReport(name: "speech-transcription")
        let (audio, durationMs) = try await prepare(report)

        let segments = try await transcribe(
            [SpeechTranscription.AudioRange(url: audio.url, fromMs: 0, toMs: durationMs)],
            report
        )
        report.log("segments: \(segments.count)")
        for segment in segments {
            report.log("  [\(segment.startMs)–\(segment.endMs) ms] \(segment.text)")
        }
        XCTAssertFalse(segments.isEmpty, "no text recognized")
        assertTimes(segments, within: durationMs)

        if let expected = audio.expectedText {
            let recognized = segments.map(\.text).joined()
            let recall = Self.characterRecall(expected: expected, recognized: recognized)
            report.log("expected: \(expected)")
            report.log(String(format: "character recall: %.2f", recall))
            XCTAssertGreaterThanOrEqual(recall, 0.5, "recognized \"\(recognized)\" for \"\(expected)\"")
        }
        report.log("RESULT: transcribed on the simulator")
        report.write()
    }

    /// A chunk joined after 3 s of silence (the boundary path of the pipeline): the times follow
    /// the joined audio, so the shared code can map them back to absolute times.
    func testTimesFollowTheJoinedAudio() async throws {
        let report = EvidenceReport(name: "speech-transcription-joined")
        let (audio, durationMs) = try await prepare(report)
        let leadMs: Int64 = 3_000
        // A range past the end of the file is padded with silence.
        let silence = SpeechTranscription.AudioRange(url: audio.url, fromMs: durationMs + 10_000, toMs: durationMs + 10_000 + leadMs)
        let speech = SpeechTranscription.AudioRange(url: audio.url, fromMs: 0, toMs: durationMs)

        let segments = try await transcribe([silence, speech], report)
        for segment in segments {
            report.log("  [\(segment.startMs)–\(segment.endMs) ms] \(segment.text)")
        }
        let first = try XCTUnwrap(segments.first, "no text recognized")
        XCTAssertGreaterThanOrEqual(first.startMs, leadMs - 500, "speech starts after the silent lead-in")
        assertTimes(segments, within: leadMs + durationMs)
        report.log("RESULT: joined audio transcribed, first segment at \(first.startMs) ms (speech starts at \(leadMs) ms)")
        report.write()
    }

    // MARK: - Helpers

    private struct SpeechAudio {
        let url: URL
        let expectedText: String?
    }

    /// Finds the audio, checks its format and that the transcriber can run here; skips otherwise.
    private func prepare(_ report: EvidenceReport) async throws -> (SpeechAudio, Int64) {
        report.log("OS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        report.log("simulator: \(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "-")")

        guard let audio = Self.locateAudio() else {
            throw report.skip("no Japanese test audio at \(Self.defaultAudioURL.path(percentEncoded: false)) (prepared by CI)")
        }
        let file = try AVAudioFile(forReading: audio.url)
        let format = file.fileFormat
        let durationMs = Int64(Double(file.length) / format.sampleRate * 1000)
        report.log("audio: \(audio.url.lastPathComponent), \(Int(format.sampleRate)) Hz, \(format.channelCount) ch, "
            + "format id \(format.streamDescription.pointee.mFormatID), \(durationMs) ms")
        XCTAssertEqual(format.sampleRate, 16_000, "same sample rate as the watch's chunks")
        XCTAssertEqual(format.channelCount, 1)
        XCTAssertEqual(format.streamDescription.pointee.mFormatID, kAudioFormatMPEG4AAC, "AAC like the watch's chunks")

        report.log("SpeechTranscriber.isAvailable: \(SpeechTranscriber.isAvailable)")
        report.log("speech recognition authorization: \(Self.authorizationDescription)")
        guard SpeechTranscriber.isAvailable else {
            throw report.skip("SpeechTranscriber.isAvailable is false on this simulator")
        }
        let supported = await SpeechTranscriber.supportedLocales
        let installed = await SpeechTranscriber.installedLocales
        report.log("supported locales: \(supported.count), Japanese: \(supported.filter { $0.identifier.hasPrefix("ja") }.map(\.identifier))")
        report.log("installed locales: \(installed.map(\.identifier))")
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Self.japanese) else {
            throw report.skip("SpeechTranscriber does not support ja_JP on this simulator (supported locales: \(supported.count))")
        }
        let probe = SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [], attributeOptions: [.audioTimeRange])
        let statusBefore = await AssetInventory.status(forModules: [probe])
        report.log("asset status before install: \(statusBefore)")

        let clock = ContinuousClock()
        let started = clock.now
        do {
            _ = try await SpeechTranscription.makeTranscriber(locale: Self.japanese)
        } catch {
            throw report.skip("the ja_JP model could not be installed: \(SpeechTranscription.describe(error))")
        }
        let statusAfter = await AssetInventory.status(forModules: [probe])
        report.log("model ready after \(clock.now - started); asset status: \(statusAfter)")
        return (audio, durationMs)
    }

    /// Errors of the Speech framework itself (e.g. analysis not supported on the simulator) skip
    /// with the error logged; errors of our own audio handling fail the test.
    private func transcribe(_ ranges: [SpeechTranscription.AudioRange], _ report: EvidenceReport) async throws -> [SpeechTranscription.Segment] {
        let clock = ContinuousClock()
        let started = clock.now
        do {
            let segments = try await SpeechTranscription.transcribe(ranges, locale: Self.japanese)
            report.log("transcribed in \(clock.now - started)")
            return segments
        } catch let error as SpeechTranscription.TranscriberError {
            report.log("FAILED: \(SpeechTranscription.describe(error))")
            report.write()
            throw error
        } catch {
            throw report.skip("the Speech framework failed to transcribe on this simulator: \(SpeechTranscription.describe(error))")
        }
    }

    private static var authorizationDescription: String {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: "authorized"
        case .denied: "denied"
        case .restricted: "restricted"
        case .notDetermined: "notDetermined"
        @unknown default: "unknown"
        }
    }

    private func assertTimes(_ segments: [SpeechTranscription.Segment], within totalMs: Int64, line: UInt = #line) {
        var previousStart: Int64 = -1
        for segment in segments {
            XCTAssertGreaterThanOrEqual(segment.startMs, 0, line: line)
            XCTAssertLessThan(segment.startMs, segment.endMs, "\(segment)", line: line)
            XCTAssertLessThanOrEqual(segment.endMs, totalMs + 500, "\(segment)", line: line)
            XCTAssertGreaterThanOrEqual(segment.startMs, previousStart, "in order", line: line)
            previousStart = segment.startMs
        }
    }

    private static var repositoryRoot: URL {
        // iosApp/SpeechTests/<this file>
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    private static var defaultAudioURL: URL {
        repositoryRoot.appending(path: "build/ja-speech/speech.m4a")
    }

    private static func locateAudio() -> SpeechAudio? {
        let url = ProcessInfo.processInfo.environment["SPEECH_AUDIO"].map { URL(fileURLWithPath: $0) } ?? defaultAudioURL
        guard FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) else { return nil }
        let expectedURL = url.deletingLastPathComponent().appending(path: "expected.txt")
        let expected = (try? String(contentsOf: expectedURL, encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return SpeechAudio(url: url, expectedText: expected?.isEmpty == false ? expected : nil)
    }

    /// Share of the expected letters (punctuation and spaces ignored) found in the recognized
    /// text, counting repeats. Lenient on purpose: TTS audio, numerals and kana/kanji choices vary.
    static func characterRecall(expected: String, recognized: String) -> Double {
        let wanted = expected.filter { $0.isLetter || $0.isNumber }
        guard !wanted.isEmpty else { return 1 }
        var available: [Character: Int] = [:]
        for character in recognized where character.isLetter || character.isNumber {
            available[character, default: 0] += 1
        }
        var found = 0
        for character in wanted where (available[character] ?? 0) > 0 {
            available[character]! -= 1
            found += 1
        }
        return Double(found) / Double(wanted.count)
    }
}

/// Collects what a simulator check observed and writes it to `build/swift-evidence/<name>.txt`
/// (`EVIDENCE_DIR` overrides the directory), which CI adds to the job summary.
final class EvidenceReport {
    private let name: String
    private var lines: [String] = []

    init(name: String) {
        self.name = name
    }

    func log(_ line: String) {
        print("[\(name)] \(line)")
        lines.append(line)
    }

    /// Logs and writes the reason, and returns the skip to throw.
    func skip(_ reason: String) -> XCTSkip {
        log("SKIPPED: \(reason)")
        write()
        return XCTSkip(reason)
    }

    func write() {
        let directory = ProcessInfo.processInfo.environment["EVIDENCE_DIR"].map { URL(fileURLWithPath: $0) }
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "build/swift-evidence")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let text = lines.joined(separator: "\n") + "\n"
        try? Data(text.utf8).write(to: directory.appending(path: "\(name).txt"), options: .atomic)
    }
}
