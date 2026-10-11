package app.watchaudio.android

import android.app.Application
import android.os.Build
import app.watchaudio.android.diag.CrashRecorder
import app.watchaudio.android.diag.PreviousExits
import app.watchaudio.core.pipeline.DiagnosticEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Builds the [AppGraph] and arranges background work once per process start. */
class WatchAudioApp : Application() {
    val graph: AppGraph get() = AppGraph.get(this)

    /** What the shared Compose UI needs (`App(services)`). */
    val services: AndroidAppServices get() = graph.services

    override fun onCreate() {
        super.onCreate()
        // Before anything else, so a crash while starting up is recorded too (#77).
        val diagnostics = AppGraph.diagnostics(this)
        CrashRecorder.install(diagnostics)
        val graph = graph
        graph.scope.launch(Dispatchers.IO) {
            diagnostics.log(
                DiagnosticEvents.APP_START,
                "version=${versionName()} sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL}",
            )
            PreviousExits.record(this@WatchAudioApp, diagnostics)
        }
        graph.scheduler.schedulePeriodic()
        graph.gapAlerts.schedulePeriodic()
        graph.scope.launch {
            graph.startup.join()
            // Pick up chunks left over from an earlier process, and fetch the model on Wi-Fi.
            if (graph.hasPendingChunks()) graph.scheduler.requestNow()
            graph.models.downloadIfMissing()
        }
    }

    @Suppress("DEPRECATION") // the flags overload needs API 33
    private fun versionName(): String? = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
}
