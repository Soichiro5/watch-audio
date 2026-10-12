import Foundation

/// What the recorder is doing right now.
enum RecorderPhase: Equatable, Sendable {
    case idle
    case recording
    /// Stopped for a reason (charging, low battery, interruption, ...) while the wearer still
    /// wants to record: resumed automatically when the condition clears.
    case paused(ChunkEndReason)
}

/// Readings of the watch the recorder decides on.
struct DeviceConditions: Equatable, Sendable {
    var charging: Bool = false
    /// 0...1, or nil when unknown (`WKInterfaceDevice` reports -1 then).
    var batteryLevel: Float?
    /// Free bytes on the data volume, or nil when unknown.
    var freeBytes: Int64?
}

/// Something that happened to the recorder; [RecorderPolicy.decide] says what to do about it.
enum RecorderEvent: Equatable, Sendable {
    /// The wearer tapped Stop.
    case userStop
    /// Periodic device check while recording.
    case statusCheck(DeviceConditions)
    /// Periodic retry while paused.
    case retryCheck(DeviceConditions)
    /// A phone call, Siri or an alarm took the microphone.
    case interruptionBegan
    case interruptionEnded
    /// The recorder stopped without an interruption notification (e.g. media services reset).
    /// `restartsSoFar` automatic restarts already happened since recording was last healthy.
    case recorderStalled(restartsSoFar: Int)
    /// Neither the next chunk nor a fallback recorder could be started while rotating.
    case rotationFailed(lowStorage: Bool)
}

/// Haptic + notification for the wearer: recording stopped for a reason they may not expect.
enum RecorderAlert: Equatable, Sendable {
    case lowBattery
    case lowStorage
    /// No recorder could be started; recording stopped with this end reason.
    case cannotContinue(ChunkEndReason)
    /// The recorder kept stopping by itself and the automatic restarts are used up.
    case repeatedStalls
    /// Scheduled reminder that fires if recording has not resumed after an interruption.
    case interruptedReminder
}

/// What the controller should do in response to a [RecorderEvent].
struct RecorderDecision: Equatable, Sendable {
    /// Close the current chunk with this end reason (written to its manifest); nil keeps going.
    var stopReason: ChunkEndReason?
    /// After stopping, stay armed in `.paused(stopReason)` instead of becoming idle.
    var pause = false
    /// Try to start recording again (after stopping, if `stopReason` is set).
    var resume = false
    var alert: RecorderAlert?

    static let noAction = RecorderDecision()
}

/// Pure decisions of the watchOS recorder (#15): when to stop, with which end reason, when to
/// resume and when to alert. Free of WatchKit and AVFoundation so it can be unit tested;
/// `RecorderController` feeds it device readings and recorder events and applies the result.
/// Counterpart of the Wear OS `RecorderPolicy`.
enum RecorderPolicy {
    /// Stop below this battery percentage so the last chunk can still be finalized and sent.
    /// Shared with Wear OS through `RecorderRules` (#137).
    static let lowBatteryPercent = RecorderRules.current.lowBatteryStopPercent
    /// Start or resume only at or above this percentage, so a reading that hovers around
    /// [lowBatteryPercent] does not create a stream of tiny chunks.
    static let resumeBatteryPercent = RecorderRules.current.lowBatteryResumePercent
    static let minFreeBytes: Int64 = 100 * 1024 * 1024
    /// dBFS. A metering sample counts as "sound" if its peak power is at or above this, the same
    /// rule as the Wear OS `SilenceDetector` (#137).
    static let silencePeakThresholdDb = Float(RecorderRules.current.silencePeakDbfs)
    /// Only drop a chunk's audio as silent if metering covered at least this share of it.
    static let minMeterCoverage = Double(RecorderRules.current.silenceMinCoveragePercent) / 100
    /// Ticks in a row in which the recorder unexpectedly was not recording before it counts as stalled.
    static let stalledTicksBeforeRestart = 5
    static let maxAutoRestarts = 3

    /// Battery level (0...1) as a whole percentage, as shown to the wearer and compared with the
    /// thresholds (Wear OS reports whole percentages too).
    static func batteryPercent(_ level: Float) -> Int {
        Int((level * 100).rounded())
    }

