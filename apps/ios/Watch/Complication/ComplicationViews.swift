import SwiftUI
import WidgetKit

// Complication layouts for `ComplicationDisplay` (#41). Shared by the widget extension and the
// preview-rendering test (ProtocolTests renders them to PNG on the iOS simulator), so nothing
// here refers to watchOS-only widget families.

extension ComplicationDisplay.Tone {
    var color: Color {
        switch self {
        case .recording: .red
        case .paused: .orange
        case .stopped: .gray
        }
    }
}

/// Elapsed time of the running recording (updates live in a widget), or `fallback`.
struct ComplicationElapsedText: View {
    let display: ComplicationDisplay
    let fallback: String

    var body: some View {
        if let since = display.recordingSince {
            Text(since, style: .timer)
                .monospacedDigit()
        } else {
            Text(fallback)
        }
    }
}

/// `.accessoryCircular`: state symbol over a short label.
struct ComplicationCircularView: View {
    let display: ComplicationDisplay

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 1) {
                Image(systemName: display.symbolName)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(display.tone.color)
                    .widgetAccentable()
                Text(display.title)
                    .font(.system(size: 10, weight: .semibold))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
            }
            .padding(4)
        }
    }
}

/// `.accessoryRectangular`: symbol + state on the first line, reason / elapsed time / hint below.
struct ComplicationRectangularView: View {
    let display: ComplicationDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Image(systemName: display.symbolName)
                    .foregroundStyle(display.tone.color)
                    .widgetAccentable()
                Text(display.title)
                    .font(.headline)
                    .widgetAccentable()
            }
            if display.recordingSince != nil {
                HStack(spacing: 4) {
                    Text(display.detail)
                    ComplicationElapsedText(display: display, fallback: "")
                }
                .font(.body)
            } else {
                Text(display.detail)
                    .font(.body)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
            }
            Text("watch-audio")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// `.accessoryInline`: one line of text with a symbol.
struct ComplicationInlineView: View {
    let display: ComplicationDisplay

    var body: some View {
        Label(display.inlineText, systemImage: display.symbolName)
    }
}
