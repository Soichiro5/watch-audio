import Foundation
import Speech

/// Temporary probe (#170): can the macOS host run SpeechTranscriber, and what do the ja_JP and
/// en_US models make of Japanese / English / mixed speech (text, confidence, time)?
@main
struct SpeechProbe {
    static func main() async {
        let dir = CommandLine.arguments.dropFirst().first ?? "build/mixed-speech"
        print("OS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        print("SpeechTranscriber.isAvailable: \(SpeechTranscriber.isAvailable)")
        let supported = await SpeechTranscriber.supportedLocales
        print("supported: \(supported.count) \(supported.map(\.identifier).filter { $0.hasPrefix("ja") || $0.hasPrefix("en") })")
        print("installed: \(await SpeechTranscriber.installedLocales.map(\.identifier))")
        print("dictation supported: \(await DictationTranscriber.supportedLocales.count)")
        print("max reserved: \(AssetInventory.maximumReservedLocales), reserved: \(await AssetInventory.reservedLocales.map(\.identifier))")
        guard SpeechTranscriber.isAvailable else { return }
        for file in ["mixed", "japanese", "english"] {
            let url = URL(fileURLWithPath: "\(dir)/\(file).m4a")
            for locale in ["ja_JP", "en_US"] {
                let clock = ContinuousClock()
                let started = clock.now
                do {
                    let segments = try await SpeechTranscription.transcribe(
                        [SpeechTranscription.AudioRange(url: url, fromMs: 0, toMs: 60_000)],
                        locale: Locale(identifier: locale)
                    )
                    print("\(file) \(locale) in \(clock.now - started):")
                    for s in segments {
                        print("  [\(s.startMs)-\(s.endMs)] conf=\(s.confidence.map { String(format: "%.2f", $0) } ?? "-") \(s.text)")
                    }
                } catch {
                    print("\(file) \(locale) FAILED after \(clock.now - started): \(SpeechTranscription.describe(error))")
                }
            }
        }
        print("reserved after: \(await AssetInventory.reservedLocales.map(\.identifier))")
        print("released en: \(await SpeechTranscription.releaseModel(locale: Locale(identifier: "en_US")))")
        print("reserved after release: \(await AssetInventory.reservedLocales.map(\.identifier))")
    }
}
