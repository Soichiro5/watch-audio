import AVFoundation
import ComposeApp
import XCTest

/// #19 in the app process: the app's own `IosAppServices` (from the Kotlin graph the app starts at
/// launch, reached through the ComposeApp framework) plays a chunk in the watch's format (16 kHz
/// mono AAC) from the requested offset, and the position advances in real time.
///
/// shared/ui's `IosPlaybackTest` drives the shared UI (previous day → tap an utterance, search →
/// one tap) up to the prepared player, but runs in a plain Kotlin/Native test executable where
/// `AVAudioPlayer.play()` returns false. This bundle is hosted by the iOS app (`TEST_HOST`), where
/// the player can start. The observed positions go to `build/swift-evidence/hosted-playback.txt`
/// as a `PLAYBACK_CHECK check=hosted_player` line, which CI adds to the job summary.
final class HostedPlaybackTests: XCTestCase {
    private static let durationMs: Int64 = 20_000
    private static let requestedMs: Int64 = 8_000
    /// How far the first observed position may be from the request (the player may already have moved on).
    private static let startToleranceMs: Int64 = 1_000

    @MainActor
    func testAppServicesPlayFromTheRequestedOffsetAndAdvance() async throws {
        let report = EvidenceReport(name: "hosted-playback")
        report.log("OS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        report.log("simulator: \(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "-")")

        let graphServices = IosAppGraph.shared.services
        let services = try XCTUnwrap(graphServices as? IosAppServices, "the app's graph has no IosAppServices: \(graphServices)")

        // A chunk file under the app's audio root, played by its relative path like a database row.
        let relative = "hosted-playback-test/\(UUID().uuidString).m4a"
        let directory = URL(fileURLWithPath: services.audioRoot).appending(path: "hosted-playback-test")
        let url = URL(fileURLWithPath: services.audioRoot).appending(path: relative)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer {
            services.stopPlayback()
            try? FileManager.default.removeItem(at: directory)
        }
        try Self.writeWatchFormatAAC(to: url, durationMs: Self.durationMs)
        let format = try AVAudioFile(forReading: url).fileFormat
        report.log("audio: \(Int(format.sampleRate)) Hz, \(format.channelCount) ch, format id \(format.streamDescription.pointee.mFormatID), \(Self.durationMs) ms")
        XCTAssertEqual(format.streamDescription.pointee.mFormatID, kAudioFormatMPEG4AAC, "AAC like the watch's chunks")

        services.play(audioPath: relative, offsetMs: Self.requestedMs)
        report.log("after play(): \(services.playbackDiagnostics())")

        guard let first = await Self.firstPlayingPosition(of: services, timeout: .seconds(5)) else {
            report.log("PLAYBACK_CHECK check=hosted_player requested_ms=\(Self.requestedMs) FAILED not playing within 5 s (\(services.playbackDiagnostics()))")
            report.write()
            XCTFail("the player did not start: \(services.playbackDiagnostics())")
            return
        }
        try await Task.sleep(for: .seconds(2))
        let after = services.playbackPositionMs
        report.log("PLAYBACK_CHECK check=hosted_player requested_ms=\(Self.requestedMs) first_ms=\(first) after_2s_ms=\(after) (\(services.playbackDiagnostics()))")
        report.write()

        XCTAssertGreaterThanOrEqual(first, Self.requestedMs - 200, "starts at the requested offset")
        XCTAssertLessThan(first, Self.requestedMs + Self.startToleranceMs, "starts at the requested offset")
        XCTAssertGreaterThanOrEqual(after - first, 1_000, "the position advances in real time (\(first) -> \(after) ms)")
    }

    /// The position once the player reports playing, or nil if it does not within [timeout].
    @MainActor
    private static func firstPlayingPosition(of services: IosAppServices, timeout: Duration) async -> Int64? {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while clock.now < deadline {
            let position = services.playbackPositionMs
            if position >= 0 { return position }
            try? await Task.sleep(for: .milliseconds(20))
        }
        return nil
    }

    /// [durationMs] of a quiet two-tone signal as AAC-LC, 16 kHz, mono, in an MPEG-4 file (the watch's format).
    private static func writeWatchFormatAAC(to url: URL, durationMs: Int64) throws {
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
