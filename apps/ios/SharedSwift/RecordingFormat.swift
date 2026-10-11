import Foundation

/// How the watch records and cuts audio: encoding, sample rate, channels, target bitrate and
/// chunk length. Swift mirror of `RecordingFormat` in shared/protocol (RecordingFormat.kt); both
/// sides check their values against shared/protocol/testdata/recording-format.json, so the Apple
/// Watch and Wear OS apps record the same format. Not sent on the wire itself: each manifest
/// describes its file in `AudioFile`.
struct RecordingFormat: Codable, Equatable, Sendable {
    var codec: AudioCodec
    var sampleRateHz: Int
    var channels: Int
    var bitrateBps: Int
    /// Length of a regular chunk before the recorder rotates to a new file.
    var chunkDurationMs: Int64

    /// What both watch apps record today: AAC-LC 16 kHz mono 24 kbps (.m4a), 5-minute chunks.
    static let current = RecordingFormat(
        codec: .aac,
        sampleRateHz: 16_000,
        channels: 1,
        bitrateBps: 24_000,
        chunkDurationMs: 300_000
    )

    /// Describes a chunk file recorded in this format.
    func audioFile(fileName: String, sizeBytes: Int64, sha256: String) -> AudioFile {
        AudioFile(
            fileName: fileName,
            codec: codec,
            sampleRateHz: sampleRateHz,
            channels: channels,
            bitrateBps: bitrateBps,
            sizeBytes: sizeBytes,
            sha256: sha256
        )
    }
}
