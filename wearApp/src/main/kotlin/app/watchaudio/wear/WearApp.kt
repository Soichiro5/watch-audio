package app.watchaudio.wear

import android.app.Application
import android.content.Context
import app.watchaudio.wear.diag.DiagnosticsLog
import app.watchaudio.wear.outbox.Outbox
import app.watchaudio.wear.outbox.OutboxUploader
import app.watchaudio.wear.outbox.UploadScheduler
import app.watchaudio.wear.recording.ChunkFinalizer
import app.watchaudio.wear.recording.RecorderAlerts
import app.watchaudio.wear.recording.RecorderStore
import app.watchaudio.wear.recording.RecordingDir
import app.watchaudio.wear.recording.currentBattery
import app.watchaudio.wear.recording.statusKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WearApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // No recorder can be running yet in a fresh process, so every journal left in the
        // recording directory belongs to a process that died while recording.
        val leftovers = graph.recordingDir.journals()
        graph.scope.launch(graph.chunkIo) {
            if (leftovers.isNotEmpty()) {
                val results = graph.finalizer.recoverLeftovers(leftovers)
                results.forEach { graph.log("chunk_recovered", "${it.manifest.chunkId} audio=${it.manifest.audio != null}") }
            }
            graph.refreshPendingCount()
            UploadScheduler.schedulePeriodic(this@WearApp)
            UploadScheduler.enqueue(this@WearApp)
            remindIfArmedButNotRecording(recovered = leftovers.isNotEmpty())
        }
    }

    /**
     * The process restarted (crash, OS kill, reboot) while the wearer wanted to record. Android 14+
     * forbids starting a microphone foreground service from the background, so ask the wearer to
     * tap — unless the service or the app UI resumed recording in the meantime.
     */
    private suspend fun remindIfArmedButNotRecording(recovered: Boolean) {
        if (!graph.prefs.armed) return
        delay(RESUME_REMINDER_DELAY_MS)
        if (graph.prefs.armed && !RecorderStore.state.value.serviceRunning) {
            graph.log("terminated", "armed but not running (recovered=$recovered)")
            graph.alerts.showTerminated()
        }
    }

    private companion object {
        const val RESUME_REMINDER_DELAY_MS = 5_000L
    }
}

/** Process-wide singletons (manual DI: the watch app is small). */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Single-threaded IO for chunk finalization so chunks reach the outbox in order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val chunkIo = Dispatchers.IO.limitedParallelism(1)

    val prefs = WearPrefs(context)
    val recordingDir = RecordingDir(File(context.filesDir, "recording"))
    val outbox = Outbox(File(context.filesDir, "outbox"))
    val finalizer = ChunkFinalizer(recordingDir, outbox)
    val uploader by lazy { OutboxUploader(context, this) }
    val alerts by lazy { RecorderAlerts(context) }

    /** Kept on app-specific external storage so it can be pulled with adb (no audio in it). */
    val diagnostics = DiagnosticsLog(File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics/events.csv"))

    fun log(event: String, detail: String = "") {
        val state = RecorderStore.state.value
        val battery = state.batteryPercent?.let { it to state.charging } ?: context.currentBattery().let { it.percent to it.charging }
        diagnostics.append(
            DiagnosticsLog.Snapshot(battery.first, battery.second, state.mode.statusKey(), state.pendingUploads),
            event,
            detail,
        )
    }

    fun refreshPendingCount() {
        val pending = outbox.count()
        val lastAck = prefs.lastAckAtEpochMs.takeIf { it > 0 }
        RecorderStore.update { it.copy(pendingUploads = pending, lastAckAtEpochMs = lastAck) }
    }

    companion object {
        fun of(context: Context): AppGraph = (context.applicationContext as WearApp).graph
    }
}
