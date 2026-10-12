#if DEBUG
import AVFoundation
import Foundation

// Debug configuration only (Release compiles this file to nothing): plays an audio file into
// the recorder instead of the microphone, so rotation, silence detection, manifests and the
// outbox can be exercised with known audio on the simulator. Real time only: the controller
// measures chunks with the wall clock. Usage: docs/platforms/ios-watchos.md 「音声ファイル入力（開発用）」.

/// The file being played, shared by consecutive chunks so a rotation continues where the
/// previous chunk stopped. Decodes with `AVAudioFile` and converts to 16 kHz mono (channel
/// average + linear interpolation; no filter, good enough to drive the pipeline).
@MainActor
final class DebugAudioPlayback {
    let url: URL
    let loop: Bool
    private let file: AVAudioFile
    private let channels: Int
    /// Source frames per 16 kHz output frame.
    private let step: Double
    /// Decoded mono source samples not consumed yet; `mono[0]` is source frame `monoStart`.
    private var mono: [Float] = []
    private var monoStart: Int64 = 0
    private var outputIndex: Int64 = 0
    private(set) var ended = false
    private var finishReported = false

    init(url: URL, loop: Bool) throws {
        self.url = url
        self.loop = loop
        file = try AVAudioFile(forReading: url)
        channels = Int(file.processingFormat.channelCount)
        step = file.processingFormat.sampleRate / Double(ChunkAudioFormat.sampleRateHz)
    }

    /// Up to `count` 16 kHz mono samples; fewer at the end of the file (then `ended` is set).
    func read(_ count: Int) -> [Float] {
        var out: [Float] = []
        out.reserveCapacity(count)
        while out.count < count, !ended {
            let position = Double(outputIndex) * step
            let index = Int64(position)
            let local = Int(index - monoStart)
            if local + 1 >= mono.count {
                if !decodeMore() { ended = true }
                continue
            }
            let a = mono[local]
            let b = mono[local + 1]
            out.append(a + (b - a) * Float(position - Double(index)))
            outputIndex += 1
        }
        // Keep the sample the next output interpolates from.
        let consumed = min(Int(Int64(Double(outputIndex) * step) - monoStart), mono.count - 1)
        if consumed > 16_384 {
            mono.removeFirst(consumed)
            monoStart += Int64(consumed)
        }
        return out
    }

    /// Reports the end of the file once (stops the recording, see `DebugAudioInput.onFinished`).
    func reportFinished() {
        guard ended, !finishReported else { return }
        finishReported = true
        Task { @MainActor in DebugAudioInput.onFinished?() }
    }

    private func decodeMore() -> Bool {
        if file.framePosition >= file.length {
            guard loop, file.length > 0 else { return false }
            file.framePosition = 0
        }
        let capacity: AVAudioFrameCount = 4_096
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: capacity) else { return false }
        do {
            try file.read(into: buffer, frameCount: capacity)
        } catch {
            return false
        }
        guard buffer.frameLength > 0, let data = buffer.floatChannelData else { return false }
        for frame in 0..<Int(buffer.frameLength) {
            var sum: Float = 0
            for channel in 0..<channels {
                sum += data[channel][frame]
            }
            mono.append(sum / Float(channels))
        }
        return true
    }
}

/// Writes one chunk from `DebugAudioPlayback` in real time: each meter update (once per tick)
/// and `stop()` append the samples due since `record()` to an AAC file with the production
/// settings (`ChunkAudioFormat.recorderSettings`), and report their peak level in dBFS like
/// `AVAudioRecorder` metering.
@MainActor
final class DebugAudioFileInput: ChunkAudioInput {
    private let playback: DebugAudioPlayback
    private let output: AVAudioFile
    private var startedAt: Date?
    private var framesWritten: Int64 = 0
    private var maxAbs: Float = 0
    private var peak: Float = -160
    private(set) var isRecording = false

    init(url: URL, playback: DebugAudioPlayback) throws {
        self.playback = playback
        output = try AVAudioFile(
            forWriting: url,
            settings: ChunkAudioFormat.recorderSettings,
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )
    }

    func record() -> Bool {
        startedAt = .now
        isRecording = true
        return true
    }

    func stop() {
        guard isRecording else { return }
        pump()
        isRecording = false
        output.close()
    }

    func updateMeters() {
        pump()
        peak = Self.decibels(maxAbs)
        maxAbs = 0
    }

    func peakPower(forChannel channelNumber: Int) -> Float { peak }

    /// Writes the samples due by now. At the end of the file it writes nothing more and asks
    /// the controller to stop (the chunk then closes as `user_stop`).
    private func pump() {
        guard isRecording, let startedAt else { return }
        let due = Int64(Date.now.timeIntervalSince(startedAt) * Double(ChunkAudioFormat.sampleRateHz)) - framesWritten
        if due > 0 {
            let samples = playback.read(Int(due))
            if !samples.isEmpty, let buffer = AVAudioPCMBuffer(pcmFormat: output.processingFormat, frameCapacity: AVAudioFrameCount(samples.count)),
               let channel = buffer.floatChannelData?[0] {
                for (i, sample) in samples.enumerated() {
                    channel[i] = sample
                    maxAbs = max(maxAbs, abs(sample))
                }
                buffer.frameLength = AVAudioFrameCount(samples.count)
                do {
                    try output.write(from: buffer)
                } catch {
                    isRecording = false // the controller treats this as a stalled recorder
                }
                framesWritten += Int64(samples.count)
            }
        }
        playback.reportFinished()
    }

    private static func decibels(_ amplitude: Float) -> Float {
        amplitude > 0 ? max(-160, 20 * log10(amplitude)) : -160
    }
}

/// Which file the next chunk inputs read (nil: the microphone).
@MainActor
enum DebugAudioInput {
    static var playback: DebugAudioPlayback?
    static var onFinished: (@MainActor () -> Void)?

    /// `Documents/debug-input` in the app container (copy files there with `simctl`).
    static var directory: URL {
        URL.documentsDirectory.appending(path: "debug-input")
    }

    static func availableFiles() -> [URL] {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return files.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    /// The launch argument `-debugAudioInput <name or absolute path>` (`-debugAudioInputLoop YES`
    /// to repeat). A name is looked up in `directory`, then in the app bundle.
    static func launchArgumentFile() -> URL? {
        guard let value = UserDefaults.standard.string(forKey: "debugAudioInput"), !value.isEmpty else { return nil }
        if value.hasPrefix("/") { return URL(filePath: value) }
        let inDirectory = directory.appending(path: value)
        if FileManager.default.fileExists(atPath: inDirectory.path(percentEncoded: false)) { return inDirectory }
        return Bundle.main.url(forResource: value, withExtension: nil)
    }

    static var launchArgumentLoop: Bool {
        UserDefaults.standard.bool(forKey: "debugAudioInputLoop")
    }
}

extension RecorderController {
    /// Records `url` instead of the microphone until the file ends or `stopDebugAudioInput()`.
    func startDebugAudioInput(_ url: URL, loop: Bool) async {
        if phase != .idle { stop() }
        do {
            DebugAudioInput.playback = try DebugAudioPlayback(url: url, loop: loop)
        } catch {
            DebugAudioInput.playback = nil
            return
        }
        DebugAudioInput.onFinished = { [weak self] in self?.stopDebugAudioInput() }
        await start()
    }

    /// Stops recording and switches back to the microphone.
    func stopDebugAudioInput() {
        DebugAudioInput.playback = nil
        DebugAudioInput.onFinished = nil
        stop()
    }
}
#endif
