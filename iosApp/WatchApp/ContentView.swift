import SwiftUI

/// Minimal controls + live stats for the M0-3 battery spike (#9).
struct ContentView: View {
    @State private var recorder = SpikeRecorder()

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    header(now: context.date)
                    controls
                    stats(now: context.date)
                }
            }
        }
    }

    @ViewBuilder
    private func header(now: Date) -> some View {
        if recorder.isRecording, let startedAt = recorder.stats.startedAt {
            let minutes = Int(now.timeIntervalSince(startedAt) / 60)
            Text("● REC \(minutes / 60)h \(minutes % 60)m").foregroundStyle(.red)
        } else {
            Text("watch-audio spike")
        }
    }

    @ViewBuilder
    private var controls: some View {
        if recorder.isRecording {
            HStack {
                Button("Mark") { recorder.mark() }
                Button("Stop", role: .destructive) { recorder.stop() }
            }
        } else {
            Button("Start") { Task { await recorder.start() } }
            Toggle("Send to iPhone", isOn: $recorder.sendChunks)
        }
    }

    private func stats(now: Date) -> some View {
        let s = recorder.stats
        let drain = s.drainPerHour(at: now).map { String(format: "%.1f%%/h", $0) } ?? "-"
        return VStack(alignment: .leading) {
            Text("Battery \(s.batteryAtStart.map(String.init) ?? "-")% → \(s.batteryNow.map(String.init) ?? "-")% (\(drain))")
            Text("Chunks \(s.chunksClosed) / sent \(s.chunksSent) / marks \(s.marks)")
            Text("\(s.bytesRecorded / 1024) KB  \(s.lastEvent)")
        }
        .font(.footnote)
    }
}
