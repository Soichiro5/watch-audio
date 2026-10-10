import AVFoundation
import ComposeApp
import CoreMedia
import Foundation
import Speech

/// Wraps a value that is only handed between threads in a controlled way (here: Kotlin
/// callbacks, which may be invoked from any thread).
struct UncheckedSendableBox<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

/// On-device Japanese speech-to-text with the iOS 26 Speech framework (SpeechAnalyzer +
/// SpeechTranscriber). The model is managed by the OS (AssetInventory); audio never leaves the
/// device. Implements the Kotlin `IosSpeechTranscriber` interface used by the shared pipeline.
final class SpeechAnalyzerTranscriber: NSObject, IosSpeechTranscriber, @unchecked Sendable {
    /// Guarded by `lock`.
    private var running: Task<Void, Never>?
    private let lock = NSLock()
    private let requestedLocale = Locale(identifier: "ja_JP")

    var engineId: String { "apple-speechtranscriber-ja" }

    var language: String { "ja" }

    func prepareModel(callback: @escaping (String?) -> Void) {
        let reply = UncheckedSendableBox(callback)
        let locale = requestedLocale
        Task {
            do {
                _ = try await Self.makeTranscriber(locale: locale)
                reply.value(nil)
            } catch {
                reply.value(Self.describe(error))
            }
        }
    }

    func transcribe(path: String, callback: @escaping ([IosTimedText]?, String?) -> Void) {
        let reply = UncheckedSendableBox(callback)
        let locale = requestedLocale
        let task = Task {
            do {
                let segments = try await Self.transcribeFile(at: URL(fileURLWithPath: path), locale: locale)
                reply.value(segments.map { IosTimedText(startMs: $0.startMs, endMs: $0.endMs, text: $0.text) }, nil)
            } catch {
                reply.value(nil, Self.describe(error))
            }
        }
        lock.lock()
        running = task
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        let task = running
        running = nil
        lock.unlock()
        task?.cancel()
    }

    // MARK: - Speech framework

    private struct Segment: Sendable {
        let startMs: Int64
        let endMs: Int64
        let text: String
    }

    private enum TranscriberError: LocalizedError {
        case unavailable
        case unsupportedLocale(String)

        var errorDescription: String? {
            switch self {
            case .unavailable: "SpeechTranscriber はこの端末で利用できません"
            case .unsupportedLocale(let id): "文字起こしモデルが \(id) に対応していません"
            }
        }
    }

    /// Creates a transcriber for the locale, downloading the on-device model through the OS if
    /// it is not installed yet.
    private static func makeTranscriber(locale requested: Locale) async throws -> SpeechTranscriber {
        guard SpeechTranscriber.isAvailable else { throw TranscriberError.unavailable }
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: requested) else {
            throw TranscriberError.unsupportedLocale(requested.identifier)
        }
        let transcriber = SpeechTranscriber(
            locale: locale,
            transcriptionOptions: [],
            reportingOptions: [],
            attributeOptions: [.audioTimeRange]
        )
        let installed = await SpeechTranscriber.installedLocales
        if !installed.contains(where: { $0.identifier == locale.identifier }) {
            // Reserving keeps the locale's assets for this app; false just means "already reserved".
            _ = try? await AssetInventory.reserve(locale: locale)
            if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                try await request.downloadAndInstall()
            }
        }
        return transcriber
    }

    /// Transcribes a whole file; each final result becomes one segment with its audio time range.
    private static func transcribeFile(at url: URL, locale: Locale) async throws -> [Segment] {
        let transcriber = try await makeTranscriber(locale: locale)
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let collector = Task { () throws -> [Segment] in
            var segments: [Segment] = []
            for try await result in transcriber.results where result.isFinal {
                let text = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { continue }
                segments.append(Segment(
                    startMs: SpeechAnalyzerTranscriber.milliseconds(result.range.start),
                    endMs: SpeechAnalyzerTranscriber.milliseconds(CMTimeRangeGetEnd(result.range)),
                    text: text
                ))
            }
            return segments
        }
        return try await withTaskCancellationHandler {
            do {
                if let lastSample = try await analyzer.analyzeSequence(from: try AVAudioFile(forReading: url)) {
                    try await analyzer.finalizeAndFinish(through: lastSample)
                } else {
                    await analyzer.cancelAndFinishNow() // empty file
                }
                return try await collector.value
            } catch {
                collector.cancel()
                await analyzer.cancelAndFinishNow()
                throw error
            }
        } onCancel: {
            collector.cancel()
            Task { await analyzer.cancelAndFinishNow() }
        }
    }

    private static func milliseconds(_ time: CMTime) -> Int64 {
        let seconds = CMTimeGetSeconds(time)
        return seconds.isFinite ? max(0, Int64((seconds * 1000).rounded())) : 0
    }

    private static func describe(_ error: Error) -> String {
        if error is CancellationError { return "cancelled" }
        return (error as? LocalizedError)?.errorDescription ?? String(describing: error)
    }
}
