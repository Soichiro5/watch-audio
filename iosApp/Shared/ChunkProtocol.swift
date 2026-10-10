import Foundation

// Swift mirror of the Kotlin wire contract in shared/core-protocol (ChunkManifest.kt,
// ChunkAck.kt, ProtocolJson.kt). Keep the two in sync; ProtocolTests decodes the shared test
// vectors in shared/core-protocol/testdata to guard compatibility.
//
// JSON rules (same as Kotlin's ProtocolJson): times are Int64 epoch milliseconds, enums are the
// lowercase strings below, absent optionals are omitted, unknown keys are ignored.

enum ChunkProtocol {
    static let schemaVersion = 1

    static func encode<T: Encodable>(_ value: T) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    static func decode<T: Decodable>(_ type: T.Type, from json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }
}

enum SourcePlatform: String, Codable, Sendable {
    case watchos
    case wearos
    case ios
    case android
}

enum AudioCodec: String, Codable, Sendable {
    /// AAC-LC in an MPEG-4 (.m4a) container.
    case aac
    /// Opus in an Ogg container.
    case opus
}

enum ChunkEndReason: String, Codable, Sendable, CaseIterable {
    /// Regular rotation; the next chunk continues seamlessly.
    case rotation
    /// The wearer tapped Mark; recording continues in a new chunk.
    case mark
    case userStop = "user_stop"
    /// Phone call, Siri or another app took the microphone.
    case interruption
    case charging
    case offBody = "off_body"
    case lowBattery = "low_battery"
    case lowStorage = "low_storage"
    /// The app was terminated; the chunk was finalized on next launch.
    case terminated
    case error

    /// Anything other than rotation/mark starts a gap on the timeline.
    var startsGap: Bool { self != .rotation && self != .mark }
}

struct AudioFile: Codable, Equatable, Sendable {
    /// File name on the watch, e.g. `<chunkId>.m4a`.
    var fileName: String
    var codec: AudioCodec
    var sampleRateHz: Int
    var channels: Int
    var bitrateBps: Int
    var sizeBytes: Int64
    /// Lowercase hex SHA-256 of the file contents.
    var sha256: String
}

/// One recorded chunk (normally ~5 minutes). `audio == nil` means the span was confidently
/// silent: it stays on the timeline but no audio was kept.
struct ChunkManifest: Codable, Equatable, Sendable {
    var schemaVersion: Int = ChunkProtocol.schemaVersion
    /// UUID generated on the watch; the idempotency key for ingest and ACKs.
    var chunkId: String
    var sessionId: String
    var deviceId: String
    var source: SourcePlatform
    var startedAtEpochMs: Int64
    var durationMs: Int64
    /// IANA time zone, e.g. `Asia/Tokyo`.
    var timeZone: String
    var audio: AudioFile?
    /// Offsets in ms from `startedAtEpochMs`; must lie within `0...durationMs`.
    var markOffsetsMs: [Int64] = []
    var endReason: ChunkEndReason

    var endedAtEpochMs: Int64 { startedAtEpochMs + durationMs }

    enum CodingKeys: String, CodingKey {
        case schemaVersion, chunkId, sessionId, deviceId, source, startedAtEpochMs, durationMs
        case timeZone, audio, markOffsetsMs, endReason
    }
}

extension ChunkManifest {
    // Defaults for keys Kotlin may omit (it always writes them today, but older/other peers may not).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? ChunkProtocol.schemaVersion
        chunkId = try c.decode(String.self, forKey: .chunkId)
        sessionId = try c.decode(String.self, forKey: .sessionId)
        deviceId = try c.decode(String.self, forKey: .deviceId)
        source = try c.decode(SourcePlatform.self, forKey: .source)
        startedAtEpochMs = try c.decode(Int64.self, forKey: .startedAtEpochMs)
        durationMs = try c.decode(Int64.self, forKey: .durationMs)
        timeZone = try c.decode(String.self, forKey: .timeZone)
        audio = try c.decodeIfPresent(AudioFile.self, forKey: .audio)
        markOffsetsMs = try c.decodeIfPresent([Int64].self, forKey: .markOffsetsMs) ?? []
        endReason = try c.decode(ChunkEndReason.self, forKey: .endReason)
    }
}

enum AckStatus: String, Codable, Sendable {
    /// Stored for the first time.
    case stored
    /// Already stored earlier (resend after a lost ACK).
    case duplicate
    /// Size or hash mismatch: resend the file.
    case corrupt
    /// Manifest not understood (e.g. newer schema): keep the chunk.
    case rejected

    /// Only final statuses allow the watch to delete its copy.
    var isFinal: Bool { self == .stored || self == .duplicate }
}

/// Phone → watch receipt for a chunk.
struct ChunkAck: Codable, Equatable, Sendable {
    var schemaVersion: Int = ChunkProtocol.schemaVersion
    var chunkId: String
    var status: AckStatus
    var detail: String?

    enum CodingKeys: String, CodingKey {
        case schemaVersion, chunkId, status, detail
    }
}

extension ChunkAck {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? ChunkProtocol.schemaVersion
        chunkId = try c.decode(String.self, forKey: .chunkId)
        status = try c.decode(AckStatus.self, forKey: .status)
        detail = try c.decodeIfPresent(String.self, forKey: .detail)
    }
}
