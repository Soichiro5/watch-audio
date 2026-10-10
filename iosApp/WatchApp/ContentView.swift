import SwiftUI

/// Recording controls and transfer status.
struct ContentView: View {
    let recorder: RecorderController

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    header(now: context.date)
                    controls
                    status
                    #if DEBUG
                    debugAudioInput
                    #endif
                }
            }
        }
    }

    @ViewBuilder
    private func header(now: Date) -> some View {
        switch recorder.phase {
        case .recording:
            let minutes = Int(now.timeIntervalSince(recorder.recordingSince ?? now) / 60)
            Text("● REC \(minutes / 60)h \(minutes % 60)m").foregroundStyle(.red)
        case .paused(let reason):
            Text(Self.pausedLabel(reason)).foregroundStyle(.orange)
        case .idle:
            Text("watch-audio")
        }
    }

    @ViewBuilder
    private var controls: some View {
        switch recorder.phase {
        case .recording:
            HStack {
                Button("Mark") { recorder.mark() }
                Button("停止", role: .destructive) { recorder.stop() }
            }
        case .paused:
            Button("再開") { Task { await recorder.start() } }
            Button("停止", role: .destructive) { recorder.stop() }
        case .idle:
            Button("録音開始") { Task { await recorder.start() } }
        }
    }

    private var status: some View {
        VStack(alignment: .leading) {
            Text("未送信 \(recorder.pendingCount)・受領 \(recorder.chunksAcked)")
            Text("チャンク \(recorder.chunksClosed)（無音 \(recorder.silentChunks)）Mark \(recorder.marks)")
            Text("電池 \(recorder.batteryPercent.map { "\($0)%" } ?? "-")")
            Text(recorder.lastEvent).lineLimit(2)
        }
        .font(.footnote)
    }

    #if DEBUG
    /// Debug builds only: record a file from `Documents/debug-input` instead of the microphone.
    @ViewBuilder
    private var debugAudioInput: some View {
        if let playback = DebugAudioInput.playback {
            Text("ファイル入力: \(playback.url.lastPathComponent)").font(.footnote)
            Button("ファイル入力を止める") { recorder.stopDebugAudioInput() }
        } else {
            ForEach(DebugAudioInput.availableFiles(), id: \.self) { url in
                Button("▶ \(url.lastPathComponent)") {
                    Task { await recorder.startDebugAudioInput(url, loop: false) }
                }
                .font(.footnote)
            }
        }
    }
    #endif

    private static func pausedLabel(_ reason: ChunkEndReason) -> String {
        switch reason {
        case .charging: "充電中（外すと再開）"
        case .lowBattery: "電池残量不足"
        case .lowStorage: "空き容量不足"
        case .interruption: "中断中（通話・Siri など）"
        default: "停止中（\(reason.rawValue)）"
        }
    }
}
