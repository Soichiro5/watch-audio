import Foundation
import XCTest

/// Stop-reason and state decisions of the watchOS recorder (#15), the Swift counterpart of the
/// Wear OS `RecorderPolicyTest`, plus the check that every stop reason ends up as the end
/// reason of the chunk's manifest.
final class RecorderPolicyTests: XCTestCase {
    private let plenty: Int64 = 8 * 1024 * 1024 * 1024
    private let megabyte: Int64 = 1024 * 1024
    private var healthy: DeviceConditions { DeviceConditions(charging: false, batteryLevel: 0.8, freeBytes: plenty) }

    private func decide(_ event: RecorderEvent, _ phase: RecorderPhase, wants: Bool = true) -> RecorderDecision {
        RecorderPolicy.decide(event, phase: phase, wantsRecording: wants)
    }

    func testHealthyDeviceKeepsRecording() {
        XCTAssertNil(RecorderPolicy.startBlocker(healthy))
        XCTAssertEqual(decide(.statusCheck(healthy), .recording), .noAction)
    }

    func testChargingPausesWithoutAlertAndWinsOverLowBattery() {
        var device = healthy
        device.charging = true
        device.batteryLevel = 0.05
        let decision = decide(.statusCheck(device), .recording)
        XCTAssertEqual(decision.stopReason, .charging)
        XCTAssertTrue(decision.pause, "stays armed, resumes when unplugged")
        XCTAssertNil(decision.alert, "charging is an expected gap")
        XCTAssertFalse(decision.resume)
        XCTAssertEqual(RecorderPolicy.startBlocker(device), .charging)
    }

    func testLowBatteryPausesBelowTenPercentAndAlerts() {
        var device = healthy
        // At the stop threshold itself recording continues; it stops only below it (as on Wear OS).
        device.batteryLevel = 0.10
        XCTAssertEqual(decide(.statusCheck(device), .recording), .noAction)
        device.batteryLevel = 0.09
        XCTAssertEqual(
            decide(.statusCheck(device), .recording),
            RecorderDecision(stopReason: .lowBattery, pause: true, alert: .lowBattery)
        )
    }

    func testLowBatteryUsesHysteresis() {
        var device = healthy
        // Just above the stop threshold: keeps recording, but would not start.
        device.batteryLevel = 0.12
        XCTAssertEqual(decide(.statusCheck(device), .recording), .noAction)
        XCTAssertEqual(RecorderPolicy.startBlocker(device), .lowBattery)
        XCTAssertEqual(decide(.retryCheck(device), .paused(.lowBattery)), .noAction)
        device.batteryLevel = 0.14
        XCTAssertEqual(RecorderPolicy.startBlocker(device), .lowBattery)
        device.batteryLevel = 0.15
        XCTAssertNil(RecorderPolicy.startBlocker(device))
        XCTAssertEqual(decide(.retryCheck(device), .paused(.lowBattery)), RecorderDecision(resume: true))
    }

    /// Same values as the Wear OS `RecorderPolicy` (shared/protocol RecorderRules, #137).
    func testThresholdsAreTheSharedRecorderRules() {
        XCTAssertEqual(RecorderPolicy.lowBatteryPercent, 10)
        XCTAssertEqual(RecorderPolicy.resumeBatteryPercent, 15)
        XCTAssertEqual(RecorderPolicy.lowBatteryPercent, RecorderRules.current.lowBatteryStopPercent)
        XCTAssertEqual(RecorderPolicy.resumeBatteryPercent, RecorderRules.current.lowBatteryResumePercent)
        XCTAssertEqual(RecorderPolicy.silencePeakThresholdDb, -40)
        XCTAssertEqual(RecorderPolicy.minMeterCoverage, 0.8, accuracy: 1e-9)
    }

    func testBatteryLevelIsComparedAsWholePercent() {
        XCTAssertEqual(RecorderPolicy.batteryPercent(0.096), 10, "rounds like the percentage shown on the watch")
        XCTAssertEqual(RecorderPolicy.batteryPercent(0.094), 9)
        XCTAssertEqual(RecorderPolicy.batteryPercent(1), 100)
        XCTAssertEqual(RecorderPolicy.batteryPercent(0), 0)
    }

    func testUnknownBatteryLevelIsNotLow() {
        var device = healthy
        device.batteryLevel = nil
        XCTAssertNil(RecorderPolicy.startBlocker(device))
        XCTAssertEqual(decide(.statusCheck(device), .recording), .noAction)
    }

    func testLowStoragePausesAlertsAndResumesWhenFreed() {
        var device = healthy
        device.freeBytes = 50 * megabyte
        XCTAssertEqual(
            decide(.statusCheck(device), .recording),
            RecorderDecision(stopReason: .lowStorage, pause: true, alert: .lowStorage)
        )
        XCTAssertEqual(RecorderPolicy.startBlocker(device), .lowStorage)
        XCTAssertEqual(decide(.retryCheck(device), .paused(.lowStorage)), .noAction)
        device.freeBytes = 200 * megabyte
        XCTAssertEqual(decide(.retryCheck(device), .paused(.lowStorage)), RecorderDecision(resume: true))
    }

