import SwiftUI
import UIKit
import XCTest

/// The watchOS complication's state → display mapping, the shared status store and the tap
/// deep link (#41). `testRendersPreviewImages` also renders the complication layouts to PNG
/// (`build/complication-previews/`, uploaded by CI as evidence).
final class ComplicationDisplayTests: XCTestCase {
    func testStoppedShowsStartHint() {
        let display = ComplicationDisplay(.stopped)
        XCTAssertEqual(display.tone, .stopped)
        XCTAssertEqual(display.title, "停止中")
        XCTAssertEqual(display.detail, "タップで録音開始")
        XCTAssertEqual(display.inlineText, "録音停止中")
        XCTAssertEqual(display.symbolName, "mic.slash")
        XCTAssertNil(display.recordingSince)
        XCTAssertEqual(display.url, ComplicationLink.record)
    }

    func testRecordingShowsElapsedSinceStart() {
        let since = Date(timeIntervalSince1970: 1_791_622_800)
        let display = ComplicationDisplay(.recording(since: since))
        XCTAssertEqual(display.tone, .recording)
        XCTAssertEqual(display.title, "録音中")
        XCTAssertEqual(display.inlineText, "録音中")
        XCTAssertEqual(display.symbolName, "record.circle")
        XCTAssertEqual(display.recordingSince, since)
        XCTAssertEqual(display.url, ComplicationLink.record)
    }

    func testPausedShowsReasonAndResumeHint() {
        let expected: [ChunkEndReason: String] = [
            .charging: "充電中",
            .lowBattery: "電池不足",
            .lowStorage: "容量不足",
            .interruption: "通話等で中断",
            .error: "エラー",
            .terminated: "terminated",
        ]
        for (reason, label) in expected {
            let display = ComplicationDisplay(.paused(reason))
            XCTAssertEqual(display.tone, .paused, "\(reason)")
            XCTAssertEqual(display.title, "一時停止", "\(reason)")
            XCTAssertEqual(display.detail, "\(label)・タップで再開", "\(reason)")
            XCTAssertEqual(display.inlineText, "一時停止（\(label)）", "\(reason)")
            XCTAssertNil(display.recordingSince, "\(reason)")
            XCTAssertEqual(display.url, ComplicationLink.record, "\(reason)")
        }
        XCTAssertEqual(ComplicationDisplay(RecorderStatus(state: .paused)).detail, "一時停止・タップで再開")
    }

    func testAppLabelsForPauseReasons() {
        XCTAssertEqual(RecorderStatusText.pausedLabel(.charging), "充電中（外すと再開）")
        XCTAssertEqual(RecorderStatusText.pausedLabel(.interruption), "中断中（通話・Siri など）")
        XCTAssertEqual(RecorderStatusText.pausedLabel(.error), "停止中（error）")
    }

    func testDeepLinkRecognisesOnlyTheRecordURL() throws {
        XCTAssertTrue(ComplicationLink.isRecordRequest(ComplicationLink.record))
        XCTAssertTrue(ComplicationLink.isRecordRequest(try XCTUnwrap(URL(string: "WatchAudio://RECORD"))))
        XCTAssertFalse(ComplicationLink.isRecordRequest(try XCTUnwrap(URL(string: "watchaudio://stop"))))
        XCTAssertFalse(ComplicationLink.isRecordRequest(try XCTUnwrap(URL(string: "https://record"))))
    }

    func testStoreRoundTripsAndDefaultsToStopped() throws {
        let suite = "complication-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        XCTAssertEqual(RecorderStatusStore.load(from: defaults), .stopped)

        let recording = RecorderStatus.recording(since: Date(timeIntervalSince1970: 1_791_622_800))
        RecorderStatusStore.save(recording, to: defaults)
        XCTAssertEqual(RecorderStatusStore.load(from: defaults), recording)

        RecorderStatusStore.save(.paused(.interruption), to: defaults)
        XCTAssertEqual(RecorderStatusStore.load(from: defaults), .paused(.interruption))
        let json = try XCTUnwrap(defaults.data(forKey: RecorderStatusStore.statusKey))
        XCTAssertTrue(String(decoding: json, as: UTF8.self).contains("\"interruption\""))

        defaults.set(Data("not json".utf8), forKey: RecorderStatusStore.statusKey)
        XCTAssertEqual(RecorderStatusStore.load(from: defaults), .stopped)
    }

    /// Renders every layout for each state (evidence for #41; the watch face itself cannot be
    /// configured from the command line).
    @MainActor
    func testRendersPreviewImages() throws {
        let states: [(String, RecorderStatus)] = [
            ("recording", .recording(since: Date.now.addingTimeInterval(-(3600 + 15 * 60 + 21)))),
            ("paused-charging", .paused(.charging)),
            ("paused-interruption", .paused(.interruption)),
            ("stopped", .stopped),
        ]
        let directory = Self.previewDirectory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        for (name, status) in states {
            let data = try XCTUnwrap(Self.png(ComplicationPreviewSheet(title: name, display: ComplicationDisplay(status))))
            XCTAssertGreaterThan(data.count, 1_000, name)
            try data.write(to: directory.appending(path: "complication-\(name).png"))
        }
        let all = VStack(alignment: .leading, spacing: 0) {
            ForEach(states.indices, id: \.self) { index in
                ComplicationPreviewSheet(title: states[index].0, display: ComplicationDisplay(states[index].1))
            }
        }
        let data = try XCTUnwrap(Self.png(all))
        try data.write(to: directory.appending(path: "complication-all.png"))
        print("[complication-previews] wrote \(states.count + 1) images to \(directory.path(percentEncoded: false))")
    }

    @MainActor
    private static func png(_ view: some View) -> Data? {
        let renderer = ImageRenderer(content: view.environment(\.colorScheme, .dark))
        renderer.scale = 2
        return renderer.uiImage?.pngData()
    }

    private static var previewDirectory: URL {
        if let dir = ProcessInfo.processInfo.environment["COMPLICATION_PREVIEW_DIR"] {
            return URL(fileURLWithPath: dir)
        }
        // iosApp/ProtocolTests/<this file> -> repository root
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "build/complication-previews")
    }
}

/// The complication layouts at Apple Watch accessory sizes on a black watch-face background.
private struct ComplicationPreviewSheet: View {
    let title: String
    let display: ComplicationDisplay

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.caption.monospaced())
                .foregroundStyle(.gray)
            HStack(alignment: .center, spacing: 14) {
                ComplicationCircularView(display: display)
                    .frame(width: 50, height: 50)
                ComplicationRectangularView(display: display)
                    .frame(width: 170, height: 66)
            }
            ComplicationInlineView(display: display)
                .font(.footnote)
                .frame(width: 234, alignment: .leading)
        }
        .foregroundStyle(.white)
        .padding(14)
        .frame(width: 262, alignment: .leading)
        .background(Color.black)
    }
}
