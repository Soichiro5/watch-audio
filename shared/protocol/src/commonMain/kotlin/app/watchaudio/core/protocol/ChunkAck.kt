package app.watchaudio.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone → watch receipt for a chunk. The watch deletes its copy only after a [ChunkAck] with
 * [AckStatus.isFinal] == true; anything else keeps the chunk queued for resend.
 */
@Serializable
data class ChunkAck(
    /** Same version as the manifest format ([ChunkManifest.SCHEMA_VERSION]). */
    val schemaVersion: Int = ChunkManifest.SCHEMA_VERSION,
    /** The [ChunkManifest.chunkId] this receipt is for. */
    val chunkId: String,
    /** Outcome of the ingest. */
    val status: AckStatus,
    /** Short reason for a non-final status (e.g. a size or hash mismatch), for diagnostics. */
    val detail: String? = null,
)

/**
 * Outcome of ingesting one chunk on the phone. [isFinal] tells the watch whether it may delete
 * its copy (true) or must keep the chunk queued and resend it (false).
 */
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
