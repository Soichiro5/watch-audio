import AVFoundation
import CoreMedia
import Foundation
import Speech

/// On-device speech-to-text with the iOS 26 Speech framework (SpeechAnalyzer +
/// SpeechTranscriber), independent of the Kotlin bridge so the simulator tests (`SpeechTests`)
/// can run it directly. The model is managed by the OS (AssetInventory); audio never leaves the
/// device. `SpeechAnalyzerTranscriber` adapts it to the shared pipeline.
enum SpeechTranscription {
    /// One final result, times in ms from the start of the transcribed audio.
    struct Segment: Sendable, Equatable {
        let startMs: Int64
        let endMs: Int64
        let text: String
    }

    /// `[fromMs, toMs)` of an audio file, in ms from its start.
    struct AudioRange: Sendable {
        let url: URL
        let fromMs: Int64
        let toMs: Int64
    }

    enum TranscriberError: LocalizedError {
        case unavailable
        case unsupportedLocale(String)
        case mismatchedFormats
        case bufferAllocation

        var errorDescription: String? {
            switch self {
            case .unavailable: "SpeechTranscriber はこの端末で利用できません"
            case .unsupportedLocale(let id): "文字起こしモデルが \(id) に対応していません"
            case .mismatchedFormats: "連結する音声のサンプルレートまたはチャンネル数が異なります"
            case .bufferAllocation: "音声バッファを確保できません"
            }
        }
    }

    /// Creates a transcriber for the locale, downloading the on-device model through the OS if
    /// it is not installed yet.
    static func makeTranscriber(locale requested: Locale) async throws -> SpeechTranscriber {
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

    /// Transcribes the ranges as one recording. A single range from the start of its file is
    /// analysed in place; otherwise the ranges are first copied into one temporary PCM file.
    static func transcribe(_ ranges: [AudioRange], locale: Locale) async throws -> [Segment] {
        if ranges.count == 1, let only = ranges.first, only.fromMs == 0 {
            return try await transcribeFile(at: only.url, locale: locale)
        }
        let joined = try concatenate(ranges)
        defer { try? FileManager.default.removeItem(at: joined) }
        return try await transcribeFile(at: joined, locale: locale)
    }

    /// Copies each range into one temporary 32-bit float CAF file; a range running past the end
    /// of its file is padded with silence, so every range keeps its exact length. All files
    /// must share sample rate and channel count, which holds within one recording session.
    static func concatenate(_ ranges: [AudioRange]) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("caf")
        do {
            var output: AVAudioFile?
            for range in ranges {
                let input = try AVAudioFile(forReading: range.url)
                let format = input.processingFormat
                if output == nil {
                    let settings: [String: Any] = [
                        AVFormatIDKey: kAudioFormatLinearPCM,
                        AVSampleRateKey: format.sampleRate,
                        AVNumberOfChannelsKey: format.channelCount,
                        AVLinearPCMBitDepthKey: 32,
                        AVLinearPCMIsFloatKey: true,
                        AVLinearPCMIsBigEndianKey: false,
                        AVLinearPCMIsNonInterleaved: false,
                    ]
                    output = try AVAudioFile(
                        forWriting: url,
                        settings: settings,
                        commonFormat: format.commonFormat,
                        interleaved: format.isInterleaved
                    )
                }
                guard let writer = output,
                      writer.processingFormat.sampleRate == format.sampleRate,
                      writer.processingFormat.channelCount == format.channelCount
                else { throw TranscriberError.mismatchedFormats }
                try copy(range, from: input, to: writer)
            }
            output?.close()
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
        return url
    }

    private static func copy(_ range: AudioRange, from input: AVAudioFile, to output: AVAudioFile) throws {
        let format = input.processingFormat
        let framesPerMs = format.sampleRate / 1000
        let capacity: AVAudioFrameCount = 16_384
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else {
            throw TranscriberError.bufferAllocation
        }
        input.framePosition = min(AVAudioFramePosition(Double(range.fromMs) * framesPerMs), input.length)
        var remaining = AVAudioFrameCount(Double(range.toMs - range.fromMs) * framesPerMs)
        while remaining > 0 {
            let wanted = min(remaining, capacity)
            let available = AVAudioFrameCount(clamping: max(0, input.length - input.framePosition))
            buffer.frameLength = 0
            if available > 0 {
                try input.read(into: buffer, frameCount: min(wanted, available))
            }
            if buffer.frameLength == 0 {
                // Past the end of the file: silence.
                buffer.frameLength = wanted
                if let channels = buffer.floatChannelData {
                    for channel in 0..<Int(format.channelCount) {
                        channels[channel].update(repeating: 0, count: Int(wanted))
                    }
                }
            }
            try output.write(from: buffer)
            remaining -= buffer.frameLength
        }
    }

    /// Transcribes a whole file; each final result becomes one segment with its audio time range.
    static func transcribeFile(at url: URL, locale: Locale) async throws -> [Segment] {
        let transcriber = try await makeTranscriber(locale: locale)
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let collector = Task { () throws -> [Segment] in
            var segments: [Segment] = []
            for try await result in transcriber.results where result.isFinal {
                let text = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { continue }
                segments.append(Segment(
                    startMs: SpeechTranscription.milliseconds(result.range.start),
                    endMs: SpeechTranscription.milliseconds(CMTimeRangeGetEnd(result.range)),
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

    static func milliseconds(_ time: CMTime) -> Int64 {
        let seconds = CMTimeGetSeconds(time)
        return seconds.isFinite ? max(0, Int64((seconds * 1000).rounded())) : 0
    }

    static func describe(_ error: Error) -> String {
        if error is CancellationError { return "cancelled" }
        return (error as? LocalizedError)?.errorDescription ?? String(describing: error)
    }
}
