import ComposeApp
import Foundation

/// Wraps a value that is only handed between threads in a controlled way (here: Kotlin
/// callbacks, which may be invoked from any thread).
struct UncheckedSendableBox<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

/// On-device speech-to-text for the shared pipeline: implements the Kotlin
/// `IosSpeechTranscriber` interface on top of `SpeechTranscription` (SpeechAnalyzer +
/// SpeechTranscriber, iOS 26). Japanese always; English for the stretches the shared routing
/// picks while 「英語も文字起こしする」 is on (#170). The models are managed by the OS; audio never
/// leaves the device.
final class SpeechAnalyzerTranscriber: NSObject, IosSpeechTranscriber, @unchecked Sendable {
    /// Guarded by `lock`.
    private var running: Task<Void, Never>?
    private let lock = NSLock()
    private let engine: SpeechTranscription.Engine

    /// `engine` is `.speech` in the app; the simulator checks may use `.dictation`.
    init(engine: SpeechTranscription.Engine = .speech) {
        self.engine = engine
        super.init()
    }

    var language: String { "ja" }

    func engineId(language: String) -> String {
        switch engine {
        case .speech: "apple-speechtranscriber-\(language)"
        case .dictation: "apple-dictationtranscriber-\(language)"
        }
    }

    /// The SpeechTranscriber locale for a language of the shared code.
    static func locale(for language: String) -> Locale {
        switch language {
        case "en": Locale(identifier: "en_US")
        default: Locale(identifier: "ja_JP")
        }
    }

    func prepareModel(language: String, callback: @escaping (String?) -> Void) {
        let reply = UncheckedSendableBox(callback)
        let locale = Self.locale(for: language)
        let engine = engine
        Task {
            do {
                switch engine {
                case .speech: _ = try await SpeechTranscription.makeTranscriber(locale: locale)
                case .dictation: _ = try await SpeechTranscription.makeDictationTranscriber(locale: locale)
                }
                reply.value(nil)
            } catch {
                reply.value(SpeechTranscription.describe(error))
            }
        }
    }

    func releaseModel(language: String, callback: @escaping () -> Void) {
        let reply = UncheckedSendableBox(callback)
        let locale = Self.locale(for: language)
        Task {
            await SpeechTranscription.releaseModel(locale: locale)
            reply.value()
        }
    }

    func transcribe(parts: [IosAudioPart], language: String, callback: @escaping ([IosTimedText]?, String?) -> Void) {
        let reply = UncheckedSendableBox(callback)
        let locale = Self.locale(for: language)
        let ranges = parts.map { SpeechTranscription.AudioRange(url: URL(fileURLWithPath: $0.path), fromMs: $0.fromMs, toMs: $0.toMs) }
        let engine = engine
        let task = Task {
            do {
                let segments = try await SpeechTranscription.transcribe(ranges, locale: locale, engine: engine)
                reply.value(segments.map {
                    IosTimedText(startMs: $0.startMs, endMs: $0.endMs, text: $0.text, confidence: $0.confidence ?? -1)
                }, nil)
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
