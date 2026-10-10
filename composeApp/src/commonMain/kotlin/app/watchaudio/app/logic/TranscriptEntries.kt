package app.watchaudio.app.logic

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.SegmentRecord

/** One row of a day's chronological transcript list. */
sealed interface TranscriptEntry {
    val at: Long
    val key: String

    /** A transcribed utterance; [audioPath] + [offsetMs] start playback at it. */
    data class Utterance(
        val segment: SegmentRecord,
        val audioPath: String?,
        val offsetMs: Long,
    ) : TranscriptEntry {
        override val at: Long get() = segment.startAt
        override val key: String get() = "s${segment.id}"
    }

    /** Consecutive chunks that are not transcribed (yet), shown as one row. */
    data class Untranscribed(
        val kind: Kind,
        val from: Long,
        val to: Long,
        val chunkIds: List<String>,
        /** Last error of the group (only for [Kind.FAILED]). */
        val error: String?,
    ) : TranscriptEntry {
        enum class Kind { PENDING, TRANSCRIBING, FAILED }

        override val at: Long get() = from
        override val key: String get() = "c${chunkIds.first()}"
    }
}

/**
 * Merges a day's segments with the chunks that have no transcript yet (pending, in progress
 * or failed), oldest first. Silent chunks and transcribed chunks without speech add no rows.
 */
fun buildTranscriptEntries(chunks: List<ChunkRecord>, segments: List<SegmentRecord>): List<TranscriptEntry> {
    val chunksById = chunks.associateBy { it.id }
    val utterances = segments.map { segment ->
        val chunk = chunksById[segment.chunkId]
        TranscriptEntry.Utterance(
            segment = segment,
            audioPath = chunk?.audioPath,
            offsetMs = if (chunk == null) 0 else (segment.startAt - chunk.startedAt).coerceAtLeast(0),
        )
    }

    val groups = mutableListOf<TranscriptEntry.Untranscribed>()
    var open: TranscriptEntry.Untranscribed? = null
    for (chunk in chunks.sortedBy { it.startedAt }) {
        val kind = when (chunk.state) {
            ChunkState.RECEIVED -> TranscriptEntry.Untranscribed.Kind.PENDING
            ChunkState.TRANSCRIBING -> TranscriptEntry.Untranscribed.Kind.TRANSCRIBING
            ChunkState.FAILED -> TranscriptEntry.Untranscribed.Kind.FAILED
            ChunkState.NO_AUDIO -> continue // silence neither breaks nor joins a group
            ChunkState.TRANSCRIBED -> {
                open?.let(groups::add)
                open = null
                continue
            }
        }
        open = if (open != null && open.kind == kind) {
            open.copy(
                to = maxOf(open.to, chunk.endedAt),
                chunkIds = open.chunkIds + chunk.id,
                error = chunk.lastError ?: open.error,
            )
        } else {
            open?.let(groups::add)
            TranscriptEntry.Untranscribed(kind, chunk.startedAt, chunk.endedAt, listOf(chunk.id), chunk.lastError)
        }
    }
    open?.let(groups::add)

    return (utterances + groups).sortedBy { it.at }
}
