package app.watchaudio.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Schedules [TranscriptionWorker] through WorkManager:
 * - [requestNow] after each stored chunk, [requestForUser] on the user's request,
 * - [requestLater] when the battery/thermal policy paused a run for a while (warm phone, hourly
 *   cap on battery; #75),
 * - [requestOnCharge] when the policy waits for the charger (battery low, battery saver, the
 *   「充電中のみ」 setting),
 * - a periodic run while charging as a safety net for anything a missed trigger left behind.
 *
 * Every run asks [app.watchaudio.core.pipeline.ProcessingPolicy] before each chunk; the WorkManager
 * constraints only keep runs from starting when they could not do anything: the one-off run needs
 * a battery that is not low, or the charger with [chargingOnly] set.
 */
class TranscriptionScheduler(
    context: Context,
    private val scope: CoroutineScope,
    /** The user's 「充電中のみ文字起こしする」 setting. */
    private val chargingOnly: suspend () -> Boolean = { false },
) {
    private val app = context.applicationContext

    // Looked up on each use, so that instrumented tests can swap in WorkManager's test instance.
    private val workManager: WorkManager get() = WorkManager.getInstance(app)
    private val enqueueLock = Mutex()

    /** True while a transcription run is in progress. */
    val running: Flow<Boolean> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(NOW),
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC),
        workManager.getWorkInfosForUniqueWorkFlow(ON_CHARGE),
    ) { now, periodic, onCharge -> (now + periodic + onCharge).any { it.state == WorkInfo.State.RUNNING } }

    /**
     * Makes sure a run starts soon. If one is queued already, nothing changes, unless it is delayed by
     * the policy: then it is replaced, so each new chunk re-checks the conditions (a run that may not
     * transcribe ends at once). If one is running, a single follow-up is appended, because the
     * running one may already have looked for work before the newest chunk arrived.
     */
    fun requestNow() {
        scope.launch { enqueue(delayMs = 0, userRequested = false, replaceQueued = false) }
    }

    /**
     * The user tapped 「今すぐ文字起こし」: like [requestNow], but it replaces any queued run, has no
     * constraint, and the run waives the battery rules (not the thermal one).
     */
    fun requestForUser() {
        scope.launch { enqueue(delayMs = 0, userRequested = true, replaceQueued = true) }
    }

    /** The 「充電中のみ」 setting changed: re-plan a queued run with the new constraints. */
    fun settingsChanged() {
        scope.launch { enqueue(delayMs = 0, userRequested = false, replaceQueued = true) }
    }

    /** A run after [delayMs] (the policy's retry delay), unless one is queued that starts sooner. */
    suspend fun requestLater(delayMs: Long) {
        enqueue(delayMs = delayMs, userRequested = false, replaceQueued = false)
    }

    /** A run as soon as the phone is plugged in. */
    suspend fun requestOnCharge() {
        withContext(Dispatchers.IO) {
            enqueueLock.withLock {
                val workManager = workManager
                val states = workManager.getWorkInfosForUniqueWork(ON_CHARGE).get().map { it.state }
                if (states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }) return@withLock
                val policy = if (WorkInfo.State.RUNNING in states) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
                val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                    .build()
                workManager.enqueueUniqueWork(ON_CHARGE, policy, request).result.get()
            }
        }
    }

    private suspend fun enqueue(delayMs: Long, userRequested: Boolean, replaceQueued: Boolean) {
        withContext(Dispatchers.IO) {
            enqueueLock.withLock {
                val workManager = workManager
                val infos = workManager.getWorkInfosForUniqueWork(NOW).get()
                val queued = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                // A blocked run starts right after the running one; a delayed one may start too late.
                val dueBy = System.currentTimeMillis() + delayMs
                val queuedSoonEnough = queued.any { it.state == WorkInfo.State.BLOCKED || it.nextScheduleTimeMillis <= dueBy }
                if (queuedSoonEnough && !replaceQueued) return@withLock
                // Never REPLACE while running: that would cancel the run in the middle of a chunk.
                val running = infos.any { it.state == WorkInfo.State.RUNNING }
                val policy = if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
                val constraints = when {
                    userRequested -> Constraints.NONE
                    chargingOnly() -> Constraints.Builder().setRequiresCharging(true).build()
                    else -> Constraints.Builder().setRequiresBatteryNotLow(true).build()
                }
                val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_USER_REQUESTED to userRequested))
                    .build()
                workManager.enqueueUniqueWork(NOW, policy, request).result.get()
            }
        }
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<TranscriptionWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        /** Unique work names (also read by the instrumented tests). */
        internal const val NOW = "transcription"
        internal const val PERIODIC = "transcription-periodic"
        internal const val ON_CHARGE = "transcription-on-charge"

        /** Input of a run the user asked for (see [requestForUser]). */
        internal const val KEY_USER_REQUESTED = "user_requested"
    }
}
