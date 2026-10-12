package app.watchaudio.core.pipeline

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.settings.loadSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The speech model a transcription run needs, as the platform provides it: Android checks the
 * downloaded sherpa-onnx files, iOS asks the OS to install the Speech model on first use.
 */
interface SpeechModel {
    /**
     * Makes the model usable if the platform can do that right away (iOS installs it on request)
     * and returns its state; a run transcribes only when it is [ModelState.Ready].
     */
    suspend fun ensureReady(): ModelState

    /**
     * Chunks failed in the last run. The model may be gone (the OS can remove it, a file can be
     * deleted): check it again before the next run instead of trusting the last state.
     */
    fun recheck()
}

/**
 * One transcription run on the phone, the same on Android (WorkManager) and iOS (foreground and
 * BGTask), so the platforms only map the [Report] to their scheduler (#134):
 *
 * 1. re-queue chunks a killed or cancelled run left TRANSCRIBING (every run, under the run lock),
 * 2. make sure the speech model is ready ([SpeechModel]); without it every chunk would fail and
 *    use up its attempts, so the run transcribes nothing,
 * 3. transcribe under the battery/thermal policy ([PolicyGatedTranscription], #75) with the
 *    user's 「充電中のみ」 setting; failures make the model be checked again,
 * 4. once no chunk waits for transcription, the embedding step of semantic search ([embedding],
 *    #24) under the same policy, stopped between chunks when audio arrives,
 * 5. apply the audio retention setting ([StorageManager.applyRetention], #33), also when nothing
 *    was transcribed,
 * 6. decide what to schedule next ([FollowUp]; the embedding step's own in [Report.embedding]).
 *
 * Runs are serialized: overlapping callers (a one-off and the periodic worker, a foreground run
 * and a BGTask) wait for each other. Progress goes to [diagnostics] (`worker_start`/`worker_stop`,
 * and per chunk `transcription_started`/`_finished`/`_failed` through [DiagnosticsPipelineObserver]).
 *
 * @param gate builds the policy-gated transcription for each run, so that test hooks for the
 *   device state, the policy and the clock take effect on the next run.
 * @param releaseTranscriber frees the engine after a run that transcribed (Android's recognizer
 *   holds a few hundred MB).
 * @param embedding the embedding step (#24), or null where the platform has no embedding model.
 */
