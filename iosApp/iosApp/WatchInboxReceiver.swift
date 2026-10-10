import Foundation
import WatchConnectivity

/// Receives files from the watchOS spike recorder (#9) into `Documents/WatchInbox/`, which is
/// visible in the Files app (On My iPhone › watch-audio). Replaced by the real ingest + ACK
/// flow in M1-5 (#17).
final class WatchInboxReceiver: NSObject, WCSessionDelegate, @unchecked Sendable {
    // No mutable state: safe to share across threads.
    static let shared = WatchInboxReceiver()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        // The system deletes `file.fileURL` when this method returns, so move it synchronously.
        let inbox = URL.documentsDirectory.appending(path: "WatchInbox")
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let name = (file.metadata?["name"] as? String) ?? file.fileURL.lastPathComponent
        let target = inbox.appending(path: "\(Int(Date.now.timeIntervalSince1970))-\(name)")
        try? FileManager.default.moveItem(at: file.fileURL, to: target)
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        // Re-activate after switching to a different paired watch.
        session.activate()
    }
}
