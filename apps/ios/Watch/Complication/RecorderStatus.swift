import Foundation

// Recorder state shared between the watch app and its complication (widget extension, #41).
// Compiled into WatchApp, WatchWidget and ProtocolTests; depends only on Foundation and
// ChunkEndReason (SharedSwift/ChunkProtocol.swift).

/// What the complication shows: written by the watch app whenever the recorder state changes.
struct RecorderStatus: Codable, Equatable, Sendable {
    enum State: String, Codable, Sendable {
        case recording
        /// Stopped for a reason while the wearer still wants to record (resumes automatically).
        case paused
        case stopped
    }

    var state: State
    /// Why recording is paused (`state == .paused` only).
    var pausedReason: ChunkEndReason?
    /// When the current recording started (`state == .recording` only).
    var recordingSince: Date?

    static let stopped = RecorderStatus(state: .stopped)

    static func recording(since: Date?) -> RecorderStatus {
        RecorderStatus(state: .recording, recordingSince: since)
    }

    static func paused(_ reason: ChunkEndReason) -> RecorderStatus {
        RecorderStatus(state: .paused, pausedReason: reason)
    }
}

/// Persists `RecorderStatus` in the App Group's UserDefaults so the widget extension can read it.
///
/// The suite name comes from the `WatchAudioAppGroup` Info.plist key (`APP_GROUP_ID` in
/// Configuration/Config.xcconfig). Unsigned builds (CI) have no App Group entitlement; the suite
/// then only lives in the reading process's own container, which is enough for the simulator.
enum RecorderStatusStore {
    static let infoPlistKey = "WatchAudioAppGroup"
    static let statusKey = "recorderStatus"
    /// WidgetKit kind of the recorder complication.
    static let widgetKind = "RecorderStatusWidget"

    static var appGroupId: String? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: infoPlistKey) as? String,
              !id.isEmpty, !id.contains("$(") else { return nil }
        return id
    }

    static func sharedDefaults() -> UserDefaults {
        appGroupId.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }

    /// The last written status; `.stopped` if nothing (readable) was written yet.
    static func load(from defaults: UserDefaults) -> RecorderStatus {
        guard let data = defaults.data(forKey: statusKey),
              let status = try? JSONDecoder().decode(RecorderStatus.self, from: data) else {
            return .stopped
        }
        return status
    }

    static func save(_ status: RecorderStatus, to defaults: UserDefaults) {
        guard let data = try? JSONEncoder().encode(status) else { return }
        defaults.set(data, forKey: statusKey)
    }
}

/// Deep link opened by tapping the complication: opens the app and starts or resumes recording.
enum ComplicationLink {
    static let scheme = "watchaudio"
    static let record = URL(string: "watchaudio://record")!

    static func isRecordRequest(_ url: URL) -> Bool {
        url.scheme?.lowercased() == scheme && url.host()?.lowercased() == "record"
    }
}

/// Labels for recorder states (user-facing, Japanese).
enum RecorderStatusText {
    /// Long form for the app screen.
    static func pausedLabel(_ reason: ChunkEndReason) -> String {
        switch reason {
        case .charging: "充電中（外すと再開）"
        case .lowBattery: "電池残量不足"
        case .lowStorage: "空き容量不足"
        case .interruption: "中断中（通話・Siri など）"
        default: "停止中（\(reason.rawValue)）"
        }
    }

    /// Short form for the complication.
    static func shortPausedLabel(_ reason: ChunkEndReason?) -> String {
        guard let reason else { return "一時停止" }
        switch reason {
        case .charging: return "充電中"
        case .lowBattery: return "電池不足"
        case .lowStorage: return "容量不足"
        case .interruption: return "通話等で中断"
        case .error: return "エラー"
        default: return reason.rawValue
        }
    }
}

/// Pure mapping from `RecorderStatus` to what the complication draws (tested in ProtocolTests).
struct ComplicationDisplay: Equatable, Sendable {
    enum Tone: Equatable, Sendable {
        case recording
        case paused
        case stopped
    }

    let tone: Tone
    /// SF Symbol name.
    let symbolName: String
    /// One word for the state: 録音中 / 一時停止 / 停止中.
    let title: String
    /// Second line: the pause reason, or what a tap does.
    let detail: String
    /// Start of the running recording; the views show the elapsed time live.
    let recordingSince: Date?
    /// Single line for the inline family.
    let inlineText: String
    /// Opened on tap (start or resume; opening the app is enough while recording).
    let url: URL

    init(_ status: RecorderStatus) {
        url = ComplicationLink.record
        switch status.state {
        case .recording:
            tone = .recording
            symbolName = "record.circle"
            title = "録音中"
            detail = "経過"
            recordingSince = status.recordingSince
            inlineText = "録音中"
        case .paused:
            let reason = RecorderStatusText.shortPausedLabel(status.pausedReason)
            tone = .paused
            symbolName = "pause.circle"
            title = "一時停止"
            detail = "\(reason)・タップで再開"
            recordingSince = nil
            inlineText = "一時停止（\(reason)）"
        case .stopped:
            tone = .stopped
            symbolName = "mic.slash"
            title = "停止中"
            detail = "タップで録音開始"
            recordingSince = nil
            inlineText = "録音停止中"
        }
    }
}
