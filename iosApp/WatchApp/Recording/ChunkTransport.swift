import Foundation
import WatchConnectivity

/// Sends closed chunks to the iPhone with WatchConnectivity and receives the iPhone's ACKs.
///
/// WatchConnectivity is a local, Apple-managed transport between the paired devices
/// (Bluetooth / peer-to-peer Wi-Fi, queued by the OS and delivered in the background); this app
/// never uploads audio to any server of its own. Files go with `transferFile(_:metadata:)`
/// (the ChunkManifest JSON travels in the metadata); silent chunks have no file and go with
/// `transferUserInfo`. ACKs come back as `transferUserInfo(["ack": json])`.
///
/// Delegate callbacks arrive on a background queue and are forwarded to the owner, which hops
/// to the main actor.
final class ChunkTransport: NSObject, WCSessionDelegate, @unchecked Sendable {
    // Only immutable state: safe to share across threads.
    private let onActivated: @Sendable () -> Void
    private let onAck: @Sendable (ChunkAck) -> Void
    private let onTransferFinished: @Sendable (_ chunkId: String?, _ error: String?) -> Void

    init(
        onActivated: @escaping @Sendable () -> Void,
        onAck: @escaping @Sendable (ChunkAck) -> Void,
        onTransferFinished: @escaping @Sendable (_ chunkId: String?, _ error: String?) -> Void
    ) {
        self.onActivated = onActivated
        self.onAck = onAck
        self.onTransferFinished = onTransferFinished
        super.init()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    var isActivated: Bool {
        WCSession.isSupported() && WCSession.default.activationState == .activated
    }

    /// Chunk ids the system is still trying to deliver (these must not be queued twice).
    func outstandingChunkIds() -> Set<String> {
        guard isActivated else { return [] }
        let session = WCSession.default
        var ids = Set<String>()
        for transfer in session.outstandingFileTransfers {
            if let id = transfer.file.metadata?[WatchTransferKeys.chunkId] as? String {
                ids.insert(id)
            }
        }
        for transfer in session.outstandingUserInfoTransfers {
            if let id = transfer.userInfo[WatchTransferKeys.chunkId] as? String {
                ids.insert(id)
            }
        }
        return ids
    }

    /// Queues the chunk with the system. Returns false if the session is not usable yet.
    func send(_ item: OutboxItem) -> Bool {
        guard WCSession.isSupported() else { return false }
        let session = WCSession.default
        guard session.activationState == .activated || session.isReachable else { return false }
        let info: [String: Any] = [
            WatchTransferKeys.manifest: item.manifestJSON,
            WatchTransferKeys.chunkId: item.chunkId,
        ]
        if let audioURL = item.audioURL {
            session.transferFile(audioURL, metadata: info)
        } else {
            session.transferUserInfo(info)
        }
        return true
    }

    // MARK: - WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if activationState == .activated {
            onActivated()
        }
    }

    func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
        let chunkId = fileTransfer.file.metadata?[WatchTransferKeys.chunkId] as? String
        onTransferFinished(chunkId, error?.localizedDescription)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let json = userInfo[WatchTransferKeys.ack] as? String,
              let ack = try? ChunkProtocol.decode(ChunkAck.self, from: json)
        else { return }
        onAck(ack)
    }
}
