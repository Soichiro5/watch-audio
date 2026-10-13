import AVFoundation
import Foundation

/// Audio for the agent's `get_audio_clip` (#185), only for pairings the user allowed audio: part of a
/// chunk's AAC file decoded on the device into 16 kHz mono 32-bit float samples (`shared/agent`
/// makes the WAV).
enum AgentAudioClip {
    static let sampleRate = 16_000.0

    /// `durationMs` of the file from `offsetMs`, as native-endian floats (fewer if the file ends first).
    static func read(path: String, offsetMs: Int64, durationMs: Int64) throws -> Data {
        let file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
        let source = file.processingFormat
        let start = AVAudioFramePosition(Double(max(0, offsetMs)) * source.sampleRate / 1_000)
        guard start < file.length, durationMs > 0 else { return Data() }
        let frames = AVAudioFrameCount(min(file.length - start, AVAudioFramePosition(Double(durationMs) * source.sampleRate / 1_000)))
        guard let input = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: frames),
              let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: source, to: target)
        else { throw SpeechTranscription.TranscriberError.bufferAllocation }
        file.framePosition = start
        try file.read(into: input, frameCount: frames)
        let capacity = AVAudioFrameCount((Double(input.frameLength) * sampleRate / source.sampleRate).rounded(.up)) + 1_024
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else {
            throw SpeechTranscription.TranscriberError.bufferAllocation
        }
        let feed = SingleBuffer(input)
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in feed.next(inputStatus) }
        if status == .error { throw error ?? SpeechTranscription.TranscriberError.mismatchedFormats }
        guard let samples = output.floatChannelData?[0] else { return Data() }
        return Data(bytes: samples, count: Int(output.frameLength) * MemoryLayout<Float>.size)
    }

    /// Hands one buffer to the converter, then ends the stream (used synchronously inside `read`).
    private final class SingleBuffer: @unchecked Sendable {
        private var buffer: AVAudioPCMBuffer?

        init(_ buffer: AVAudioPCMBuffer) {
            self.buffer = buffer
        }

        func next(_ status: UnsafeMutablePointer<AVAudioConverterInputStatus>) -> AVAudioBuffer? {
            guard let pending = buffer else {
                status.pointee = .endOfStream
                return nil
            }
            buffer = nil
            status.pointee = .haveData
            return pending
        }
    }
}
