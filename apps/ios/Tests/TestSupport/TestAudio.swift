import AVFoundation
import XCTest

/// Audio files in the watch's chunk format for the app-hosted checks (`PlaybackTests`, `AgentAccessTests`).
enum TestAudio {
    /// `durationMs` of a quiet two-tone signal as AAC-LC, 16 kHz, mono, in an MPEG-4 file (the watch's format).
    static func writeWatchFormatAAC(to url: URL, durationMs: Int64) throws {
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16_000.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000,
        ]
        let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let sampleRate = file.processingFormat.sampleRate
        let capacity: AVAudioFrameCount = 4_096
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: capacity))
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        let total = AVAudioFramePosition(sampleRate * Double(durationMs) / 1000)
        var written: AVAudioFramePosition = 0
        while written < total {
            let frames = min(AVAudioFramePosition(capacity), total - written)
            for i in 0..<Int(frames) {
                let t = Double(written + AVAudioFramePosition(i)) / sampleRate
                let tone = Int(t) % 2 == 0 ? 440.0 : 660.0
                samples[i] = Float(sin(2 * Double.pi * tone * t) * 0.12)
            }
            buffer.frameLength = AVAudioFrameCount(frames)
            try file.write(from: buffer)
            written += frames
        }
        file.close()
    }
}
