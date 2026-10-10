import Foundation

/// Append-only CSV for the M0-3 battery spike (#9). Sent to the iPhone when recording stops.
struct SpikeLog {
    let url: URL

    init(directory: URL) {
        url = directory.appending(path: "log.csv")
        if !FileManager.default.fileExists(atPath: url.path()) {
            try? Data("timestamp,elapsed_min,battery_pct,charging,chunks_closed,chunks_sent,bytes,event,detail\n".utf8).write(to: url)
        }
    }

    func append(_ state: SpikeRecorder.Stats, charging: Bool, event: String, detail: String = "") {
        let elapsed = state.startedAt.map { Date.now.timeIntervalSince($0) / 60 } ?? 0
        let fields = [
            Date.now.ISO8601Format(),
            String(format: "%.1f", elapsed),
            state.batteryNow.map(String.init) ?? "",
            String(charging),
            String(state.chunksClosed),
            String(state.chunksSent),
            String(state.bytesRecorded),
            event,
            detail.replacingOccurrences(of: ",", with: ";"),
        ]
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data((fields.joined(separator: ",") + "\n").utf8))
    }
}
