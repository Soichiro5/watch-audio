package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.db.StoreTransaction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/**
 * Transcribes stored chunks in order, oldest first. Safe to run from any background
 * scheduler: progress lives in the database, so a run can stop at any point (OS time
 * limit, app killed) and the next run continues where it left off.
 *
 * An utterance crossing the boundary to a seamless, already transcribed neighbour is
 * transcribed in one piece together with that neighbour's adjoining audio (see [BoundaryJoin]).
 */
class TranscriptionPipeline(
    private val store: LocalStore,
    private val audioRoot: Path,
    private val transcriber: Transcriber,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    /**
     * Re-queues chunks left TRANSCRIBING by a run that was killed or cancelled. Only call it while
     * no run is in progress ([TranscriptionRun] does so at the start of every run).
     * @return the number of chunks re-queued.
     */
    suspend fun recoverInterrupted(): Int = store.transaction { resetInterrupted() }

    /** True if a chunk waits for transcription (new, or failed fewer than `maxAttempts` times). */
    suspend fun hasPending(): Boolean = store.read { pendingChunks(limit = 1, maxAttempts = maxAttempts) }.isNotEmpty()

    /**
     * Processes pending chunks until none are left or [shouldContinue] returns false.
     * [observer] hears about each chunk (e.g. for logs).
     * @return number of chunks attempted in this run.
     */
    suspend fun processPending(observer: Observer = Observer.NONE, shouldContinue: () -> Boolean = { true }): RunResult {
        val attempted = mutableSetOf<String>()
        var transcribed = 0
        var failed = 0
        while (shouldContinue()) {
            val next = store.read { pendingChunks(limit = attempted.size + 1, maxAttempts = maxAttempts) }
                .firstOrNull { it.id !in attempted } ?: break
            attempted += next.id
            observer.started(next.id)
            val startedAt = TimeSource.Monotonic.markNow()
            val error = process(next)
            if (error == null) transcribed++ else failed++
            observer.finished(
                ChunkOutcome(
                    chunkId = next.id,
                    transcribed = error == null,
                    audioMs = next.endedAt - next.startedAt,
                    elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
                    errorType = error,
                ),
            )
        }
        return RunResult(transcribed = transcribed, failed = failed)
    }

    /** @return null on success, else the type of the error (the chunk is marked failed). */
    private suspend fun process(chunk: ChunkRecord): String? {
        if (chunk.audioPath == null) return null // silent chunks are never queued
        store.transaction { markTranscribing(chunk.id) }
        return try {
            val joined = store.read { planJoin(chunk) }
            val segments = try {
                transcribe(joined)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A neighbour's audio may be unreadable or gone; the chunk alone may still work.
                if (joined.previous == null && joined.next == null) throw e
                transcribe(JoinedTranscription(chunk))
            }
            store.transaction { save(segments) }
            null
        } catch (e: CancellationException) {
            // The coroutine is already cancelled; the requeue must still run.
            withContext(NonCancellable) { store.transaction { resetInterrupted() } }
            throw e
        } catch (e: Exception) {
            store.transaction { markFailed(chunk.id, e.message ?: e::class.simpleName.orEmpty()) }
            e::class.simpleName ?: "Exception"
        }
    }

    private class Transcribed(val joined: JoinedTranscription, val byChunk: Map<String, List<NewSegment>>)

    private suspend fun transcribe(joined: JoinedTranscription) =
        Transcribed(joined, joined.assign(transcriber.transcribe(joined.parts(audioRoot))))

    /** Joins [chunk] with the boundary audio of its seamless, transcribed neighbours. */
    private fun StoreTransaction.planJoin(chunk: ChunkRecord): JoinedTranscription {
        val previous = previousInSession(chunk)
            ?.takeIf { it.state == ChunkState.TRANSCRIBED && BoundaryJoin.isSeamless(it, chunk) }
            ?.let { BoundaryJoin.tail(it, segmentsBetween(it.startedAt, it.endedAt)) }
        val next = nextInSession(chunk)
            ?.takeIf { it.state == ChunkState.TRANSCRIBED && BoundaryJoin.isSeamless(chunk, it) }
            ?.let { BoundaryJoin.head(it, segmentsBetween(it.startedAt, it.endedAt)) }
        return JoinedTranscription(chunk, previous, next)
    }

    private fun StoreTransaction.save(result: Transcribed) {
        val joined = result.joined
        val engine = transcriber.engineId
        // The neighbours' segments that start inside the re-transcribed audio are superseded.
        joined.previous?.let { replaceSegments(it.chunk.id, it.from, Long.MAX_VALUE, result.byChunk.getValue(it.chunk.id), engine) }
        joined.next?.let { replaceSegments(it.chunk.id, Long.MIN_VALUE, it.to, result.byChunk.getValue(it.chunk.id), engine) }
        saveTranscript(joined.chunk.id, result.byChunk.getValue(joined.chunk.id), engine)
    }

    data class RunResult(val transcribed: Int, val failed: Int)

    companion object {
        /** Attempts per chunk before it stays FAILED (it is no longer pending then). */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }

    /**
     * How one chunk went: [audioMs] is its recorded length, [elapsedMs] the time spent on it
     * (including joined neighbour audio), [errorType] the failure's exception class (never its
     * message, which may quote data).
     */
    data class ChunkOutcome(
        val chunkId: String,
        val transcribed: Boolean,
        val audioMs: Long,
        val elapsedMs: Long,
        val errorType: String? = null,
    )

    /** Progress of [processPending], chunk by chunk. A chunk interrupted by cancellation never finishes. */
    interface Observer {
        fun started(chunkId: String) {}

        /** [ChunkOutcome.transcribed] is false when the attempt failed (see `markFailed`). */
        fun finished(outcome: ChunkOutcome) {}

        companion object {
            val NONE: Observer = object : Observer {}
        }
    }
}
