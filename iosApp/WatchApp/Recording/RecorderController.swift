import AVFoundation
import Foundation
import Observation
import UserNotifications
import WatchKit

/// The chunk being recorded right now (main actor only).
@MainActor
private final class ActiveChunk {
    var info: InProgressChunk
    let recorder: AVAudioRecorder
    let startedAt: Date
    /// Metering samples taken and whether any of them was above the silence threshold.
    var meterSamples = 0
    var heardSound = false
    /// Consecutive ticks in which the recorder unexpectedly was not recording.
    var stalledTicks = 0

    init(info: InProgressChunk, recorder: AVAudioRecorder, startedAt: Date) {
        self.info = info
        self.recorder = recorder
        self.startedAt = startedAt
    }
}

/// Production watchOS recorder (#15).
///
/// - Records AAC 16 kHz mono 24 kbps and rotates to a new file every 5 minutes. The next file is
///   started before the current one is stopped, so rotations overlap by a few milliseconds
///   instead of losing audio.
/// - Every chunk gets a ChunkManifest (UUID, start, duration, marks, SHA-256, end reason) and
///   goes into a persistent outbox until the iPhone sends a final ACK.
/// - Silent chunks: audio levels are sampled every second via metering; a chunk in which no
///   sample exceeded the threshold (and enough samples were taken) is sent as a manifest only.
/// - Stops with a reason on charging, low battery, low storage and interruptions, and resumes
///   automatically when possible (see `appBecameActive` / `tick`).
///
/// watchOS has no public wrist-detection API for third-party apps, so `off_body` is never
/// produced here (the system locks the watch when it is taken off; recording continues).
@MainActor
@Observable
final class RecorderController {
    enum Phase: Equatable {
        case idle
        case recording
        /// Stopped for a reason (charging, low battery, interruption, ...) while the wearer still
        /// wants to record: resumed automatically when the condition clears.
        case paused(ChunkEndReason)
    }

    enum Config {
        static let chunkDuration: TimeInterval = 300
        static let tickSeconds: Double = 1
        static let statusEveryTicks = 30
        static let batteryLogEveryTicks = 300
        /// dBFS. A tick counts as "sound" if the average or the peak power exceeds these.
        static let silenceAverageThresholdDb: Float = -50
        static let silencePeakThresholdDb: Float = -35
        /// Only drop audio if metering covered at least this share of the chunk.
        static let minMeterCoverage = 0.8
        static let lowBatteryLevel: Float = 0.10
        static let resumeBatteryLevel: Float = 0.15
        static let minFreeBytes: Int64 = 100 * 1024 * 1024
        /// Resend a chunk that was handed to WatchConnectivity but never ACKed after this long.
        static let resendAfter: TimeInterval = 15 * 60
        static let maxCorruptResends = 3
        static let maxAutoRestarts = 3
        /// If recording has not resumed this long after an interruption, a notification fires
        /// (scheduled up front because the app may be suspended by then).
        static let interruptedReminderDelay: TimeInterval = 120
        static let interruptedReminderId = "recording-interrupted"
    }

    private enum RecorderError: Error, Equatable {
        case recordFailed
        case lowStorage
    }

    private(set) var phase: Phase = .idle
    private(set) var recordingSince: Date?
    private(set) var pendingCount = 0
    private(set) var chunksClosed = 0
    private(set) var silentChunks = 0
    private(set) var chunksAcked = 0
    private(set) var marks = 0
    private(set) var batteryPercent: Int?
    private(set) var lastEvent = ""

    private let outbox: ChunkOutbox
    private let log: DiagnosticsLog
    private let deviceId: String
    @ObservationIgnored private var active: ActiveChunk?
    @ObservationIgnored private var sessionId = ""
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var tickCount = 0
    @ObservationIgnored private var autoRestarts = 0
    @ObservationIgnored private var interruptionObserver: NSObjectProtocol?
    @ObservationIgnored private var lastSent: [String: Date] = [:]
    @ObservationIgnored private var corruptResends: [String: Int] = [:]
    @ObservationIgnored private lazy var transport = ChunkTransport(
        onActivated: { [weak self] in
            Task { @MainActor in self?.transportActivated() }
        },
        onAck: { [weak self] ack in
            Task { @MainActor in self?.handleAck(ack) }
        },
        onTransferFinished: { [weak self] chunkId, error in
            Task { @MainActor in self?.transferFinished(chunkId: chunkId, error: error) }
        }
    )

    /// The wearer wants to be recording (persisted across launches): drives automatic resume.
    private var wantsRecording: Bool {
        get { UserDefaults.standard.bool(forKey: "wantsRecording") }
        set { UserDefaults.standard.set(newValue, forKey: "wantsRecording") }
    }

