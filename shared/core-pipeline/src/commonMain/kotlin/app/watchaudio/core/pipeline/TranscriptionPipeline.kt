package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.files.Path

/**
 * Transcribes stored chunks in order, oldest first. Safe to run from any background
 * scheduler: progress lives in the database, so a run can stop at any point (OS time
 * limit, app killed) and the next run continues where it left off.
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
     * @return number of chunks attempted in this run.
     */
    suspend fun processPending(shouldContinue: () -> Boolean = { true }): RunResult {
        val attempted = mutableSetOf<String>()
        var transcribed = 0
        var failed = 0
        while (shouldContinue()) {
            val next = store.read { pendingChunks(limit = attempted.size + 1, maxAttempts = maxAttempts) }
                .firstOrNull { it.id !in attempted } ?: break
            attempted += next.id
            if (process(next)) transcribed++ else failed++
        }
        return RunResult(transcribed = transcribed, failed = failed)
    }

    private suspend fun process(chunk: ChunkRecord): Boolean {
        val relative = chunk.audioPath ?: return true // silent chunks are never queued
        store.transaction { markTranscribing(chunk.id) }
        return try {
            val utterances = transcriber.transcribe(Path(audioRoot, relative))
            val segments = utterances.map {
                NewSegment(
                    startAt = chunk.startedAt + it.startMs,
                    endAt = chunk.startedAt + it.endMs,
                    text = it.text,
                    lang = it.lang,
                )
            }
            store.transaction { saveTranscript(chunk.id, segments, transcriber.engineId) }
            true
        } catch (e: CancellationException) {
            store.transaction { resetInterrupted() }
            throw e
        } catch (e: Exception) {
            store.transaction { markFailed(chunk.id, e.message ?: e::class.simpleName.orEmpty()) }
            false
        }
    }

    data class RunResult(val transcribed: Int, val failed: Int)
}
