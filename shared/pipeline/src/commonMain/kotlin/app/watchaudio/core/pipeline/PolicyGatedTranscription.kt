package app.watchaudio.core.pipeline

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.StoreTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs [TranscriptionPipeline.processPending] under [ProcessingPolicy] (#75): the policy is asked
 * before every chunk, so a run that must stop (unplugged, battery low, warm phone, hourly cap
 * reached) stops between two chunks and never loses or repeats work; the next run continues with
 * the next pending chunk. Audio transcribed on battery is charged to the hourly [BatteryBudget],
 * which is kept in the `app_setting` table so it survives the process.
 *
 * The decision of the run (and a pause, if any) goes to [diagnostics] as a
 * [DiagnosticEvents.TRANSCRIPTION_POLICY] line: device state and counts only, no user data.
 */
class PolicyGatedTranscription(
    private val pipeline: TranscriptionPipeline,
    private val store: LocalStore,
    private val policy: ProcessingPolicy,
    private val conditions: () -> DeviceConditions,
    private val clock: () -> Long,
    private val diagnostics: Diagnostics = Diagnostics.NONE,
) {
    /** [pause] is set when the policy stopped the run (null: the queue ran dry or [run]'s caller stopped it). */
    data class Outcome(val result: TranscriptionPipeline.RunResult, val pause: ProcessingDecision.Pause?)

    /**
     * Transcribes pending chunks while the policy allows it and [shouldContinue] returns true.
     * @param chargingOnly the user's 「充電中のみ文字起こしする」 setting.
     * @param userRequested the user asked for this run (see [ProcessingPolicy.decide]).
     */
    suspend fun run(
        chargingOnly: Boolean,
        userRequested: Boolean = false,
        observer: TranscriptionPipeline.Observer = TranscriptionPipeline.Observer.NONE,
        shouldContinue: () -> Boolean = { true },
    ): Outcome {
        val initialBudget = store.read { batteryBudget() }
        var budget = initialBudget
        var pause: ProcessingDecision.Pause? = null
        var chunkOnBattery = false
        var loggedRun = false
        var chunks = 0

        fun mayStartNextChunk(): Boolean {
            if (!shouldContinue()) return false
            val now = conditions()
            return when (val decision = policy.decide(now, chargingOnly, budget, clock(), userRequested)) {
                is ProcessingDecision.Run -> {
                    if (!loggedRun) {
                        loggedRun = true
                        val used = policy.current(budget, clock()).usedAudioMs
                        diagnostics.log(
                            DiagnosticEvents.TRANSCRIPTION_POLICY,
                            "decision=run on_battery=${decision.onBattery} ${now.describe()} battery_used_ms=$used" +
                                if (userRequested) " user_requested=true" else "",
                        )
                    }
                    chunkOnBattery = decision.onBattery
                    true
                }
                is ProcessingDecision.Pause -> {
                    pause = decision
                    diagnostics.log(
                        DiagnosticEvents.TRANSCRIPTION_POLICY,
                        "decision=pause reason=${decision.reason.code} ${now.describe()} " +
                            "retry_ms=${decision.retryAfterMs ?: "on_charge"} after_chunks=$chunks",
                    )
                    false
                }
            }
        }

        val accounting = object : TranscriptionPipeline.Observer {
            override fun started(chunkId: String) = observer.started(chunkId)

            override fun finished(outcome: TranscriptionPipeline.ChunkOutcome) {
                chunks++
                if (chunkOnBattery) budget = policy.spend(budget, outcome.audioMs, clock())
                observer.finished(outcome)
            }
        }

        try {
            val result = pipeline.processPending(accounting) { mayStartNextChunk() }
            return Outcome(result, pause)
        } finally {
            if (budget != initialBudget) withContext(NonCancellable) { store.transaction { putBatteryBudget(budget) } }
        }
    }

    /**
     * The policy's decision for other background work right now (the embedding step of semantic
     * search, #24), with the same device state, setting and hourly budget as transcription; the
     * budget is not charged (that work is small next to transcription).
     */
    suspend fun decideOtherWork(chargingOnly: Boolean): ProcessingDecision {
        val budget = store.read { batteryBudget() }
        return policy.decide(conditions(), chargingOnly, budget, clock())
    }

    /** The clock the policy uses (epoch ms). */
    fun now(): Long = clock()

    companion object {
        /** `app_setting` keys of the [BatteryBudget] (state, not a user preference). */
        const val KEY_BUDGET_WINDOW_START = "transcription.battery_budget.window_start"
        const val KEY_BUDGET_USED_MS = "transcription.battery_budget.used_ms"

        /** The stored budget; an empty one if none was stored or it is unreadable. */
        fun StoreTransaction.batteryBudget(): BatteryBudget {
            val start = setting(KEY_BUDGET_WINDOW_START)?.toLongOrNull() ?: return BatteryBudget()
            return BatteryBudget(start, setting(KEY_BUDGET_USED_MS)?.toLongOrNull() ?: 0)
        }

        fun StoreTransaction.putBatteryBudget(budget: BatteryBudget) {
            putSetting(KEY_BUDGET_WINDOW_START, budget.windowStartMs.toString())
            putSetting(KEY_BUDGET_USED_MS, budget.usedAudioMs.toString())
        }
    }
}