class TranscriptionRun(
    private val pipeline: TranscriptionPipeline,
    private val store: LocalStore,
    private val storage: StorageManager,
    private val model: SpeechModel,
    private val diagnostics: Diagnostics,
    private val gate: () -> PolicyGatedTranscription,
    private val releaseTranscriber: suspend () -> Unit = {},
    private val embedding: EmbeddingStep? = null,
) {
    private val lock = Mutex()

    /**
     * Runs once. [worker] names the caller in the diagnostics log (`transcription`, `foreground`,
     * `bgtask`); [startDetail] and [stopDetail] add platform details to its start and stop lines.
     *
     * @param userRequested the user asked for this run (see [ProcessingPolicy.decide]).
     * @param observer hears about each chunk, in addition to the diagnostics log.
     * @param shouldContinue false once the platform wants the run to stop (checked between chunks
     *   and retention batches); a stopped run reports [FollowUp.Interrupted].
     * @param onTranscribing true while chunks are being transcribed, false afterwards (status UI).
     * @throws CancellationException if the caller is cancelled; the chunk in progress is re-queued.
     */
    suspend fun run(
        worker: String,
        userRequested: Boolean = false,
        observer: TranscriptionPipeline.Observer = TranscriptionPipeline.Observer.NONE,
        shouldContinue: () -> Boolean = { true },
        onTranscribing: (Boolean) -> Unit = {},
        startDetail: String = "",
        stopDetail: () -> String = { "" },
    ): Report {
        diagnostics.log(DiagnosticEvents.WORKER_START, "worker=$worker$startDetail")
        var summary = ""
        try {
            return lock.withLock {
                val report = runLocked(userRequested, observer, shouldContinue, onTranscribing)
                summary = report.summary()
                report
            }
        } finally {
            diagnostics.log(DiagnosticEvents.WORKER_STOP, "worker=$worker$summary${stopDetail()}")
        }
    }

    private suspend fun runLocked(
        userRequested: Boolean,
        observer: TranscriptionPipeline.Observer,
        shouldContinue: () -> Boolean,
        onTranscribing: (Boolean) -> Unit,
    ): Report {
        val recovered = pipeline.recoverInterrupted()
        val modelState = model.ensureReady()
        val transcription = if (modelState == ModelState.Ready) {
            transcribe(userRequested, observer, shouldContinue, onTranscribing)
        } else {
            null
        }
        val embedded = embedding?.let { embed(it, shouldContinue) }
        val retention = applyRetention(shouldContinue)
        val followUp = followUp(
            modelState = modelState,
            pause = transcription?.pause,
            stopped = !shouldContinue(),
            pending = transcription != null && pipeline.hasPending(),
        )
        return Report(modelState, transcription, recovered, retention, followUp, embedded)
    }

    /**
     * Runs the embedding step if nothing waits for transcription and the policy allows background
     * work now; it stops between chunks once that changes. Failures are logged and leave the
     * transcription results alone. @return null when the step did not run.
     */
    private suspend fun embed(step: EmbeddingStep, shouldContinue: () -> Boolean): EmbeddingStep.Outcome? {
        if (!shouldContinue() || pipeline.hasPending()) return null
        return try {
            val chargingOnly = store.loadSettings().transcribeOnlyWhileCharging
            val gate = gate()
            val decision = gate.decideOtherWork(chargingOnly) as? ProcessingDecision.Run ?: return null
            step.run(onBattery = decision.onBattery, nowMs = gate.now()) {
                shouldContinue() && !pipeline.hasPending() && gate.decideOtherWork(chargingOnly) is ProcessingDecision.Run
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Also errors (a native library that cannot load): semantic search is optional,
            // transcription is not.
            diagnostics.log(DiagnosticEvents.EMBEDDING_FAILED, "error=${DiagnosticsLog.typeName(e)}")
            null
        }
    }

    private suspend fun transcribe(
        userRequested: Boolean,
        observer: TranscriptionPipeline.Observer,
        shouldContinue: () -> Boolean,
        onTranscribing: (Boolean) -> Unit,
    ): PolicyGatedTranscription.Outcome {
        val chargingOnly = store.loadSettings().transcribeOnlyWhileCharging
        onTranscribing(true)
        val outcome = try {
            gate().run(chargingOnly, userRequested, BothObservers(DiagnosticsPipelineObserver(diagnostics), observer), shouldContinue)
        } finally {
            onTranscribing(false)
            withContext(NonCancellable) { releaseTranscriber() }
        }
        if (outcome.result.failed > 0) model.recheck()
        return outcome
    }

    private suspend fun applyRetention(shouldContinue: () -> Boolean): Retention = try {
        val purged = storage.applyRetention(shouldContinue)
        if (purged.purgedChunks > 0) {
            diagnostics.log(DiagnosticEvents.RETENTION_PURGE, "chunks=${purged.purgedChunks} bytes=${purged.freedBytes}")
        }
        Retention.Purged(purged)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        diagnostics.log(DiagnosticEvents.RETENTION_PURGE, "failed=true error=${DiagnosticsLog.typeName(e)}")
        Retention.Failed(e)
    }

    /** What one run did and what should happen next. */
    data class Report(
        /** The model's state when the run checked it; nothing was transcribed unless [ModelState.Ready]. */
        val model: ModelState,
        /** What was transcribed, and the policy's pause if it stopped the run; null without a model. */
        val transcription: PolicyGatedTranscription.Outcome?,
        /** Chunks re-queued because an earlier run was cut off. */
        val recovered: Int,
        val retention: Retention,
        val followUp: FollowUp,
        /** What the embedding step did (#24); null when it did not run. */
        val embedding: EmbeddingStep.Outcome? = null,
    ) {
        /** The outcome for the `worker_stop` line, e.g. ` transcribed=2 failed=0 paused=thermal`. */
        fun summary(): String {
            val outcome = transcription ?: return " skipped=model_not_ready"
            return " transcribed=${outcome.result.transcribed} failed=${outcome.result.failed}" +
                outcome.pause?.let { " paused=${it.reason.code}" }.orEmpty()
        }
    }

    /** The audio retention step of a run. */
    sealed interface Retention {
        data class Purged(val result: StorageManager.PurgeResult) : Retention

        /** Deleting failed (the next run tries again); [error] is for the platform's log and status. */
        data class Failed(val error: Exception) : Retention
    }

    /** What the platform should schedule after a run. */
    sealed interface FollowUp {
        /** No chunk is waiting. */
        data object None : FollowUp

        /** Chunks are still waiting and nothing holds them back (e.g. a failed chunk to retry): run again now. */
        data object RunAgain : FollowUp

        /**
         * The policy paused the run (#75) with chunks still waiting: run again after [retryAfterMs]
         * (null: no timed retry) and, if [onCharge], once the phone is plugged in. A warm phone does
         * not cool down by charging, so a thermal pause only retries after its delay.
         */
        data class Paused(val reason: PauseReason, val retryAfterMs: Long?, val onCharge: Boolean) : FollowUp

        /**
         * The platform stopped the run ([run]'s `shouldContinue` turned false): the platform
         * reschedules it its own way (WorkManager retries stopped work, iOS submits a BGTask).
         */
        data object Interrupted : FollowUp

        /**
         * The model is not ready, so nothing was transcribed. A model that becomes ready asks for a
         * run itself; while it is [downloading], the platform may also retry the run.
         */
        data class WaitForModel(val downloading: Boolean) : FollowUp
    }

    companion object {
        /**
         * The [FollowUp] of a run: [modelState] when it was checked, the policy's [pause] (null if
         * it did not pause), whether the platform [stopped] the run and whether chunks are still
         * [pending] afterwards.
         */
        fun followUp(modelState: ModelState, pause: ProcessingDecision.Pause?, stopped: Boolean, pending: Boolean): FollowUp = when {
            modelState != ModelState.Ready -> FollowUp.WaitForModel(downloading = modelState is ModelState.Downloading)
            stopped -> FollowUp.Interrupted
            !pending -> FollowUp.None
            pause != null -> FollowUp.Paused(pause.reason, pause.retryAfterMs, onCharge = pause.reason != PauseReason.THERMAL)
            else -> FollowUp.RunAgain
        }
    }

    private class BothObservers(
        private val first: TranscriptionPipeline.Observer,
        private val second: TranscriptionPipeline.Observer,
    ) : TranscriptionPipeline.Observer {
        override fun started(chunkId: String) {
            first.started(chunkId)
            second.started(chunkId)
        }

        override fun finished(outcome: TranscriptionPipeline.ChunkOutcome) {
            first.finished(outcome)
            second.finished(outcome)
        }
    }
}
