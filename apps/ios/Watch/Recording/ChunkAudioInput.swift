import AVFoundation
import Foundation

/// What `RecorderController` needs from the thing that writes one chunk file: the microphone
/// (`MicrophoneChunkInput`) in production; Debug builds can substitute an audio file
/// (`DebugAudioFileInput`, docs/platforms/ios-watchos.md 「音声ファイル入力（開発用）」).
@MainActor
protocol ChunkAudioInput: AnyObject {
    var isRecording: Bool { get }
    /// Starts writing the chunk file; false when it cannot start.
    func record() -> Bool
    /// Stops and finalizes the chunk file.
    func stop()
    /// Refreshes the level returned by `peakPower` (called once per tick).
    func updateMeters()
    /// Peak level in dBFS (-160...0) as of the last `updateMeters()`, like `AVAudioRecorder`.
    /// The silence rule (`RecorderPolicy.isSound`, #137) uses the peak only.
    func peakPower(forChannel channelNumber: Int) -> Float
}

/// The production input: `AVAudioRecorder` on the microphone with metering enabled.
@MainActor
final class MicrophoneChunkInput: ChunkAudioInput {
    private let recorder: AVAudioRecorder

    init(url: URL) throws {
        recorder = try AVAudioRecorder(url: url, settings: ChunkAudioFormat.recorderSettings)
        recorder.isMeteringEnabled = true
    }

    var isRecording: Bool { recorder.isRecording }
    func record() -> Bool { recorder.record() }
    func stop() { recorder.stop() }
    func updateMeters() { recorder.updateMeters() }
    func peakPower(forChannel channelNumber: Int) -> Float { recorder.peakPower(forChannel: channelNumber) }
}
