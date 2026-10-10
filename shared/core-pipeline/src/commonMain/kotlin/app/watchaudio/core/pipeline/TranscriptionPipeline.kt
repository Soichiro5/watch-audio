package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.db.StoreTransaction
import kotlin.coroutines.cancellation.CancellationException
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
    private val maxAttempts: Int = 3,
) {
    /** Call once per process start: re-queues chunks a previous process died on. */
    suspend fun recoverInterrupted(): Int = store.transaction { resetInterrupted() }

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
            val ok = process(next)
            if (ok) transcribed++ else failed++
            observer.finished(next.id, ok)
        }
        return RunResult(transcribed = transcribed, failed = failed)
    }

    private suspend fun process(chunk: ChunkRecord): Boolean {
        if (chunk.audioPath == null) return true // silent chunks are never queued
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
            true
        } catch (e: CancellationException) {
            // The coroutine is already cancelled; the requeue must still run.
            withContext(NonCancellable) { store.transaction { resetInterrupted() } }
            throw e
        } catch (e: Exception) {
            store.transaction { markFailed(chunk.id, e.message ?: e::class.simpleName.orEmpty()) }
            false
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

    /** Progress of [processPending], chunk by chunk. A chunk interrupted by cancellation never finishes. */
    interface Observer {
        fun started(chunkId: String) {}

        /** [transcribed] is false when the attempt failed (see `markFailed`). */
        fun finished(chunkId: String, transcribed: Boolean) {}

        companion object {
            val NONE: Observer = object : Observer {}
        }
    }
}