    func testUnpluggingResumesOnlyWhileTheWearerWantsToRecord() {
        XCTAssertEqual(decide(.retryCheck(healthy), .paused(.charging)), RecorderDecision(resume: true))
        XCTAssertEqual(decide(.retryCheck(healthy), .paused(.charging), wants: false), .noAction)
        var stillCharging = healthy
        stillCharging.charging = true
        XCTAssertEqual(decide(.retryCheck(stillCharging), .paused(.charging)), .noAction)
    }

    func testInterruptionClosesTheChunkAndSchedulesAReminder() {
        XCTAssertEqual(
            decide(.interruptionBegan, .recording),
            RecorderDecision(stopReason: .interruption, pause: true, alert: .interruptedReminder)
        )
        XCTAssertEqual(decide(.interruptionBegan, .idle), .noAction)
        XCTAssertEqual(decide(.interruptionBegan, .paused(.charging)), .noAction)
    }

    func testInterruptionEndResumesOnlyAnInterruptedRecording() {
        XCTAssertEqual(decide(.interruptionEnded, .paused(.interruption)), RecorderDecision(resume: true))
        XCTAssertEqual(decide(.interruptionEnded, .paused(.interruption), wants: false), .noAction)
        XCTAssertEqual(decide(.interruptionEnded, .paused(.charging)), .noAction)
        XCTAssertEqual(decide(.interruptionEnded, .idle), .noAction)
        // Interruptions are not retried by the timer (only on their end or when the app opens).
        XCTAssertEqual(decide(.retryCheck(healthy), .paused(.interruption)), .noAction)
        XCTAssertEqual(decide(.retryCheck(healthy), .paused(.error)), .noAction)
    }

    func testUserStopAlwaysClosesWithUserStopAndGoesIdle() {
        for phase in [RecorderPhase.recording, .paused(.charging), .idle] {
            XCTAssertEqual(decide(.userStop, phase, wants: false), RecorderDecision(stopReason: .userStop), "\(phase)")
        }
    }

    func testStalledRecorderRestartsAFewTimesThenAlerts() {
        for restarts in 0..<RecorderPolicy.maxAutoRestarts {
            XCTAssertEqual(
                decide(.recorderStalled(restartsSoFar: restarts), .recording),
                RecorderDecision(stopReason: .error, pause: true, resume: true),
                "restart \(restarts)"
            )
        }
        XCTAssertEqual(
            decide(.recorderStalled(restartsSoFar: RecorderPolicy.maxAutoRestarts), .recording),
            RecorderDecision(stopReason: .error, pause: true, alert: .repeatedStalls)
        )
    }

    func testRotationFailureStopsWithStorageOrErrorReason() {
        XCTAssertEqual(
            decide(.rotationFailed(lowStorage: true), .recording),
            RecorderDecision(stopReason: .lowStorage, pause: true, alert: .cannotContinue(.lowStorage))
        )
        XCTAssertEqual(
            decide(.rotationFailed(lowStorage: false), .recording),
            RecorderDecision(stopReason: .error, pause: true, alert: .cannotContinue(.error))
        )
    }

    func testOnlySelfClearingConditionsResumeAutomatically() {
        let automatic = ChunkEndReason.allCases.filter(RecorderPolicy.resumesAutomatically(after:))
        XCTAssertEqual(automatic, [.charging, .lowBattery, .lowStorage])
    }

    func testSilenceDetection() {
        // One rule on both watches (#137): a sample is sound when its peak reaches -40 dBFS.
        XCTAssertTrue(RecorderPolicy.isSound(peakPowerDb: -40), "the threshold itself is sound")
        XCTAssertTrue(RecorderPolicy.isSound(peakPowerDb: -20))
        XCTAssertFalse(RecorderPolicy.isSound(peakPowerDb: -40.5))
        XCTAssertFalse(RecorderPolicy.isSound(peakPowerDb: -160), "metering floor")

        // 5 minutes at one sample per second: 240 samples (80 %) are enough to call it silent.
        XCTAssertTrue(RecorderPolicy.isSilent(heardSound: false, meterSamples: 240, durationMs: 300_000, tickSeconds: 1))
        XCTAssertFalse(RecorderPolicy.isSilent(heardSound: false, meterSamples: 200, durationMs: 300_000, tickSeconds: 1),
                       "metering gaps must not make speech disappear")
        XCTAssertFalse(RecorderPolicy.isSilent(heardSound: true, meterSamples: 300, durationMs: 300_000, tickSeconds: 1))
    }

    func testMarkOffsetIsMeasuredFromTheChunkStart() {
        let start = Date(timeIntervalSince1970: 1_791_622_800)
        XCTAssertEqual(RecorderPolicy.markOffsetMs(at: start.addingTimeInterval(12.5), chunkStartedAt: start), 12_500)
        XCTAssertEqual(RecorderPolicy.markOffsetMs(at: start.addingTimeInterval(-1), chunkStartedAt: start), 0, "never negative")
    }

