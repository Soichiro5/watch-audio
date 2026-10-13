import ComposeApp
import Foundation
import XCTest

/// #185 in the app process, against the real bridge CLI on the CI host (`tools/agent-bridge`,
/// `e2e/emulator-host.ts`), driven by `.github/scripts/ios-agent-access.sh`. The simulator shares the
/// host's network, so the CLI is reached at 127.0.0.1. The app's own `IosAgentAccess` (Keychain key,
/// Network.framework mutual TLS 1.3, the shared connector and tools) is driven through
/// `IosAgentAccessCheck` the way the 「エージェント連携」 screen drives it (the screen itself is the
/// shared Compose UI, tested in `AppUiTest` / `AgentViewModelTest`):
///
/// 1. `testPair` (env `AGENT_PAIRING_URI`, the string `watch-audio-mcp pair` printed): seeds a
///    transcript, pairs, confirms the 6-digit code (the script compares it with the PC's).
/// 2. `testServe` (env `AGENT_SERVE=1`, the CLI running as an agent's MCP server that calls
///    `search_transcripts` every second): on → the PC's agent reads the transcript; off → nothing is
///    answered; on → answered; the app leaves the screen → off, nothing answered; on → answered;
///    revoked → nothing answered. The host expects connected / disconnected six times.
///
/// Evidence: `AGENT_CHECK` lines in `build/swift-evidence/agent-access-*.txt`. Without the env both
/// tests skip.
final class AgentAccessTests: XCTestCase {
    /// Long enough for the host (one call per second) to notice that the phone is gone.
    private static let offWindow: Duration = .seconds(6)
    /// Margin for a call that was being answered at the moment access ended.
    private static let inFlightMs: Int64 = 1_000

    private var check: IosAgentAccessCheck { IosAgentAccessCheck.shared }

    @MainActor
    func testPair() async throws {
        let report = EvidenceReport(name: "agent-access-pair")
        guard let uri = ProcessInfo.processInfo.environment["AGENT_PAIRING_URI"], !uri.isEmpty else {
            throw report.skip("AGENT_PAIRING_URI not set (run by .github/scripts/ios-agent-access.sh)")
        }
        report.log("simulator: \(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "-")")

        let directory = FileManager.default.temporaryDirectory.appending(path: "agent-check")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let audio = directory.appending(path: "\(UUID().uuidString).m4a")
        try TestAudio.writeWatchFormatAAC(to: audio, durationMs: 20_000)
        let seeded = await withCheckedContinuation { continuation in
            check.seed(audioPath: audio.path, durationMs: 20_000) { continuation.resume(returning: $0) }
        }
        XCTAssertNil(seeded, "seeding the transcript")

        let (code, error) = await withCheckedContinuation { continuation in
            check.pair(uri: uri) { code, error in continuation.resume(returning: (code, error)) }
        }
        let pairings = await withCheckedContinuation { continuation in
            check.pairings { continuation.resume(returning: $0) }
        }
        report.log("AGENT_CHECK phase=paired code=\(code ?? "-") error=\(error ?? "none") pairings=\(pairings)")
        report.write()
        XCTAssertNil(error)
        XCTAssertNotNil(code)
        XCTAssertTrue(pairings.contains("@127.0.0.1:"), pairings)
        XCTAssertTrue(pairings.contains("audio=false"), "text only by default: \(pairings)")
    }

