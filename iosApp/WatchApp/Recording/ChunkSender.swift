import Foundation
import WatchConnectivity

/// Spike-quality transfer of closed chunks to the iPhone (no manifest/ACK yet — that is M1-5, #17).
/// Used to measure the battery cost of transferring while recording.
final class ChunkSender: NSObject, WCSessionDelegate, @unchecked Sendable {
    // Only immutable state: safe to share across threads.
    private let onTransferFinished: @Sendable (_ fileName: String, _ error: String?) -> Void

    init(onTransferFinished: @escaping @Sendable (_ fileName: String, _ error: String?) -> Void) {
        self.onTransferFinished = onTransferFinished
        super.init()
        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }
    }

    func send(_ url: URL, kind: String) {
        WCSession.default.transferFile(url, metadata: ["kind": kind, "name": url.lastPathComponent])
    }

    var pendingTransfers: Int { WCSession.default.outstandingFileTransfers.count }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}

    func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
        let url = fileTransfer.file.fileURL
        if error == nil, url.pathExtension == "m4a" {
            // The phone has the chunk; free watch storage. (Keep the CSV log locally.)
            try? FileManager.default.removeItem(at: url)
        }
        onTransferFinished(url.lastPathComponent, error?.localizedDescription)
    }
}
