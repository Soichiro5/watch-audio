import Foundation

/// What to do with a chunk after the iPhone's ACK.
enum AckAction: Equatable, Sendable {
    /// Final ACK (stored or duplicate): delete the chunk from the outbox.
    case delete
    /// Size or hash mismatch: send the chunk again now.
    case resend
    /// Keep the chunk in the outbox; it is offered again on the next activation.
    case keep
}

/// When to (re)send the chunks of the `ChunkOutbox` (#17). The outbox files are the source of
/// truth for *what* waits for a final ACK and survive relaunches; this in-memory bookkeeping only
/// decides *when* to hand each one to WatchConnectivity again. A new process starts empty, and
/// `RecorderController` sends everything pending once the session activates (`force`).
/// Counterpart of the Wear OS `Outbox` retry state.
struct ChunkSendTracker: Sendable {
    /// Resend a chunk that was handed to WatchConnectivity but never ACKed after this long.
    static let resendAfter: TimeInterval = 15 * 60
    /// CORRUPT answers resent right away per chunk; after that the chunk waits for the next activation.
    static let maxCorruptResends = 3

    private var lastSent: [String: Date] = [:]
    private var corruptResends: [String: Int] = [:]

    /// The chunk was queued with WatchConnectivity at [date].
    mutating func markSent(_ chunkId: String, at date: Date) {
        lastSent[chunkId] = date
    }

    /// WatchConnectivity reported that the transfer failed: the chunk is due again right away.
    mutating func markFailed(_ chunkId: String) {
        lastSent[chunkId] = nil
    }

    /// Chunks of [pending] (oldest first) to hand to the transport now. Chunks the system is still
    /// delivering ([inFlight]) are never queued twice; without [force], a sent chunk is only sent
    /// again after [resendAfter] without an ACK.
    func dueForSend(pending: [String], inFlight: Set<String>, now: Date, force: Bool) -> [String] {
        pending.filter { id in
            guard !inFlight.contains(id) else { return false }
            if force { return true }
            guard let last = lastSent[id] else { return true }
            return now.timeIntervalSince(last) >= Self.resendAfter
        }
    }

    mutating func apply(_ ack: ChunkAck) -> AckAction {
        if ack.status.isFinal {
            lastSent[ack.chunkId] = nil
            corruptResends[ack.chunkId] = nil
            return .delete
        }
        switch ack.status {
        case .corrupt:
            let attempts = (corruptResends[ack.chunkId] ?? 0) + 1
            corruptResends[ack.chunkId] = attempts
            return attempts <= Self.maxCorruptResends ? .resend : .keep
        default:
            // .rejected (e.g. a newer schema): keep the chunk for a later app version.
            return .keep
        }
    }
}
