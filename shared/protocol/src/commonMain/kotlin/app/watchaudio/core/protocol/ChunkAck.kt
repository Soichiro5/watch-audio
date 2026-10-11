package app.watchaudio.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone → watch receipt for a chunk. The watch deletes its copy only after a [ChunkAck] with
 * [AckStatus.isFinal] == true; anything else keeps the chunk queued for resend.
 */
@Serializable
data class ChunkAck(
    val schemaVersion: Int = ChunkManifest.SCHEMA_VERSION,
    val chunkId: String,
    val status: AckStatus,
    val detail: String? = null,
)

@Serializable
enum class AckStatus(val isFinal: Boolean) {
    /** Stored for the first time. */
    @SerialName("stored") STORED(isFinal = true),
    /** Already stored earlier (resend after a lost ACK). */
    @SerialName("duplicate") DUPLICATE(isFinal = true),
    /** Size or hash mismatch: the watch should resend the file. */
    @SerialName("corrupt") CORRUPT(isFinal = false),
    /** Manifest could not be understood (e.g. newer schema): the watch keeps the chunk. */
    @SerialName("rejected") REJECTED(isFinal = false),
}
