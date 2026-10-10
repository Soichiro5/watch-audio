package app.watchaudio.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Schedules [TranscriptionWorker] through WorkManager:
 * - [requestNow] after each stored chunk (and on user request),
 * - a periodic run while charging as a safety net for anything a missed trigger left behind.
 */
class TranscriptionScheduler(context: Context, private val scope: CoroutineScope) {
    private val workManager = WorkManager.getInstance(context)
    private val enqueueLock = Mutex()

    /** True while a transcription run is in progress. */
    val running: Flow<Boolean> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(NOW),
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC),
    ) { now, periodic -> (now + periodic).any { it.state == WorkInfo.State.RUNNING } }

    /**
     * Makes sure a run starts soon. If one is queued already, nothing changes. If one is running,
     * a single follow-up is appended, because the running one may already have looked for work
     * before the newest chunk arrived.
     */
    fun requestNow() {
        scope.launch(Dispatchers.IO) {
            enqueueLock.withLock {
                val states = workManager.getWorkInfosForUniqueWork(NOW).get().map { it.state }
                val queued = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
                if (queued) return@withLock
                val policy = if (WorkInfo.State.RUNNING in states) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
                val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
                workManager.enqueueUniqueWork(NOW, policy, request)
            }
        }
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<TranscriptionWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val NOW = "transcription"
        const val PERIODIC = "transcription-periodic"
    }
}