    init() {
        let outbox = ChunkOutbox()
        self.outbox = outbox
        log = DiagnosticsLog(directory: outbox.root)
        deviceId = DeviceIdentity.deviceId
        WKInterfaceDevice.current().isBatteryMonitoringEnabled = true
        let recovered = outbox.recoverAbandoned(deviceId: deviceId)
        if !recovered.isEmpty {
            record("recovered_terminated", "\(recovered.count)")
        }
        observeInterruptions()
        transport.activate()
        refreshPending()
    }

    // MARK: - Controls

    func start() async {
        guard phase != .recording else { return }
        wantsRecording = true
        autoRestarts = 0
        await startRecording(resuming: false)
    }

    func stop() {
        wantsRecording = false
        stopRecording(reason: .userStop, pause: false)
    }

    func mark() {
        guard phase == .recording, let active else { return }
        let offset = Int64(Date.now.timeIntervalSince(active.startedAt) * 1000)
        active.info.markOffsetsMs.append(max(0, offset))
        outbox.saveInProgress(active.info)
        marks += 1
        WKInterfaceDevice.current().play(.click)
        record("mark")
        // Close the chunk so the marked moment reaches the phone promptly.
        rotate(reason: .mark)
    }

    /// The app came to the foreground: resend what is pending and resume if the wearer wants to
    /// record (after charging, an interruption or the app being terminated).
    func appBecameActive() {
        refreshBattery()
        flushOutbox(force: false)
        guard wantsRecording, phase != .recording else { return }
        Task { await startRecording(resuming: true) }
    }

    // MARK: - Recording

