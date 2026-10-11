import SwiftUI
import WidgetKit

/// Timeline entry: the recorder status the watch app last wrote to the App Group.
struct RecorderStatusEntry: TimelineEntry {
    let date: Date
    let status: RecorderStatus
}

/// Reads the shared status. The watch app reloads the timeline whenever the recorder state
/// changes, so a single entry that never expires is enough; the elapsed time is drawn with a
/// live timer text.
struct RecorderStatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecorderStatusEntry {
        RecorderStatusEntry(date: .now, status: .recording(since: Date.now.addingTimeInterval(-754)))
    }

    func getSnapshot(in context: Context, completion: @escaping (RecorderStatusEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : currentEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RecorderStatusEntry>) -> Void) {
        completion(Timeline(entries: [currentEntry()], policy: .never))
    }

    private func currentEntry() -> RecorderStatusEntry {
        RecorderStatusEntry(date: .now, status: RecorderStatusStore.load(from: RecorderStatusStore.sharedDefaults()))
    }
}

struct RecorderStatusWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: RecorderStatusEntry

    var body: some View {
        let display = ComplicationDisplay(entry.status)
        content(display)
            .widgetURL(display.url)
            .containerBackground(for: .widget) { Color.clear }
    }

    @ViewBuilder
    private func content(_ display: ComplicationDisplay) -> some View {
        switch family {
        case .accessoryRectangular:
            ComplicationRectangularView(display: display)
        case .accessoryInline:
            ComplicationInlineView(display: display)
        case .accessoryCorner:
            Image(systemName: display.symbolName)
                .font(.title2.weight(.semibold))
                .foregroundStyle(display.tone.color)
                .widgetAccentable()
                .widgetLabel {
                    // Corner labels only render Text / Gauge / ProgressView.
                    if let since = display.recordingSince {
                        Text(since, style: .timer)
                    } else {
                        Text(display.title)
                    }
                }
        default:
            ComplicationCircularView(display: display)
        }
    }
}

/// Watch face complication / Smart Stack widget: recording state, tap to start or resume (#41).
struct RecorderStatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: RecorderStatusStore.widgetKind, provider: RecorderStatusProvider()) { entry in
            RecorderStatusWidgetView(entry: entry)
        }
        .configurationDisplayName("録音状態")
        .description("録音中・停止中を表示し、タップで録音を開始・再開します。")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

@main
struct WatchAudioWidgets: WidgetBundle {
    var body: some Widget {
        RecorderStatusWidget()
    }
}
