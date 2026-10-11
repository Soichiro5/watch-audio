import Foundation
import XCTest

/// Collects what a simulator check observed and writes it to `build/swift-evidence/<name>.txt`
/// (`EVIDENCE_DIR` overrides the directory), which CI adds to the job summary. Shared by the
/// app-hosted test bundles (`SpeechTests`, `PlaybackTests`).
final class EvidenceReport {
    private let name: String
    private var lines: [String] = []

    init(name: String) {
        self.name = name
    }

    func log(_ line: String) {
        print("[\(name)] \(line)")
        lines.append(line)
    }

    /// Logs and writes the reason, and returns the skip to throw.
    func skip(_ reason: String) -> XCTSkip {
        log("SKIPPED: \(reason)")
        write()
        return XCTSkip(reason)
    }

    func write() {
        // iosApp/TestSupport/<this file> -> repository root
        let directory = ProcessInfo.processInfo.environment["EVIDENCE_DIR"].map { URL(fileURLWithPath: $0) }
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "build/swift-evidence")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let text = lines.joined(separator: "\n") + "\n"
        try? Data(text.utf8).write(to: directory.appending(path: "\(name).txt"), options: .atomic)
    }
}
