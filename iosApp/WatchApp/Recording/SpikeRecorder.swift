import AVFoundation
import Foundation
import Observation
import WatchKit

/// Background recorder for the M0-3 spike (#9).
///
/// Records 16 kHz mono AAC, rotates to a new file every 5 minutes, logs battery every
/// 5 minutes and (optionally) sends closed chunks to the iPhone. Interruptions (calls,
/// Siri) are logged together with whether resuming worked.
@MainActor
@Observable
final class SpikeRecorder {
    struct Stats {
        var startedAt: Date?
        var batteryAtStart: Int?
        var batteryNow: Int?
        var chunksClosed = 0
        var chunksSent = 0
        var bytesRecorded: Int64 = 0
        var marks = 0
        var lastEvent = ""

        /// %/hour since start, once at least 15 minutes have passed.
        func drainPerHour(at now: Date) -> Double? {
            guard let startedAt, let batteryAtStart, let batteryNow else { return nil }
            let hours = now.timeIntervalSince(startedAt) / 3600
            return hours < 0.25 ? nil : Double(batteryAtStart - batteryNow) / hours
        }
    }

    static let bitrate = 32_000
    static let chunkDuration: Duration = .seconds(300)
    static let batteryLogInterval: Duration = .seconds(300)

    private(set) var isRecording = false
    private(set) var stats = Stats()
    var sendChunks = true

    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var currentURL: URL?
    @ObservationIgnored private var chunkIndex = 0
    @ObservationIgnored private var sessionDirectory: URL?
    @ObservationIgnored private var log: SpikeLog?
    @ObservationIgnored private var loops: [Task<Void, Never>] = []
    @ObservationIgnored private var interruptionObserver: NSObjectProtocol?
    @ObservationIgnored private lazy var sender = ChunkSender { [weak self] name, error in
        Task { @MainActor in self?.transferFinished(name: name, error: error) }
    }

    func start() async {
        guard !isRecording else { return }
        guard await AVAudioApplication.requestRecordPermission() else {
            stats.lastEvent = "microphone permission denied"
            return
        }
        WKInterfaceDevice.current().isBatteryMonitoringEnabled = true
        _ = sender // activate WCSession early

        let sessionID = Date.now.ISO8601Format().replacingOccurrences(of: ":", with: "")
        let directory = URL.documentsDirectory.appending(path: "spike/\(sessionID)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        sessionDirectory = directory
        log = SpikeLog(directory: directory)
        chunkIndex = 0

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)
            try startChunk()
        } catch {
            stats.lastEvent = "start failed: \(error.localizedDescription)"
            return
        }

        let battery = batteryPercent()
        stats = Stats(startedAt: .now, batteryAtStart: battery, batteryNow: battery)
        isRecording = true
        observeInterruptions()
        record("start", "sendChunks=\(sendChunks) bitrate=\(Self.bitrate)")

        loops = [
            repeating(every: Self.chunkDuration) { $0.rotate() },
            repeating(every: Self.batteryLogInterval) { recorder in
                recorder.stats.batteryNow = recorder.batteryPercent()
                recorder.record("battery")
            },
        ]
    }

    func mark() {
        guard isRecording else { return }
        stats.marks += 1
        WKInterfaceDevice.current().play(.click)
        record("mark")
        rotate() // Marks close the current chunk so the moment is transferred promptly.
    }

    func stop(reason: String = "user") {
        guard isRecording else { return }
        loops.forEach { $0.cancel() }
        loops = []
        recorder?.stop()
        recorder = nil
        if let url = currentURL { chunkClosed(url) }
        currentURL = nil
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        interruptionObserver = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        isRecording = false
        stats.batteryNow = batteryPercent()
        record("stop", reason)
        if let log { sender.send(log.url, kind: "log") }
    }

    // MARK: - Chunks

    private func startChunk() throws {
        guard let sessionDirectory else { return }
        let url = sessionDirectory.appending(path: String(format: "chunk-%04d.m4a", chunkIndex))
        chunkIndex += 1
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: Self.bitrate,
        ]
        let next = try AVAudioRecorder(url: url, settings: settings)
        guard next.record() else { throw RecorderError.recordFailed }
        recorder = next
        currentURL = url
    }

    /// Starts the next file before stopping the current one so no audio is lost; falls back
    /// to stop-then-start if two recorders cannot run at once (logged for the spike report).
    private func rotate() {
        guard isRecording, let old = recorder, let oldURL = currentURL else { return }
        do {
            try startChunk()
            old.stop()
        } catch {
            old.stop()
            do {
                try startChunk()
                record("rotate_fallback", "overlap failed: \(error)")
            } catch {
                record("rotate_failed", "\(error)")
                stop(reason: "rotate_failed")
                return
            }
        }
        chunkClosed(oldURL)
    }

    private func chunkClosed(_ url: URL) {
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path())[.size] as? Int64) ?? 0
        stats.chunksClosed += 1
        stats.bytesRecorded += size
        record("chunk_closed", url.lastPathComponent)
        if sendChunks { sender.send(url, kind: "chunk") }
    }

    private func transferFinished(name: String, error: String?) {
        if let error {
            record("send_failed", "\(name): \(error)")
        } else if name.hasSuffix(".m4a") {
            stats.chunksSent += 1
            record("chunk_sent", name)
        }
    }

    // MARK: - Interruptions

    private func observeInterruptions() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let rawType = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            MainActor.assumeIsolated { self?.handleInterruption(rawType: rawType) }
        }
    }

    private func handleInterruption(rawType: UInt?) {
        switch rawType.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) {
        case .began:
            record("interruption_began")
        case .ended:
            let resumed = (try? AVAudioSession.sharedInstance().setActive(true)) != nil && (recorder?.record() ?? false)
            record("interruption_ended", "resumed=\(resumed)")
            if !resumed {
                WKInterfaceDevice.current().play(.failure)
                stop(reason: "interrupted")
            }
        default:
            break
        }
    }

    // MARK: - Helpers

    private func repeating(every interval: Duration, _ action: @escaping @MainActor (SpikeRecorder) -> Void) -> Task<Void, Never> {
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard !Task.isCancelled, let self else { return }
                action(self)
            }
        }
    }

    private func record(_ event: String, _ detail: String = "") {
        stats.lastEvent = event
        log?.append(stats, charging: isCharging(), event: event, detail: detail)
    }

    private func batteryPercent() -> Int {
        Int((WKInterfaceDevice.current().batteryLevel * 100).rounded())
    }

    private func isCharging() -> Bool {
        let state = WKInterfaceDevice.current().batteryState
        return state == .charging || state == .full
    }

    private enum RecorderError: Error {
        case recordFailed
    }
}
