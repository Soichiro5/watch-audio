package app.watchaudio.core.db

import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform

/** Processing state of a stored chunk. */
enum class ChunkState {
    /** Stored; waiting for transcription. */
    RECEIVED,

    /** Transcription in progress (reset to RECEIVED if the app dies mid-way). */
    TRANSCRIBING,
    TRANSCRIBED,

    /** Transcription failed; retried until the attempt limit. */
    FAILED,

    /** Silent span without audio; nothing to transcribe. */
    NO_AUDIO,
}

data class ChunkRecord(
    val id: String,
    val sessionId: String,
    val deviceId: String,
    val source: SourcePlatform,
    val startedAt: Long,
    val endedAt: Long,
    val timeZone: String,
    /** Relative to the audio root (app container paths change across iOS updates). */
    val audioPath: String?,
    val endReason: ChunkEndReason,
    val state: ChunkState,
    val attempts: Int,
    val lastError: String?,
)

data class NewSegment(
    val startAt: Long,
    val endAt: Long,
    val text: String,
    val lang: String?,
)

data class SegmentRecord(
    val id: Long,
    val chunkId: String,
    val startAt: Long,
    val endAt: Long,
    val text: String,
    val lang: String?,
    val engine: String,
)

data class SearchHit(
    val segment: SegmentRecord,
    /** Text with the match wrapped in `[` `]`. */
    val snippet: String,
)

data class StoreStats(
    val chunks: Int,
    val pendingChunks: Int,
    val transcribedChunks: Int,
    val failedChunks: Int,
    val segments: Int,
    val audioBytes: Long,
    val recordedMs: Long,
    /** When the phone last stored a chunk (`received_at`), or null if there are none. */
    val lastReceivedAt: Long? = null,
)
