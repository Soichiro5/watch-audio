import AVFoundation
import CoreMedia
import Foundation
import Speech

/// On-device speech-to-text with the iOS 26 Speech framework (SpeechAnalyzer +
/// SpeechTranscriber), independent of the Kotlin bridge so the simulator tests (`SpeechTests`)
/// can run it directly. The model is managed by the OS (AssetInventory); audio never leaves the
/// device. `SpeechAnalyzerTranscriber` adapts it to the shared pipeline.
enum SpeechTranscription {
    /// One final result, times in ms from the start of the transcribed audio, with the model's
    /// confidence in it (0...1, the mean over its words weighted by length; nil if not reported).
    struct Segment: Sendable, Equatable {
        let startMs: Int64
        let endMs: Int64
        let text: String
        var confidence: Double? = nil
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

    /// The Speech framework module that transcribes. The app uses `.speech` (SpeechTranscriber).
    /// `.dictation` (DictationTranscriber, the older on-device dictation models) is what the
    /// simulator checks fall back to where SpeechTranscriber is unavailable (GitHub's runners).
    enum Engine: String, Sendable {
        case speech
        case dictation
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
            attributeOptions: [.audioTimeRange, .transcriptionConfidence]
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

    /// A DictationTranscriber for the locale (see `Engine.dictation`), installing its model like
    /// `makeTranscriber`.
    static func makeDictationTranscriber(locale requested: Locale) async throws -> DictationTranscriber {
        guard let locale = await DictationTranscriber.supportedLocale(equivalentTo: requested) else {
            throw TranscriberError.unsupportedLocale(requested.identifier)
        }
        let transcriber = DictationTranscriber(
            locale: locale,
            contentHints: [],
            transcriptionOptions: [],
            reportingOptions: [],
            attributeOptions: [.audioTimeRange, .transcriptionConfidence]
        )
        let installed = await DictationTranscriber.installedLocales
        if !installed.contains(where: { $0.identifier == locale.identifier }) {
            _ = try? await AssetInventory.reserve(locale: locale)
            if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                try await request.downloadAndInstall()
            }
        }
        return transcriber
    }

