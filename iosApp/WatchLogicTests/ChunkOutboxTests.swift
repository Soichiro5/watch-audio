import AVFoundation
import Foundation
import XCTest

/// The watch's send queue (#17): `ChunkOutbox` (persistent files) and `ChunkSendTracker` (when to
/// resend), the Swift counterpart of the Wear OS `OutboxTest`. A chunk leaves the queue only on a
/// final ACK; CORRUPT resends it, a missing ACK resends it after a timeout, and pending chunks
/// survive a relaunch.
final class ChunkOutboxTests: XCTestCase {
    private var root: URL!
    private var outbox: ChunkOutbox!
    private let t0 = Date(timeIntervalSince1970: 1_791_622_800)

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "outbox-test-\(UUID().uuidString)")
        outbox = ChunkOutbox(root: root)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    @discardableResult
    private func commit(
        _ id: String,
        startedAt: Int64 = 1_791_622_800_000,
        audio: Data? = Data([1, 2, 3, 4]),
        marks: [Int64] = [],
        endReason: ChunkEndReason = .rotation
    ) throws -> OutboxItem {
        let chunk = InProgressChunk(chunkId: id, sessionId: "s", startedAtEpochMs: startedAt, timeZone: "Asia/Tokyo", markOffsetsMs: marks)
        outbox.saveInProgress(chunk)
        if let audio {
            try audio.write(to: outbox.recordingAudioURL(id))
        }
        return try outbox.commit(chunk, deviceId: "watch", durationMs: 300_000, endReason: endReason, keepAudio: audio != nil)
    }

    private func files(in directory: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path(percentEncoded: false))) ?? []).sorted()
    }

    private func ack(_ id: String, _ status: AckStatus, detail: String? = nil) -> ChunkAck {
        ChunkAck(chunkId: id, status: status, detail: detail)
    }

    // MARK: - Outbox files

    func testCommittedChunksArePendingOldestFirst() throws {
        try commit("b", startedAt: 2_000)
        try commit("a", startedAt: 1_000)
        try commit("silent", startedAt: 3_000, audio: nil)

        let pending = outbox.pendingItems()
        XCTAssertEqual(pending.map(\.chunkId), ["a", "b", "silent"])
        let first = try XCTUnwrap(pending.first)
        let audioURL = try XCTUnwrap(first.audioURL)
        XCTAssertEqual(try Data(contentsOf: audioURL), Data([1, 2, 3, 4]))
        let audio = try XCTUnwrap(first.manifest.audio)
        XCTAssertEqual(audio.sizeBytes, 4)
        XCTAssertEqual(audio.sha256, try FileDigest.sha256Hex(of: audioURL))
        XCTAssertEqual(audio.codec, .aac)
        XCTAssertEqual(audio.sampleRateHz, 16_000)
        XCTAssertNil(pending.last?.audioURL, "a silent chunk is sent as a manifest only")
        XCTAssertNil(pending.last?.manifest.audio)
        XCTAssertEqual(files(in: outbox.recordingDirectory), [], "nothing is left in recording/")
    }

    func testMarksAreClampedToTheChunk() throws {
        let item = try commit("m", marks: [-5, 12_000, 999_999])
        XCTAssertEqual(item.manifest.markOffsetsMs, [0, 12_000, 300_000])
    }

    func testFinalAcksDeleteTheChunk() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        try commit("b", startedAt: 1_791_623_100_000)
        tracker.markSent("a", at: t0)
        tracker.markSent("b", at: t0)

        let finalAcks: [(String, AckStatus)] = [("a", .stored), ("b", .duplicate)]
        for (id, status) in finalAcks {
            XCTAssertEqual(tracker.apply(ack(id, status)), .delete)
            outbox.remove(chunkId: id)
        }
        XCTAssertEqual(outbox.pendingItems().count, 0)
        XCTAssertEqual(files(in: outbox.outboxDirectory), [], "audio and manifest are both gone")
    }

    func testCorruptAckResendsUpToTheLimit() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        tracker.markSent("a", at: t0)
        for attempt in 1...ChunkSendTracker.maxCorruptResends {
            XCTAssertEqual(tracker.apply(ack("a", .corrupt, detail: "sha256 mismatch")), .resend, "attempt \(attempt)")
            XCTAssertNotNil(outbox.item(chunkId: "a"), "the chunk is still there to resend")
        }
        XCTAssertEqual(tracker.apply(ack("a", .corrupt)), .keep, "then it waits for the next activation")
        XCTAssertEqual(outbox.pendingItems().map(\.chunkId), ["a"])
        // A later final ACK still deletes it.
        XCTAssertEqual(tracker.apply(ack("a", .stored)), .delete)
    }

    func testRejectedAckKeepsTheChunk() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        tracker.markSent("a", at: t0)
        XCTAssertEqual(tracker.apply(ack("a", .rejected, detail: "unsupported schema 2")), .keep)
        XCTAssertEqual(outbox.pendingItems().map(\.chunkId), ["a"])
    }

    func testUnackedChunksAreResentAfterTheTimeout() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        let pending = outbox.pendingItems().map(\.chunkId)
        XCTAssertEqual(tracker.dueForSend(pending: pending, inFlight: [], now: t0, force: false), ["a"], "never sent: due")

        tracker.markSent("a", at: t0)
        let almost = t0.addingTimeInterval(ChunkSendTracker.resendAfter - 1)
        XCTAssertEqual(tracker.dueForSend(pending: pending, inFlight: [], now: almost, force: false), [])
        let timeout = t0.addingTimeInterval(ChunkSendTracker.resendAfter)
        XCTAssertEqual(tracker.dueForSend(pending: pending, inFlight: [], now: timeout, force: false), ["a"])
    }

    func testChunksStillQueuedWithTheSystemAreNotSentTwice() throws {
        let tracker = ChunkSendTracker()
        try commit("a")
        try commit("b", startedAt: 1_791_623_100_000)
        let pending = outbox.pendingItems().map(\.chunkId)
        XCTAssertEqual(tracker.dueForSend(pending: pending, inFlight: ["a"], now: t0, force: true), ["b"])
    }

    func testFailedTransferIsDueAgainRightAway() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        tracker.markSent("a", at: t0)
        tracker.markFailed("a")
        XCTAssertEqual(tracker.dueForSend(pending: ["a"], inFlight: [], now: t0.addingTimeInterval(1), force: false), ["a"])
    }

    func testActivationSendsEverythingPendingEvenIfRecentlySent() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        tracker.markSent("a", at: t0)
        XCTAssertEqual(tracker.dueForSend(pending: ["a"], inFlight: [], now: t0.addingTimeInterval(1), force: true), ["a"])
    }

    /// The app is terminated with unacknowledged chunks: a new process (new outbox instance over the
    /// same directory, empty tracker) still has them and sends them once WatchConnectivity activates.
    func testPendingChunksSurviveARelaunchAndAreResent() throws {
        var tracker = ChunkSendTracker()
        try commit("a")
        try commit("silent", startedAt: 1_791_623_100_000, audio: nil)
        tracker.markSent("a", at: t0)
        XCTAssertEqual(tracker.apply(ack("silent", .stored)), .delete)
        outbox.remove(chunkId: "silent")

        let relaunched = ChunkOutbox(root: root)
        let pending = relaunched.pendingItems()
        XCTAssertEqual(pending.map(\.chunkId), ["a"])
        XCTAssertNotNil(pending.first?.audioURL)
        let fresh = ChunkSendTracker()
        XCTAssertEqual(fresh.dueForSend(pending: pending.map(\.chunkId), inFlight: [], now: t0, force: true), ["a"])
        XCTAssertEqual(try ChunkProtocol.decode(ChunkManifest.self, from: pending[0].manifestJSON), pending[0].manifest)
    }

    // MARK: - Recovery and damaged entries

    /// A chunk that was still recording when the app was terminated is finalized on the next
    /// launch with end reason `terminated`, keeping its audio when the file is readable.
    func testRecoversARecordingInterruptedByTermination() throws {
        let chunk = InProgressChunk(chunkId: "rec", sessionId: "s", startedAtEpochMs: 1_791_622_800_000, timeZone: "Asia/Tokyo", markOffsetsMs: [500])
        outbox.saveInProgress(chunk)
        try writeTone(to: outbox.recordingAudioURL("rec"), seconds: 2)

        let recovered = ChunkOutbox(root: root).recoverAbandoned(deviceId: "watch")
        XCTAssertEqual(recovered.map(\.chunkId), ["rec"])
        let manifest = recovered[0].manifest
        XCTAssertEqual(manifest.endReason, .terminated)
        XCTAssertNotNil(manifest.audio, "readable audio is kept")
        XCTAssertEqual(Double(manifest.durationMs), 2_000, accuracy: 300)
        XCTAssertEqual(manifest.markOffsetsMs, [500])
        XCTAssertEqual(outbox.pendingItems().map(\.chunkId), ["rec"])
        XCTAssertEqual(files(in: outbox.recordingDirectory), [])
    }

    func testUnreadableRecordingIsSentWithoutAudio() throws {
        let chunk = InProgressChunk(chunkId: "broken", sessionId: "s", startedAtEpochMs: 1_791_622_800_000, timeZone: "Asia/Tokyo", markOffsetsMs: [])
        outbox.saveInProgress(chunk)
        try Data("not audio".utf8).write(to: outbox.recordingAudioURL("broken"))
        // Audio without a state file cannot be described and is dropped.
        try Data([0]).write(to: outbox.recordingAudioURL("orphan"))

        let recovered = outbox.recoverAbandoned(deviceId: "watch")
        XCTAssertEqual(recovered.map(\.chunkId), ["broken"])
        XCTAssertEqual(recovered[0].manifest.endReason, .terminated)
        XCTAssertNil(recovered[0].manifest.audio)
        XCTAssertEqual(files(in: outbox.recordingDirectory), [])
    }

    func testDamagedEntriesAreDropped() throws {
        try commit("ok")
        try commit("lost-audio", startedAt: 1_791_623_100_000)
        try FileManager.default.removeItem(at: outbox.outboxDirectory.appending(path: "lost-audio.m4a"))
        try Data("{".utf8).write(to: outbox.outboxDirectory.appending(path: "garbage.json"))

        XCTAssertEqual(outbox.pendingItems().map(\.chunkId), ["ok"])
        XCTAssertEqual(files(in: outbox.outboxDirectory), ["ok.json", "ok.m4a"])
    }

    /// 16 kHz mono AAC like the recorder's chunks.
    private func writeTone(to url: URL, seconds: Double) throws {
        let file = try AVAudioFile(
            forWriting: url,
            settings: ChunkAudioFormat.recorderSettings,
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )
        let frames = AVAudioFrameCount(file.processingFormat.sampleRate * seconds)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: frames))
        buffer.frameLength = frames
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        let rate = Float(file.processingFormat.sampleRate)
        for i in 0..<Int(frames) {
            samples[i] = 0.2 * sinf(2 * .pi * 440 * Float(i) / rate)
        }
        try file.write(from: buffer)
        // The file is finalized when `file` is released at the end of this function.
    }
}
