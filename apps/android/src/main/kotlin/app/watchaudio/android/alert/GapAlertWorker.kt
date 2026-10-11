package app.watchaudio.android.alert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.watchaudio.android.AppGraph

/** Runs one gap alert check (periodic safety net or the timer armed after a chunk). */
class GapAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        graph.startup.join()
        graph.gapAlerts.check()
        return Result.success()
    }
}