    @MainActor
    func testServe() async throws {
        let report = EvidenceReport(name: "agent-access-serve")
        guard ProcessInfo.processInfo.environment["AGENT_SERVE"] == "1" else {
            throw report.skip("AGENT_SERVE not set (run by .github/scripts/ios-agent-access.sh)")
        }
        defer { report.write() }

        // mDNS: what the simulator sees of the CLI's advertisement (informational; the last known
        // address is used when nothing is found).
        let mdns = await withCheckedContinuation { continuation in
            check.discover(seconds: 10) { continuation.resume(returning: $0) }
        }
        report.log("AGENT_CHECK phase=mdns \(mdns)")

        // 1. On: the PC's agent reads the transcript.
        let firstOn = check.now()
        try await turnOn(report)
        try await waitFor("the connection to the bridge", timeout: .seconds(60)) { self.check.isConnected() }
        let first = try await waitForAnswer(since: firstOn, "a tool call answered while on")
        report.log("AGENT_CHECK phase=on state=\(check.describeState()) log=\(first)")
        let clip = await withCheckedContinuation { continuation in
            check.audioClip { continuation.resume(returning: $0) }
        }
        report.log("AGENT_CHECK phase=audio_clip \(clip)")
        XCTAssertTrue(clip.hasPrefix("mime=audio/wav"), clip)

        // 2. Off with the switch: nothing is answered.
        check.turnOff()
        XCTAssertFalse(check.isOn())
        let offAnswered = try await answeredDuringOffWindow()
        report.log("AGENT_CHECK phase=off state=\(check.describeState()) calls_answered_while_off=\(offAnswered)")
        XCTAssertEqual(offAnswered, 0)

        // 3. On again.
        let secondOn = check.now()
        try await turnOn(report)
        _ = try await waitForAnswer(since: secondOn, "a tool call answered after turning on again")
        report.log("AGENT_CHECK phase=on_again state=\(check.describeState())")

        // 4. The app leaves the screen: access turns off and stays off when it comes back.
        check.setAppActive(active: false)
        let offInBackground = !check.isOn()
        let backgroundAnswered = try await answeredDuringOffWindow()
        check.setAppActive(active: true)
        report.log("AGENT_CHECK phase=background off=\(offInBackground) calls_answered_in_background=\(backgroundAnswered) on_after_return=\(check.isOn())")
        XCTAssertTrue(offInBackground, "leaving the screen turns access off")
        XCTAssertEqual(backgroundAnswered, 0)
        XCTAssertFalse(check.isOn(), "access stays off after coming back")

        // 5. On once more, then revoke: the connection ends and nothing is answered any more.
        let thirdOn = check.now()
        try await turnOn(report)
        _ = try await waitForAnswer(since: thirdOn, "a tool call answered after coming back")
        await withCheckedContinuation { continuation in check.revokeAll { continuation.resume() } }
        let revokedAnswered = try await answeredDuringOffWindow()
        let log = await accessLog(since: firstOn)
        report.log("AGENT_CHECK phase=revoked state=\(check.describeState()) calls_answered_after=\(revokedAnswered) log=\(log)")
        XCTAssertEqual(revokedAnswered, 0)
        XCTAssertFalse(check.isOn())
    }

    @MainActor
    private func turnOn(_ report: EvidenceReport) async throws {
        let result = await withCheckedContinuation { continuation in
            check.turnOn(minutes: 30) { continuation.resume(returning: $0) }
        }
        guard result == "ON" else {
            report.log("AGENT_CHECK turn_on=\(result) FAILED")
            XCTFail("turning access on answered \(result)")
            throw CancellationError()
        }
    }

    /// `<answered>|<entries>` of the access log since `since`.
    @MainActor
    private func accessLog(since: Int64) async -> String {
        await withCheckedContinuation { continuation in
            check.accessLog(since: since) { continuation.resume(returning: $0) }
        }
    }

    @MainActor
    private func answered(since: Int64) async -> Int {
        Int(await accessLog(since: since).split(separator: "|", maxSplits: 1).first ?? "0") ?? 0
    }

    /// Waits until a `search_transcripts` call since `since` was answered; returns the log.
    @MainActor
    private func waitForAnswer(since: Int64, _ what: String) async throws -> String {
        var log = ""
        try await waitFor(what, timeout: .seconds(120)) {
            log = await self.accessLog(since: since)
            return log.contains("search_transcripts:OK")
        }
        return log
    }

    /// Calls answered (outcome OK) during an off window that starts now.
    @MainActor
    private func answeredDuringOffWindow() async throws -> Int {
        let since = check.now() + Self.inFlightMs
        try await Task.sleep(for: Self.offWindow)
        return await answered(since: since)
    }

    @MainActor
    private func waitFor(_ what: String, timeout: Duration, condition: @MainActor () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while clock.now < deadline {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(500))
        }
        XCTFail("timed out waiting for \(what): \(check.describeState())")
        throw CancellationError()
    }
}