    /// Transcribes the ranges as one recording. A single range from the start of its file is
    /// analysed in place; otherwise the ranges are first copied into one temporary PCM file.
    static func transcribe(_ ranges: [AudioRange], locale: Locale, engine: Engine = .speech) async throws -> [Segment] {
        if ranges.count == 1, let only = ranges.first, only.fromMs == 0 {
            return try await transcribeFile(at: only.url, locale: locale, engine: engine)
        }
        let joined = try concatenate(ranges)
        defer { try? FileManager.default.removeItem(at: joined) }
        return try await transcribeFile(at: joined, locale: locale, engine: engine)
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
    static func transcribeFile(at url: URL, locale: Locale, engine: Engine = .speech) async throws -> [Segment] {
        switch engine {
        case .speech:
            let transcriber = try await makeTranscriber(locale: locale)
            return try await analyze(url, module: transcriber) {
                var segments: [Segment] = []
                for try await result in transcriber.results where result.isFinal {
                    if let segment = SpeechTranscription.segment(result.text, range: result.range) { segments.append(segment) }
                }
                return segments
            }
        case .dictation:
            let transcriber = try await makeDictationTranscriber(locale: locale)
            return try await analyze(url, module: transcriber) {
                var segments: [Segment] = []
                for try await result in transcriber.results where result.isFinal {
                    if let segment = SpeechTranscription.segment(result.text, range: result.range) { segments.append(segment) }
                }
                return segments
            }
        }
    }

    /// One final result as a segment, or nil if its text is blank.
    static func segment(_ text: AttributedString, range: CMTimeRange) -> Segment? {
        let plain = String(text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !plain.isEmpty else { return nil }
        return Segment(
            startMs: milliseconds(range.start),
            endMs: milliseconds(CMTimeRangeGetEnd(range)),
            text: plain,
            confidence: confidence(of: text)
        )
    }

    /// Runs the file through an analyzer with `module` while `collect` gathers the module's results.
    private static func analyze(
        _ url: URL,
        module: any SpeechModule,
        collect: @escaping @Sendable () async throws -> [Segment]
    ) async throws -> [Segment] {
        let input = try await analyzerInput(url, modules: [module])
        defer {
            if let temporary = input.temporary { try? FileManager.default.removeItem(at: temporary) }
        }
        let file = input.file
        let analyzer = SpeechAnalyzer(modules: [module])
        let collector = Task { try await collect() }
        return try await withTaskCancellationHandler {
            do {
                if let lastSample = try await analyzer.analyzeSequence(from: file) {
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

    /// The file as the modules take it: the file itself if its processing format is the modules'
    /// best available format, otherwise a temporary PCM copy converted to that format (which the
    /// caller deletes). DictationTranscriber rejects the watch's 16 kHz AAC read as Float32
    /// ("Audio format is not supported"), so the format is never assumed.
    static func analyzerInput(_ url: URL, modules: [any SpeechModule]) async throws -> (file: AVAudioFile, temporary: URL?) {
        let file = try AVAudioFile(forReading: url)
        guard let target = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: modules),
              file.processingFormat != target
        else { return (file, nil) }
        let converted = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("caf")
        do {
            try convert(file, to: target, at: converted)
            return (try AVAudioFile(forReading: converted, commonFormat: target.commonFormat, interleaved: target.isInterleaved), converted)
        } catch {
            try? FileManager.default.removeItem(at: converted)
            throw error
        }
    }

    /// Writes the whole of `input` converted to `target` (sample format, rate, channels) to a CAF file.
    static func convert(_ input: AVAudioFile, to target: AVAudioFormat, at url: URL) throws {
        let source = input.processingFormat
        guard let converter = AVAudioConverter(from: source, to: target) else { throw TranscriberError.mismatchedFormats }
        let capacity: AVAudioFrameCount = 16_384
        let outputCapacity = AVAudioFrameCount((Double(capacity) * target.sampleRate / source.sampleRate).rounded(.up)) + 1_024
        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: capacity),
              let outputBuffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: outputCapacity)
        else { throw TranscriberError.bufferAllocation }
        let writer = try AVAudioFile(forWriting: url, settings: target.settings, commonFormat: target.commonFormat, interleaved: target.isInterleaved)
        let reader = ConverterSource(file: input, buffer: inputBuffer)
        while true {
            var error: NSError?
            let status = converter.convert(to: outputBuffer, error: &error) { _, inputStatus in
                reader.next(inputStatus)
            }
            if status == .error { throw error ?? TranscriberError.mismatchedFormats }
            if outputBuffer.frameLength > 0 { try writer.write(from: outputBuffer) }
            if status == .endOfStream || status == .inputRanDry { break }
        }
        writer.close()
    }

    /// Feeds an `AVAudioConverter` from a file, one buffer at a time (only used synchronously
    /// inside `convert`).
    private final class ConverterSource: @unchecked Sendable {
        private let file: AVAudioFile
        private let buffer: AVAudioPCMBuffer
        private var finished = false

        init(file: AVAudioFile, buffer: AVAudioPCMBuffer) {
            self.file = file
            self.buffer = buffer
        }

        func next(_ status: UnsafeMutablePointer<AVAudioConverterInputStatus>) -> AVAudioBuffer? {
            if !finished {
                buffer.frameLength = 0
                do {
                    try file.read(into: buffer)
                } catch {
                    buffer.frameLength = 0
                }
                if buffer.frameLength > 0 {
                    status.pointee = .haveData
                    return buffer
                }
                finished = true
            }
            status.pointee = .endOfStream
            return nil
        }
    }

    /// Mean word confidence of a result, weighted by the length of each run; nil if none is given.
    static func confidence(of text: AttributedString) -> Double? {
        var weighted = 0.0
        var weight = 0.0
        for run in text.runs {
            guard let confidence = run.transcriptionConfidence else { continue }
            let length = Double(text[run.range].characters.count)
            weighted += confidence * length
            weight += length
        }
        return weight > 0 ? weighted / weight : nil
    }

    /// Gives up this app's reservation of the locale's model, so the OS may remove it (the
    /// English model when 「英語も文字起こしする」 is turned off, #170). True if it was reserved.
    @discardableResult
    static func releaseModel(locale requested: Locale) async -> Bool {
        let locale = await SpeechTranscriber.supportedLocale(equivalentTo: requested) ?? requested
        return await AssetInventory.release(reservedLocale: locale)
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