    /// A mark no longer closes the chunk (#137, as on Wear OS): no event ends a chunk with
    /// `.mark`; marks travel as `markOffsetsMs` of the chunk that was recording.
    func testNoDecisionClosesAChunkForAMark() throws {
        var lowBattery = healthy
        lowBattery.batteryLevel = 0.05
        let events: [RecorderEvent] = [
            .userStop, .statusCheck(healthy), .statusCheck(lowBattery), .retryCheck(healthy),
            .interruptionBegan, .interruptionEnded, .recorderStalled(restartsSoFar: 0),
            .recorderStalled(restartsSoFar: RecorderPolicy.maxAutoRestarts),
            .rotationFailed(lowStorage: true), .rotationFailed(lowStorage: false),
        ]
        let phases: [RecorderPhase] = [.idle, .recording] + ChunkEndReason.allCases.map { RecorderPhase.paused($0) }
        for event in events {
            for phase in phases {
                XCTAssertNotEqual(decide(event, phase).stopReason, ChunkEndReason.mark, "\(event) in \(phase)")
            }
        }

        // Marks tapped during a chunk end up in its manifest, which closes as a regular rotation.
        let root = FileManager.default.temporaryDirectory.appending(path: "marks-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let outbox = ChunkOutbox(root: root)
        let start = Date(timeIntervalSince1970: 1_791_622_800)
        var chunk = InProgressChunk(
            chunkId: "marked",
            sessionId: "session",
            startedAtEpochMs: Int64(start.timeIntervalSince1970 * 1000),
            timeZone: "Asia/Tokyo",
            markOffsetsMs: []
        )
        for seconds in [12.0, 240.5] {
            chunk.markOffsetsMs.append(RecorderPolicy.markOffsetMs(at: start.addingTimeInterval(seconds), chunkStartedAt: start))
            outbox.saveInProgress(chunk)
        }
        try Data([1, 2, 3, 4]).write(to: outbox.recordingAudioURL(chunk.chunkId))
        let item = try outbox.commit(chunk, deviceId: "watch", durationMs: 300_000, endReason: .rotation, keepAudio: true)
        XCTAssertEqual(item.manifest.markOffsetsMs, [12_000, 240_500])
        XCTAssertEqual(item.manifest.endReason, .rotation)
    }

    private struct StopCase {
        let event: RecorderEvent
        let phase: RecorderPhase
        let expected: ChunkEndReason
    }

    /// Charging, low battery, low storage, interruption and user stop each close the chunk with
    /// their own end reason, which the outbox writes to the manifest the iPhone receives.
    func testStopReasonsAreRecordedAsTheManifestEndReason() throws {
        var charging = healthy
        charging.charging = true
        var lowBattery = healthy
        lowBattery.batteryLevel = 0.05
        var lowStorage = healthy
        lowStorage.freeBytes = 10 * megabyte
        let cases = [
            StopCase(event: .statusCheck(charging), phase: .recording, expected: .charging),
            StopCase(event: .statusCheck(lowBattery), phase: .recording, expected: .lowBattery),
            StopCase(event: .statusCheck(lowStorage), phase: .recording, expected: .lowStorage),
            StopCase(event: .interruptionBegan, phase: .recording, expected: .interruption),
            StopCase(event: .userStop, phase: .recording, expected: .userStop),
            StopCase(event: .recorderStalled(restartsSoFar: 0), phase: .recording, expected: .error),
        ]

        let root = FileManager.default.temporaryDirectory.appending(path: "policy-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let outbox = ChunkOutbox(root: root)
        for (index, stop) in cases.enumerated() {
            let reason = try XCTUnwrap(RecorderPolicy.decide(stop.event, phase: stop.phase, wantsRecording: true).stopReason)
            XCTAssertEqual(reason, stop.expected)
            XCTAssertTrue(reason.startsGap, "\(reason) must show as a gap on the timeline")
            let chunk = InProgressChunk(
                chunkId: "chunk-\(index)",
                sessionId: "session",
                startedAtEpochMs: 1_791_622_800_000 + Int64(index) * 300_000,
                timeZone: "Asia/Tokyo",
                markOffsetsMs: []
            )
            outbox.saveInProgress(chunk)
            try Data([1, 2, 3, 4]).write(to: outbox.recordingAudioURL(chunk.chunkId))
            _ = try outbox.commit(chunk, deviceId: "watch", durationMs: 60_000, endReason: reason, keepAudio: true)
        }

        // Read back from disk as a relaunched app (or the transfer) would.
        let pending = ChunkOutbox(root: root).pendingItems()
        XCTAssertEqual(pending.map(\.manifest.endReason), cases.map(\.expected))
        for item in pending {
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(item.manifestJSON.utf8)) as? [String: Any])
            XCTAssertEqual(json["endReason"] as? String, item.manifest.endReason.rawValue)
            XCTAssertEqual(json["source"] as? String, "watchos")
        }
    }
}
