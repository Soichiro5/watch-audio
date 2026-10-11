import Foundation

/// Capped, local-only CSV of recorder events and battery level (successor of the M0-3 spike log).
/// Stays on the watch; useful when checking battery drain, interruptions and transfer problems.
struct DiagnosticsLog: Sendable {
    /// When the file grows past this size, only the newest half is kept.
    static let maxBytes = 512 * 1024
    private static let header = "timestamp,battery_pct,charging,event,detail\n"

    let url: URL

    init(directory: URL) {
        url = directory.appending(path: "diagnostics.csv")
    }

    func append(event: String, detail: String, batteryPercent: Int?, charging: Bool) {
        let fields = [
            Date.now.ISO8601Format(),
            batteryPercent.map(String.init) ?? "",
            charging ? "1" : "0",
            event,
            detail.replacingOccurrences(of: ",", with: ";").replacingOccurrences(of: "\n", with: " "),
        ]
        let line = Data((fields.joined(separator: ",") + "\n").utf8)
        if !FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) {
            try? Data(Self.header.utf8).write(to: url)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        let size = (try? handle.seekToEnd()) ?? 0
        try? handle.write(contentsOf: line)
        try? handle.close()
        if size > UInt64(Self.maxBytes) { trim() }
    }

    private func trim() {
        guard let data = try? Data(contentsOf: url) else { return }
        let tail = data.suffix(Self.maxBytes / 2)
        let start = tail.firstIndex(of: UInt8(ascii: "\n")).map { tail.index(after: $0) } ?? tail.startIndex
        var trimmed = Data(Self.header.utf8)
        trimmed.append(contentsOf: tail[start...])
        try? trimmed.write(to: url, options: .atomic)
    }
}