    /// Why recording cannot start (or resume) right now, if anything.
    static func startBlocker(_ device: DeviceConditions) -> ChunkEndReason? {
        if device.charging { return .charging }
        if let level = device.batteryLevel, batteryPercent(level) < resumeBatteryPercent { return .lowBattery }
        if let free = device.freeBytes, free < minFreeBytes { return .lowStorage }
        return nil
    }

    /// Checked periodically while recording: the reason to stop now, if any.
    static func stopReason(_ device: DeviceConditions) -> ChunkEndReason? {
        if device.charging { return .charging }
        if let level = device.batteryLevel, batteryPercent(level) < lowBatteryPercent { return .lowBattery }
        if let free = device.freeBytes, free < minFreeBytes { return .lowStorage }
        return nil
    }

    /// Conditions that clear by themselves: retried while the app is alive. Interruptions are
    /// resumed on their end notification (or when the app is opened), errors by restarts.
    static func resumesAutomatically(after reason: ChunkEndReason) -> Bool {
        reason == .charging || reason == .lowBattery || reason == .lowStorage
    }

    static func decide(_ event: RecorderEvent, phase: RecorderPhase, wantsRecording: Bool) -> RecorderDecision {
        switch event {
        case .userStop:
            return RecorderDecision(stopReason: .userStop)
        case .statusCheck(let device):
            guard phase == .recording, let reason = stopReason(device) else { return .noAction }
            // Charging is an expected gap (resumes when unplugged): no alert.
            let alert: RecorderAlert? = switch reason {
            case .lowBattery: .lowBattery
            case .lowStorage: .lowStorage
            default: nil
            }
            return RecorderDecision(stopReason: reason, pause: true, alert: alert)
        case .retryCheck(let device):
            guard case .paused(let reason) = phase, wantsRecording, resumesAutomatically(after: reason),
                  startBlocker(device) == nil
            else { return .noAction }
            return RecorderDecision(resume: true)
        case .interruptionBegan:
            guard phase == .recording else { return .noAction }
            // Close the chunk now so the timeline shows the gap and its reason.
            return RecorderDecision(stopReason: .interruption, pause: true, alert: .interruptedReminder)
        case .interruptionEnded:
            // Try even without `shouldResume`; on failure the wearer gets a haptic + notification.
            guard phase == .paused(.interruption), wantsRecording else { return .noAction }
            return RecorderDecision(resume: true)
        case .recorderStalled(let restartsSoFar):
            guard phase == .recording else { return .noAction }
            if restartsSoFar < maxAutoRestarts {
                return RecorderDecision(stopReason: .error, pause: true, resume: true)
            }
            return RecorderDecision(stopReason: .error, pause: true, alert: .repeatedStalls)
        case .rotationFailed(let lowStorage):
            let reason: ChunkEndReason = lowStorage ? .lowStorage : .error
            return RecorderDecision(stopReason: reason, pause: true, alert: .cannotContinue(reason))
        }
    }

    /// One metering sample: whether its peak power (dBFS) reaches the silence threshold.
    static func isSound(peakPowerDb: Float) -> Bool {
        peakPowerDb >= silencePeakThresholdDb
    }

    /// A chunk is sent without audio only if no sample was sound and metering covered enough of
    /// it (a recorder that stopped metering must not make speech disappear).
    static func isSilent(heardSound: Bool, meterSamples: Int, durationMs: Int64, tickSeconds: Double) -> Bool {
        let expectedSamples = Double(durationMs) / 1000 / tickSeconds
        return !heardSound && Double(meterSamples) >= expectedSamples * minMeterCoverage
    }

    /// Offset of a mark tapped at [date] in the chunk that started at [chunkStartedAt] (never
    /// negative). A mark is only recorded in the current chunk's `markOffsetsMs`; the chunk keeps
    /// recording and closes at its regular rotation, as on Wear OS (#137). No decision closes a
    /// chunk with `ChunkEndReason.mark` any more.
    static func markOffsetMs(at date: Date, chunkStartedAt: Date) -> Int64 {
        max(0, Int64(date.timeIntervalSince(chunkStartedAt) * 1000))
    }
}