    private func startRecording(resuming: Bool) async {
        guard phase != .recording else { return }
        guard await AVAudioApplication.requestRecordPermission() else {
            record("permission_denied")
            return
        }
        guard phase != .recording else { return } // another start won the race during the await
        refreshBattery()
        if let blocker = startBlocker() {
            phase = .paused(blocker)
            record("start_blocked", blocker.rawValue)
            startTicker() // keeps checking while the app is alive (e.g. waiting to be unplugged)
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)
            sessionId = UUID().uuidString
            active = try beginChunk()
        } catch {
            record("start_failed", "\(error)")
            if resuming {
                notifyStopped("録音を再開できませんでした。アプリを開いて開始してください。")
            }
            return
        }
        phase = .recording
        recordingSince = .now
        cancelInterruptedReminder()
        startTicker()
        requestNotificationPermission()
        record(resuming ? "resume" : "start", "device=\(deviceId)")
    }

    /// Stops recording and closes the current chunk with [reason]. With `pause`, the controller
    /// stays in `.paused(reason)` and resumes later; otherwise it becomes idle.
    private func stopRecording(reason: ChunkEndReason, pause: Bool) {
        ticker?.cancel()
        ticker = nil
        if let active {
            finish(active, reason: reason)
        }
        active = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        recordingSince = nil
        phase = pause ? .paused(reason) : .idle
        record("stop", reason.rawValue)
        if pause {
            startTicker()
        }
    }

    private func beginChunk() throws -> ActiveChunk {
        if let free = outbox.freeBytes(), free < Config.minFreeBytes {
            throw RecorderError.lowStorage
        }
        let chunkId = UUID().uuidString
        let url = outbox.recordingAudioURL(chunkId)
        let recorder = try AVAudioRecorder(url: url, settings: ChunkAudioFormat.recorderSettings)
        recorder.isMeteringEnabled = true
        guard recorder.record() else {
            try? FileManager.default.removeItem(at: url)
            throw RecorderError.recordFailed
        }
        let startedAt = Date.now
        let info = InProgressChunk(
            chunkId: chunkId,
            sessionId: sessionId,
            startedAtEpochMs: Int64(startedAt.timeIntervalSince1970 * 1000),
            timeZone: TimeZone.current.identifier,
            markOffsetsMs: []
        )
        outbox.saveInProgress(info)
        return ActiveChunk(info: info, recorder: recorder, startedAt: startedAt)
    }

    /// Starts the next file before stopping the current one so no audio is lost; falls back to
    /// stop-then-start if two recorders cannot run at once.
    private func rotate(reason: ChunkEndReason) {
        guard phase == .recording, let old = active else { return }
        do {
            active = try beginChunk()
            finish(old, reason: reason)
            autoRestarts = 0
        } catch {
            finish(old, reason: reason)
            active = nil
            do {
                active = try beginChunk()
                record("rotate_fallback", "\(error)")
            } catch {
                record("rotate_failed", "\(error)")
                let stopReason: ChunkEndReason = (error as? RecorderError) == .lowStorage ? .lowStorage : .error
                stopRecording(reason: stopReason, pause: true)
                notifyStopped("録音を続けられませんでした（\(stopReason.rawValue)）。")
            }
        }
    }

    private func finish(_ chunk: ActiveChunk, reason: ChunkEndReason) {
        chunk.recorder.stop()
        let durationMs = max(0, Int64(Date.now.timeIntervalSince(chunk.startedAt) * 1000))
        let expectedSamples = Double(durationMs) / 1000 / Config.tickSeconds
        let silent = !chunk.heardSound && Double(chunk.meterSamples) >= expectedSamples * Config.minMeterCoverage
        do {
            let item = try outbox.commit(
                chunk.info,
                deviceId: deviceId,
                durationMs: durationMs,
                endReason: reason,
                keepAudio: !silent
            )
            chunksClosed += 1
            if silent { silentChunks += 1 }
            record("chunk_closed", "\(reason.rawValue) silent=\(silent) bytes=\(item.manifest.audio?.sizeBytes ?? 0)")
            send(item)
        } catch {
            record("commit_failed", "\(error)")
        }
        refreshPending()
    }

    // MARK: - Periodic checks

    private func startTicker() {
        ticker?.cancel()
        tickCount = 0
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Config.tickSeconds))
                guard !Task.isCancelled, let self else { return }
                self.tick()
            }
        }
    }

    private func tick() {
        tickCount += 1
        switch phase {
        case .recording:
            guard let active else { return }
            meter(active)
            if Date.now.timeIntervalSince(active.startedAt) >= Config.chunkDuration {
                rotate(reason: .rotation)
            }
            if tickCount % Config.statusEveryTicks == 0 {
                checkDeviceStatus()
            }
            if tickCount % Config.batteryLogEveryTicks == 0 {
                record("battery")
            }
        case .paused(let reason):
            // Charging / battery / storage clear by themselves: retry while the app is alive.
            let autoResumable = reason == .charging || reason == .lowBattery || reason == .lowStorage
            if autoResumable, wantsRecording, tickCount % 10 == 0 {
                refreshBattery()
                if startBlocker() == nil {
                    Task { await startRecording(resuming: true) }
                }
            }
        case .idle:
            ticker?.cancel()
            ticker = nil
        }
        if tickCount % Config.statusEveryTicks == 0 {
            flushOutbox(force: false)
        }
    }

    private func meter(_ chunk: ActiveChunk) {
        let recorder = chunk.recorder
        guard recorder.isRecording else {
            chunk.stalledTicks += 1
            if chunk.stalledTicks >= 5 {
                recorderStalled()
            }
            return
        }
        chunk.stalledTicks = 0
        recorder.updateMeters()
        chunk.meterSamples += 1
        if recorder.averagePower(forChannel: 0) > Config.silenceAverageThresholdDb
            || recorder.peakPower(forChannel: 0) > Config.silencePeakThresholdDb {
            chunk.heardSound = true
        }
    }

    /// The recorder stopped without an interruption notification (e.g. media services reset).
    private func recorderStalled() {
        record("recorder_stalled")
        stopRecording(reason: .error, pause: true)
        autoRestarts += 1
        if autoRestarts <= Config.maxAutoRestarts {
            Task { await startRecording(resuming: true) }
        } else {
            notifyStopped("録音が繰り返し停止しました。アプリを開いて再開してください。")
        }
    }

    private func checkDeviceStatus() {
        refreshBattery()
        let device = WKInterfaceDevice.current()
        if isCharging {
            // Charging is an expected gap; resumes when unplugged (see tick / appBecameActive).
            stopRecording(reason: .charging, pause: true)
        } else if device.batteryLevel >= 0, device.batteryLevel < Config.lowBatteryLevel {
            stopRecording(reason: .lowBattery, pause: true)
            notifyStopped("電池残量が少ないため録音を止めました。")
        } else if let free = outbox.freeBytes(), free < Config.minFreeBytes {
            stopRecording(reason: .lowStorage, pause: true)
            notifyStopped("空き容量が少ないため録音を止めました。iPhone への転送を確認してください。")
        }
    }

    /// Why recording cannot start right now, if anything.
    private func startBlocker() -> ChunkEndReason? {
        let level = WKInterfaceDevice.current().batteryLevel
        if isCharging { return .charging }
        if level >= 0, level < Config.resumeBatteryLevel { return .lowBattery }
        if let free = outbox.freeBytes(), free < Config.minFreeBytes { return .lowStorage }
        return nil
    }

    // MARK: - Interruptions

    private func observeInterruptions() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let rawType = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let rawOptions = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            MainActor.assumeIsolated {
                self?.handleInterruption(rawType: rawType, rawOptions: rawOptions)
            }
        }
    }

    private func handleInterruption(rawType: UInt?, rawOptions: UInt?) {
        switch rawType.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) {
        case .began:
            guard phase == .recording else { return }
            // Close the chunk now so the timeline shows the gap and its reason.
            stopRecording(reason: .interruption, pause: true)
            scheduleInterruptedReminder()
        case .ended:
            guard phase == .paused(.interruption), wantsRecording else { return }
            let shouldResume = rawOptions.map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? false
            record("interruption_ended", "shouldResume=\(shouldResume)")
            // Try even without shouldResume; on failure the wearer gets a haptic + notification.
            Task { await startRecording(resuming: true) }
        default:
            break
        }
    }

    // MARK: - Transfer

    private func send(_ item: OutboxItem) {
        if transport.send(item) {
            lastSent[item.chunkId] = .now
        }
    }

    /// Hands every pending chunk that is not already queued to WatchConnectivity. Without
    /// `force`, a chunk is only re-sent after `Config.resendAfter` without an ACK.
    private func flushOutbox(force: Bool) {
        guard transport.isActivated else { return }
        let inFlight = transport.outstandingChunkIds()
        for item in outbox.pendingItems() where !inFlight.contains(item.chunkId) {
            if !force, let last = lastSent[item.chunkId], Date.now.timeIntervalSince(last) < Config.resendAfter {
                continue
            }
            send(item)
        }
        refreshPending()
    }

    private func transportActivated() {
        record("wc_activated")
        // New process or re-activation: anything not ACKed yet is sent again (the phone
        // answers duplicates with a final ACK).
        flushOutbox(force: true)
    }

    private func transferFinished(chunkId: String?, error: String?) {
        guard let chunkId else { return }
        if let error {
            lastSent[chunkId] = nil
            record("send_failed", "\(chunkId): \(error)")
        } else {
            record("sent", chunkId)
        }
    }

    private func handleAck(_ ack: ChunkAck) {
        if ack.status.isFinal {
            outbox.remove(chunkId: ack.chunkId)
            lastSent[ack.chunkId] = nil
            corruptResends[ack.chunkId] = nil
            chunksAcked += 1
        } else if ack.status == .corrupt {
            let attempts = (corruptResends[ack.chunkId] ?? 0) + 1
            corruptResends[ack.chunkId] = attempts
            if attempts <= Config.maxCorruptResends, let item = outbox.item(chunkId: ack.chunkId) {
                send(item)
            }
        }
        // .rejected: keep the chunk; it is offered again on the next activation.
        record("ack", "\(ack.chunkId) \(ack.status.rawValue) \(ack.detail ?? "")")
        refreshPending()
    }

    // MARK: - Helpers

    private var isCharging: Bool {
        let state = WKInterfaceDevice.current().batteryState
        return state == .charging || state == .full
    }

    private func refreshBattery() {
        let level = WKInterfaceDevice.current().batteryLevel
        batteryPercent = level >= 0 ? Int((level * 100).rounded()) : nil
    }

    private func refreshPending() {
        pendingCount = outbox.pendingItems().count
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Haptic + local notification when recording stopped and could not resume by itself.
    private func notifyStopped(_ body: String) {
        WKInterfaceDevice.current().play(.failure)
        let content = UNMutableNotificationContent()
        content.title = "録音が止まりました"
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: "recording-stopped", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
        record("notified", body)
    }

    private func scheduleInterruptedReminder() {
        let content = UNMutableNotificationContent()
        content.title = "録音が中断されました"
        content.body = "通話や Siri の後、録音を再開できていません。アプリを開くと再開します。"
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: Config.interruptedReminderDelay, repeats: false)
        let request = UNNotificationRequest(identifier: Config.interruptedReminderId, content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    private func cancelInterruptedReminder() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Config.interruptedReminderId])
    }

    private func record(_ event: String, _ detail: String = "") {
        lastEvent = detail.isEmpty ? event : "\(event): \(detail)"
        let level = WKInterfaceDevice.current().batteryLevel
        log.append(
            event: event,
            detail: detail,
            batteryPercent: level >= 0 ? Int((level * 100).rounded()) : nil,
            charging: isCharging
        )
    }
}

/// Stable per-install identifier of this watch (no hardware identifiers are used).
enum DeviceIdentity {
    private static let key = "deviceId"

    static var deviceId: String {
        if let existing = UserDefaults.standard.string(forKey: key) {
            return existing
        }
        let id = "watchos-" + UUID().uuidString.lowercased()
        UserDefaults.standard.set(id, forKey: key)
        return id
    }
}
