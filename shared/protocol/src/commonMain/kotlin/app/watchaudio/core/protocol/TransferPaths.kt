package app.watchaudio.core.protocol

/**
 * Wearable Data Layer paths (Wear OS ↔ Android). Only `MessageClient`/`ChannelClient` with
 * nearby (directly connected) nodes are used: `DataClient` items can be relayed through
 * Google's servers, which would break the "local only" rule.
 */
object TransferPaths {
    /** Channel carrying one framed chunk (see [ChunkFrame]); the suffix is the chunk id. */
    const val CHUNK_CHANNEL_PREFIX = "/watch-audio/chunk/"

    /** Message from the phone with a [ChunkAck] JSON payload. */
    const val ACK_MESSAGE = "/watch-audio/ack"

    /** Message from the watch asking the phone to resend ACKs it may have lost (no payload). */
    const val SYNC_REQUEST_MESSAGE = "/watch-audio/sync"
}
