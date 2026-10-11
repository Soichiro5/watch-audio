import CryptoKit
import Foundation
import XCTest

/// Compatibility of the Swift wire protocol (apps/ios/SharedSwift) with the Kotlin contract in
/// shared/protocol, using the same test vectors (bundled from shared/protocol/testdata).
final class ChunkProtocolTests: XCTestCase {
    private func vector(_ name: String) throws -> String {
        let bundle = Bundle(for: ChunkProtocolTests.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "json"), "missing test vector \(name).json")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func jsonObject(_ json: String) throws -> NSDictionary {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? NSDictionary)
    }

    func testDecodesManifestWithAudioAndMarks() throws {
        let manifest = try ChunkProtocol.decode(ChunkManifest.self, from: try vector("manifest-v1"))
        XCTAssertEqual(manifest.schemaVersion, 1)
        XCTAssertEqual(manifest.chunkId, "6F9619FF-8B86-D011-B42D-00C04FC964FF")
        XCTAssertEqual(manifest.sessionId, "2B1A3C4D-0000-4000-8000-000000000001")
        XCTAssertEqual(manifest.deviceId, "watch-device-01")
        XCTAssertEqual(manifest.source, .watchos)
        XCTAssertEqual(manifest.startedAtEpochMs, 1_791_622_800_000)
        XCTAssertEqual(manifest.durationMs, 300_000)
        XCTAssertEqual(manifest.endedAtEpochMs, 1_791_623_100_000)
        XCTAssertEqual(manifest.timeZone, "Asia/Tokyo")
        XCTAssertEqual(manifest.markOffsetsMs, [12_000, 240_500])
        XCTAssertEqual(manifest.endReason, .rotation)
        let audio = try XCTUnwrap(manifest.audio)
        XCTAssertEqual(audio.fileName, "6F9619FF-8B86-D011-B42D-00C04FC964FF.m4a")
        XCTAssertEqual(audio.codec, .aac)
        XCTAssertEqual(audio.sampleRateHz, 16_000)
        XCTAssertEqual(audio.channels, 1)
        XCTAssertEqual(audio.bitrateBps, 24_000)
        XCTAssertEqual(audio.sizeBytes, 901_234)
        XCTAssertEqual(audio.sha256, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
    }

    func testDecodesSilentManifestWithDefaults() throws {
        let manifest = try ChunkProtocol.decode(ChunkManifest.self, from: try vector("manifest-v1-silent"))
        XCTAssertNil(manifest.audio)
        XCTAssertEqual(manifest.markOffsetsMs, [])
        XCTAssertEqual(manifest.source, .wearos)
        XCTAssertEqual(manifest.endReason, .charging)
        XCTAssertTrue(manifest.endReason.startsGap)
    }

    /// Re-encoding gives exactly the JSON Kotlin produces (encodeDefaults = true, no nulls).
    func testManifestRoundTripsToKotlinJSON() throws {
        let original = try vector("manifest-v1")
        let manifest = try ChunkProtocol.decode(ChunkManifest.self, from: original)
        let encoded = try ChunkProtocol.encode(manifest)
        XCTAssertEqual(try jsonObject(encoded), try jsonObject(original))
        XCTAssertEqual(try ChunkProtocol.decode(ChunkManifest.self, from: encoded), manifest)
    }

    func testSilentManifestRoundTripOmitsAudioAndWritesEmptyMarks() throws {
        let original = try vector("manifest-v1-silent")
        let encoded = try ChunkProtocol.encode(ChunkProtocol.decode(ChunkManifest.self, from: original))
        let expected = try XCTUnwrap(try jsonObject(original).mutableCopy() as? NSMutableDictionary)
        expected["markOffsetsMs"] = [Int]()
        XCTAssertEqual(try jsonObject(encoded), expected)
        XCTAssertNil(try jsonObject(encoded)["audio"], "absent audio must be omitted, not null")
    }

    func testAckRoundTrip() throws {
        let original = try vector("ack-v1")
        let ack = try ChunkProtocol.decode(ChunkAck.self, from: original)
        XCTAssertEqual(ack.chunkId, "6F9619FF-8B86-D011-B42D-00C04FC964FF")
        XCTAssertEqual(ack.status, .stored)
        XCTAssertTrue(ack.status.isFinal)
        XCTAssertNil(ack.detail)
        XCTAssertEqual(try jsonObject(ChunkProtocol.encode(ack)), try jsonObject(original))
    }

    func testAckStatusesMatchKotlin() throws {
        let json = #"{"chunkId":"x","status":"corrupt","detail":"sha256 mismatch","futureField":1}"#
        let ack = try ChunkProtocol.decode(ChunkAck.self, from: json)
        XCTAssertEqual(ack.schemaVersion, 1)
        XCTAssertEqual(ack.status, .corrupt)
        XCTAssertFalse(ack.status.isFinal)
        XCTAssertEqual(ack.detail, "sha256 mismatch")
        XCTAssertTrue(AckStatus.duplicate.isFinal)
        XCTAssertFalse(AckStatus.rejected.isFinal)
    }

    /// Enum strings must match the @SerialName values in ChunkManifest.kt.
    func testEndReasonWireValues() throws {
        XCTAssertEqual(
            ChunkEndReason.allCases.map(\.rawValue),
            ["rotation", "mark", "user_stop", "interruption", "charging", "off_body", "low_battery", "low_storage", "terminated", "error"]
        )
        XCTAssertFalse(ChunkEndReason.rotation.startsGap)
        XCTAssertFalse(ChunkEndReason.mark.startsGap)
        XCTAssertTrue(ChunkEndReason.userStop.startsGap)
    }

    func testFileDigestMatchesKnownVector() throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "digest-\(UUID().uuidString).bin")
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("abc".utf8).write(to: url)
        XCTAssertEqual(try FileDigest.sha256Hex(of: url), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

        // Spans several 64 KiB blocks: streaming must equal the one-shot hash.
        let big = Data((0..<200_000).map { UInt8($0 % 251) })
        try big.write(to: url)
        let oneShot = SHA256.hash(data: big).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(try FileDigest.sha256Hex(of: url), oneShot)
    }
}
