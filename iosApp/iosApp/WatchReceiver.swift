import ComposeApp
import Foundation
import WatchConnectivity

/// Receives chunks from the Apple Watch over WatchConnectivity (a local, Apple-managed transport
/// between the paired devices) and hands them to the shared Kotlin `Ingestor` through
/// `IosAppGraph`. Every chunk is answered with a ChunkAck via `transferUserInfo(["ack": json])`;
/// the watch deletes its copy only after a final ACK (stored / duplicate).
final class WatchReceiver: NSObject, WCSessionDelegate, @unchecked Sendable {
    // No mutable state: safe to share across threads.
    static let shared = WatchReceiver()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// A chunk with audio. The system deletes `file.fileURL` when this method returns, so the
    /// file is moved into the app's inbox synchronously before ingesting asynchronously.
    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        guard let manifestJSON = file.metadata?[WatchTransferKeys.manifest] as? String else {
            return // Not a chunk (e.g. a log from an old spike build); let the system delete it.
        }
        let inbox = URL(fileURLWithPath: IosAppGraph.shared.inboxDirectoryPath(), isDirectory: true)
        let target = inbox.appending(path: UUID().uuidString + "-" + file.fileURL.lastPathComponent)
        do {
            try FileManager.default.moveItem(at: file.fileURL, to: target)
        } catch {
            return // No ACK: the watch keeps the chunk and resends it later.
        }
        IosAppGraph.shared.ingest(manifestJson: manifestJSON, filePath: target.path(percentEncoded: false)) { ackJSON in
            WatchReceiver.sendAck(ackJSON)
        }
    }

    /// A silent chunk: manifest only, no audio.
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let manifestJSON = userInfo[WatchTransferKeys.manifest] as? String else { return }
        IosAppGraph.shared.ingest(manifestJson: manifestJSON, filePath: nil) { ackJSON in
            WatchReceiver.sendAck(ackJSON)
        }
    }

    private static func sendAck(_ ackJSON: String?) {
        guard let ackJSON, WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated else { return } // the watch resends; the phone answers "duplicate"
        session.transferUserInfo([WatchTransferKeys.ack: ackJSON])
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        // Re-activate after switching to a different paired watch.
        session.activate()
    }
}
