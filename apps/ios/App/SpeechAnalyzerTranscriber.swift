import ComposeApp
import Foundation

/// Wraps a value that is only handed between threads in a controlled way (here: Kotlin
/// callbacks, which may be invoked from any thread).
struct UncheckedSendableBox<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

/// On-device Japanese speech-to-text for the shared pipeline: implements the Kotlin
/// `IosSpeechTranscriber` interface on top of `SpeechTranscription` (SpeechAnalyzer +
/// SpeechTranscriber, iOS 26). The model is managed by the OS; audio never leaves the device.
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
                _ = try await SpeechTranscription.makeTranscriber(locale: locale)
                reply.value(nil)
            } catch {
                reply.value(SpeechTranscription.describe(error))
            }
        }
    }

    func transcribe(parts: [IosAudioPart], callback: @escaping ([IosTimedText]?, String?) -> Void) {
        let reply = UncheckedSendableBox(callback)
        let locale = requestedLocale
        let ranges = parts.map { SpeechTranscription.AudioRange(url: URL(fileURLWithPath: $0.path), fromMs: $0.fromMs, toMs: $0.toMs) }
        let task = Task {
            do {
                let segments = try await SpeechTranscription.transcribe(ranges, locale: locale)
                reply.value(segments.map { IosTimedText(startMs: $0.startMs, endMs: $0.endMs, text: $0.text) }, nil)
            } catch {
                reply.value(nil, SpeechTranscription.describe(error))
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
}
