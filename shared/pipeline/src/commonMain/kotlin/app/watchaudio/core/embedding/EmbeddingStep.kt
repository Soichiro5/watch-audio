package app.watchaudio.core.embedding

import app.watchaudio.core.db.ChunkToEmbed
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewPassage
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.search.SemanticIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * The pipeline's `embedded` step (#24): transcribed chunks whose segments have no passages for the
 * current model are joined into passages ([EmbeddingPassages]), embedded on the device
 * ([Embedder]), quantized to int8 ([Int8Vectors]) and stored (`passage_embedding`). Newest chunks
 * first, so recent conversations become searchable by meaning before the backfill of older ones;
 * a chunk whose segments change (boundary re-transcription, a restore) loses its passages and is
 * embedded again, and so is everything after a model change ([EmbeddingModelSpec.id]).
 *
 * It never delays transcription: [app.watchaudio.core.pipeline.TranscriptionRun] runs it only once
 * no chunk waits for transcription, under the same battery/thermal policy, and stops it between
 * chunks as soon as a chunk arrives or the policy or the platform says so. On battery it only
 * embeds chunks received in the last [Limits.batteryRecentMs] and at most
 * [Limits.batteryChunksPerRun] per run (the day's new conversations, seconds of CPU); the backfill
 * of older data waits for the charger.
 *
 * Afterwards the search's [index] catches up (and prunes the deletion log). Nothing happens while
 * the model is not downloaded ([isModelReady]).
 */
class EmbeddingStep(
    private val store: LocalStore,
    private val embedder: Embedder,
    private val index: SemanticIndex?,
    private val diagnostics: Diagnostics = Diagnostics.NONE,
    private val rules: EmbeddingPassages.Rules = EmbeddingPassages.Rules(),
    private val limits: Limits = Limits(),
    /** Whether the model's files are on the device; the step does nothing until they are. */
    private val isModelReady: () -> Boolean = { true },
) {
    data class Limits(
        /** Chunks per run while charging; more makes the platform run again ([FollowUp.RunAgain]). */
        val chunksPerRun: Int = 300,
        /** Stop a run after this long (WorkManager stops workers after 10 minutes). */
        val maxRunMs: Long = 4 * 60_000L,
        /** Chunks per run on battery. */
        val batteryChunksPerRun: Int = 50,
        /** On battery, only chunks the phone received this recently. */
        val batteryRecentMs: Long = 48 * 60 * 60_000L,
        /** Failed chunks after which a run gives up (the model is likely broken). */
        val maxFailures: Int = 3,
        /** Passages of an old model deleted per run. */
        val oldModelDeletesPerRun: Int = 20_000,
    )

    /** What should run next for the embedding step. */
    enum class FollowUp {
        /** Nothing left (or the run was stopped from outside; the platform reschedules that). */
        None,

        /** Charging and more to embed: run again now. */
        RunAgain,

        /** On battery with older chunks left: run once the phone is plugged in. */
        OnCharge,
    }

    data class Outcome(val embeddedChunks: Int, val passages: Int, val failedChunks: Int, val followUp: FollowUp)

    /**
     * Embeds pending chunks. [onBattery] applies the battery limits; [mayContinue] is asked before
     * every chunk (platform stop, newly arrived audio, the policy).
     * @param nowMs the current time, for the battery window.
     */
    suspend fun run(onBattery: Boolean, nowMs: Long, mayContinue: suspend () -> Boolean): Outcome {
        if (!isModelReady()) return Outcome(0, 0, 0, FollowUp.None)
        val spec = embedder.spec
        val started = TimeSource.Monotonic.markNow()
        val limit = if (onBattery) limits.batteryChunksPerRun else limits.chunksPerRun
        val since = if (onBattery) nowMs - limits.batteryRecentMs else null
        var embedded = 0
        var passages = 0
        var failures = 0
        var stopped = false
        var exhausted = false
        try {
            store.transaction { deletePassagesOfOtherModels(spec.id, limits.oldModelDeletesPerRun) }
            val skipped = HashSet<String>()
            while (embedded + failures < limit) {
                val batch = store.read { chunksToEmbed(spec.id, BATCH + skipped.size, since) }.filter { it.chunkId !in skipped }
                if (batch.isEmpty()) {
                    exhausted = true
                    break
                }
                for (chunk in batch) {
                    if (embedded + failures >= limit || started.elapsedNow().inWholeMilliseconds >= limits.maxRunMs) break
                    if (!mayContinue()) {
                        stopped = true
                        break
                    }
                    try {
                        passages += embed(chunk)
                        embedded++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        skipped += chunk.chunkId
                        diagnostics.log(DiagnosticEvents.EMBEDDING_FAILED, "chunk=${chunk.chunkId} error=${DiagnosticsLog.typeName(e)}")
                        if (failures >= limits.maxFailures) break
                    }
                }
                if (stopped || failures >= limits.maxFailures || started.elapsedNow().inWholeMilliseconds >= limits.maxRunMs) break
            }
        } finally {
            if (embedded > 0 || failures > 0) {
                diagnostics.log(
                    DiagnosticEvents.EMBEDDING_RUN,
                    "chunks=$embedded passages=$passages failed=$failures on_battery=$onBattery ms=${started.elapsedNow().inWholeMilliseconds}",
                )
            }
        }
        index?.sync(prune = true)
        // The model holds ~100 MB while loaded; the search screen loads it again when needed.
        if (embedded > 0 || failures > 0) embedder.release()
        val followUp = when {
            exhausted || stopped || failures >= limits.maxFailures -> {
                // On battery only recent chunks were looked at: older ones may still wait for the charger.
                if (onBattery && !stopped && failures < limits.maxFailures && store.read { chunksToEmbed(spec.id, 1).isNotEmpty() }) {
                    FollowUp.OnCharge
                } else {
                    FollowUp.None
                }
            }
            onBattery -> FollowUp.OnCharge
            else -> FollowUp.RunAgain
        }
        return Outcome(embedded, passages, failures, followUp)
    }

    /** Embeds and stores one chunk's passages; @return how many (0 if its segments changed meanwhile). */
    private suspend fun embed(chunk: ChunkToEmbed): Int {
        val spec = embedder.spec
        val segments = store.read { chunkSegments(chunk.chunkId) }
        if (segments.isEmpty()) return 0
        val planned = segments.map { EmbeddingPassages.Segment(it.startAt, it.endAt, embedder.countTokens(it.text)) }
        val spans = EmbeddingPassages.plan(planned, rules)
        val texts = spans.map { span -> EmbeddingPassages.text(segments.subList(span.firstIndex, span.firstIndex + span.count).map { it.text }) }
        val vectors = embedder.embedDocuments(texts)
        val passages = spans.mapIndexed { i, span ->
            val (values, scale) = Int8Vectors.quantize(vectors[i])
            NewPassage(span.firstIndex, span.count, segments[span.firstIndex].startAt, scale, values)
        }
        val stored = store.transaction { savePassages(chunk.chunkId, spec.id, segments.map { it.id }, passages) }
        return if (stored) passages.size else 0
    }

    private companion object {
        const val BATCH = 32
    }
}
