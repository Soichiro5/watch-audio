package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.db.SegmentRecord
import kotlin.math.abs
import kotlinx.io.files.Path

/**
 * Keeps utterances that cross a chunk boundary in one piece.
 *
 * The watch closes a chunk every ~5 minutes regardless of speech, so an utterance can start in
 * one chunk and end in the next; transcribed separately, it is cut in two and the words at the
 * cut are garbled. Instead, a chunk is transcribed together with the audio around the boundary
 * of each seamless, already transcribed neighbour:
 *
 * ```
 *   previous chunk                 this chunk                  next chunk
 *   … ▕last utterance▏|…………………………………………………………………|▕first utterance▏ …
 *     └ tail ┘ +            whole chunk              + └ head ┘
 * ```
 *
 * Every recognised utterance belongs to the chunk its start falls in, and the neighbour's
 * segments that start inside its re-transcribed tail or head are replaced, so nothing is stored
 * twice. Whichever of two neighbours is transcribed second does the join, so arrival order does
 * not matter.
 */
object BoundaryJoin {
    /** Neighbouring chunks this close (end → start) count as one continuous recording. */
    const val SEAMLESS_TOLERANCE_MS = 1_000L

    /** Speech this close to a boundary may continue across it. */
    const val NEAR_BOUNDARY_MS = 1_500L

    /** Extra audio kept around the boundary utterance so its edges are not clipped. */
    const val MARGIN_MS = 500L

    /** Upper bound on audio taken from one neighbour; longer boundary speech stays split. */
    const val MAX_CONTEXT_MS = 30_000L

    /** True when [later] continues [earlier]'s recording without a gap. */
    fun isSeamless(earlier: ChunkRecord, later: ChunkRecord): Boolean =
        earlier.sessionId == later.sessionId &&
            earlier.deviceId == later.deviceId &&
            !earlier.endReason.startsGap &&
            earlier.audioPath != null &&
            later.audioPath != null &&
            abs(later.startedAt - earlier.endedAt) <= SEAMLESS_TOLERANCE_MS

    /**
     * The end of [previous] to transcribe again in front of the following chunk: from just
     * before the speech that reaches its end, to its end. Null when no speech reaches the end,
     * or when it is too long to re-transcribe.
     *
     * @param segments segments overlapping [previous]'s time range, of any chunk.
     */
    fun tail(previous: ChunkRecord, segments: List<SegmentRecord>): BoundaryContext? {
        val boundary = segments.filter { it.endAt >= previous.endedAt - NEAR_BOUNDARY_MS }
        if (boundary.isEmpty()) return null
        val start = boundary.minOf { it.startAt }
        // Speech that began in an earlier chunk is not this chunk's to replace.
        if (start < previous.startedAt) return null
        val priorEnd = (segments - boundary.toSet()).maxOfOrNull { it.endAt } ?: Long.MIN_VALUE
        val from = maxOf(previous.startedAt, minOf(start, maxOf(start - MARGIN_MS, priorEnd)))
        if (previous.endedAt - from > MAX_CONTEXT_MS) return null
        return BoundaryContext(previous, from, previous.endedAt)
    }

    /**
     * The start of [next] to transcribe again after the preceding chunk: from its start to just
     * after the speech that begins at its start. Null when no speech is near its start, or when
     * it is too long to re-transcribe.
     *
     * @param segments segments overlapping [next]'s time range, of any chunk.
     */
    fun head(next: ChunkRecord, segments: List<SegmentRecord>): BoundaryContext? {
        val boundary = segments.filter { it.startAt <= next.startedAt + NEAR_BOUNDARY_MS }
        if (boundary.isEmpty()) return null
        val end = boundary.maxOf { it.endAt }
        // Speech that runs on into a later chunk would be cut off at this chunk's end.
        if (end > next.endedAt) return null
        val laterStart = (segments - boundary.toSet()).minOfOrNull { it.startAt } ?: Long.MAX_VALUE
        val to = minOf(next.endedAt, maxOf(end, minOf(end + MARGIN_MS, laterStart)))
        if (to - next.startedAt > MAX_CONTEXT_MS) return null
        return BoundaryContext(next, next.startedAt, to)
    }
}

/** The absolute time range `[from, to)` of [chunk] taken into a joined transcription. */
data class BoundaryContext(val chunk: ChunkRecord, val from: Long, val to: Long)

/**
 * One transcription run: [chunk] in full, optionally preceded by the [previous] chunk's tail and
 * followed by the [next] chunk's head (see [BoundaryJoin]).
 */
class JoinedTranscription(
    val chunk: ChunkRecord,
    val previous: BoundaryContext? = null,
    val next: BoundaryContext? = null,
) {
    private val pieces = listOfNotNull(previous, BoundaryContext(chunk, chunk.startedAt, chunk.endedAt), next)

    init {
        require(pieces.all { it.chunk.audioPath != null }) { "every piece needs audio" }
    }

    /** What to pass to [Transcriber.transcribe]. */
    fun parts(audioRoot: Path): List<AudioPart> = pieces.map {
        AudioPart(Path(audioRoot, it.chunk.audioPath!!), it.from - it.chunk.startedAt, it.to - it.chunk.startedAt)
    }

    /**
     * Maps [results] (relative to the concatenated parts) to absolute times and groups them by
     * the chunk their start falls in. Every piece's chunk id is a key, possibly with no segments.
     */
    fun assign(results: List<TimedText>): Map<String, List<NewSegment>> {
        val byChunk = pieces.associate { it.chunk.id to mutableListOf<NewSegment>() }
        for (result in results) {
            val (piece, startAt) = locate(result.startMs)
            val endAt = maxOf(startAt, locate(result.endMs).second)
            byChunk.getValue(piece.chunk.id) += NewSegment(startAt, endAt, result.text, result.lang)
        }
        return byChunk
    }

    /** The piece holding [offsetMs] of the concatenation, and the absolute time of that offset. */
    private fun locate(offsetMs: Long): Pair<BoundaryContext, Long> {
        var pieceStart = 0L
        for ((index, piece) in pieces.withIndex()) {
            val length = piece.to - piece.from
            if (offsetMs < pieceStart + length || index == pieces.lastIndex) {
                return piece to piece.from + (offsetMs - pieceStart)
            }
            pieceStart += length
        }
        error("no pieces")
    }
}
