package app.watchaudio.android

import android.app.Application
import kotlinx.coroutines.launch

/** Builds the [AppGraph] and arranges background work once per process start. */
class WatchAudioApp : Application() {
    val graph: AppGraph get() = AppGraph.get(this)

    /** What the shared Compose UI needs (`App(services)`). */
    val services: AndroidAppServices get() = graph.services

    override fun onCreate() {
        super.onCreate()
        val graph = graph
        graph.scheduler.schedulePeriodic()
        graph.gapAlerts.schedulePeriodic()
        graph.scope.launch {
            graph.startup.join()
            // Pick up chunks left over from an earlier process, and fetch the model on Wi-Fi.
            if (graph.hasPendingChunks()) graph.scheduler.requestNow()
            graph.models.downloadIfMissing()
        }
    }
}
