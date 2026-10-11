package app.watchaudio.core.db

import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform

/** Processing state of a stored chunk. */
enum class ChunkState {
    /** Stored; waiting for transcription. */
    RECEIVED,

    /** Transcription in progress (reset to RECEIVED if the app dies mid-way). */
    TRANSCRIBING,

    /** Transcript stored (possibly with no segments if nothing was said). */
    TRANSCRIBED,

    /** Transcription failed; retried until the attempt limit. */
    FAILED,

    /** Silent span without audio; nothing to transcribe. */
    NO_AUDIO,
}

/**
 * One stored chunk (a row of `audio_chunk`): the ingested [app.watchaudio.core.protocol.ChunkManifest]
 * plus its processing state on the phone. Times are epoch ms.
 */
data class ChunkRecord(
    /** The manifest's chunk id. */
    val id: String,
    /** Recording session on the watch (from start until the recorder stops). */
    val sessionId: String,
    /** The watch that recorded the chunk. */
    val deviceId: String,
    val source: SourcePlatform,
    val startedAt: Long,
    val endedAt: Long,
    /** IANA time zone on the watch when the chunk started. */
    val timeZone: String,
    /** Relative to the audio root (app container paths change across iOS updates). */
    val audioPath: String?,
    val endReason: ChunkEndReason,
    val state: ChunkState,
    /** Transcription attempts so far (reset by a user-initiated retry). */
    val attempts: Int,
    /** Message of the last failed attempt, or null. */
    val lastError: String?,
    /** Size of the audio file as received, or null for a silent chunk. */
    val sizeBytes: Long? = null,
    /** When the audio file was deleted by the retention setting (the transcript is kept). */
    val audioPurgedAt: Long? = null,
) {
    /** True if the chunk's audio file should still exist and can be played. */
    val hasAudioFile: Boolean get() = audioPath != null && audioPurgedAt == null
}

/** A transcript segment to store. Times are absolute epoch ms. */
data class NewSegment(
    val startAt: Long,
    val endAt: Long,
    val text: String,
    /** Language code reported by the recognizer, or null if unknown. */
    val lang: String?,
)

/** A stored transcript segment (a row of `transcript_segment`). Times are absolute epoch ms. */
data class SegmentRecord(
    /** Row id. */
    val id: Long,
    /** The chunk the segment's start falls in. */
    val chunkId: String,
    val startAt: Long,
    val endAt: Long,
    val text: String,
    /** Language code reported by the recognizer, or null if unknown. */
    val lang: String?,
    /** Id of the transcriber that produced the segment (`Transcriber.engineId`). */
    val engine: String,
)

/** A keyword search result: the matching segment and a highlighted excerpt. */
data class SearchHit(
    val segment: SegmentRecord,
    /** Text with the match wrapped in `[` `]`. */
    val snippet: String,
)

/** Counters over the whole store, for the status screen and storage management. */
data class StoreStats(
    /** All stored chunks, silent ones included. */
    val chunks: Int,
    /** Chunks waiting for or in transcription ([ChunkState.RECEIVED], [ChunkState.TRANSCRIBING]). */
    val pendingChunks: Int,
    val transcribedChunks: Int,
    val failedChunks: Int,
    /** Stored transcript segments. */
    val segments: Int,
    /** Audio still on the device (purged audio excluded). */
    val audioBytes: Long,
    /** Total duration of chunks that had audio (purged audio included). */
    val recordedMs: Long,
    /** When the phone last stored a chunk (`received_at`), or null if there are none. */
    val lastReceivedAt: Long? = null,
    /** Chunks whose audio was deleted by the retention setting (their transcript is kept). */
    val audioPurgedChunks: Int = 0,
)

/** Number and total size of audio files, e.g. those a retention change would delete. */
data class AudioUsage(val chunks: Int, val bytes: Long)
