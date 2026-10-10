package app.watchaudio.wear.recording

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.wear.AppGraph
import app.watchaudio.wear.outbox.UploadScheduler
import app.watchaudio.wear.tile.StatusSurfaces
import java.io.File
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on recorder (#16). Started by the wearer from the foreground (app or tile tap → app),
 * then keeps running as a `microphone` foreground service until the wearer taps Stop:
 *
 * - records ~5 minute AAC chunks gaplessly ([RecorderInput]: the microphone, [ChunkRecorder]) and
 *   journals the current chunk ([ChunkTracker]) so a process death loses at most the chunk in
 *   progress;
 * - pauses (closing the chunk with the matching [ChunkEndReason]) when the watch is taken off,
 *   put on the charger, low on battery or storage, or the microphone is taken, and resumes on its
 *   own when the condition clears. Resuming happens inside the still-running foreground service,
 *   which Android 14+ allows; starting a new one from the background is not allowed;
 * - hands finalized chunks to the outbox and triggers an upload.
 *
 * The decisions themselves live in [RecorderPolicy] (pure, unit-tested); this class only wires
 * Android events to it and applies the result.
 */
class RecordingService : LifecycleService(), RecorderInput.Listener {
    private val graph by lazy { AppGraph.of(this) }
    private val handler = Handler(Looper.getMainLooper())

    private val policy = RecorderPolicy()
    private val backoff = InterruptionBackoff()
    private var conditions = RecorderConditions()
    private var mode: RecorderMode = RecorderMode.Stopped
    private var shownAlert: RecorderAlert? = null
    private var inForeground = false
    private var reconcileQueued = false

    private var recorder: RecorderInput? = null
    private var current: ChunkTracker? = null
    private var pendingNextChunkId: String? = null
    private var sessionId: String? = null
    private var sessionStartedAtEpochMs: Long? = null
    private var recorderStartedAtElapsed = 0L

    private var tickerJob: Job? = null
    private var statusJob: Job? = null
    private var reevaluateJob: Job? = null

    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var offBodyMonitor: OffBodyMonitor
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var recordingCallbackRegistered = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val sessionId = recorder?.audioSessionId ?: return
            // Android 10+ concurrent capture: a call, the Assistant or another recorder silences us.
            if (configs.any { it.clientAudioSessionId == sessionId && it.isClientSilenced }) {
                onInterrupted(hardFailure = false, ChunkEndReason.INTERRUPTION, "silenced by another client")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        batteryMonitor = BatteryMonitor(this) { status ->
            val chargingChanged = status.charging != conditions.charging
            conditions = conditions.copy(batteryPercent = status.percent, charging = status.charging)
            RecorderStore.update { it.copy(batteryPercent = status.percent, charging = status.charging) }
            if (chargingChanged) graph.log(if (status.charging) "power_connected" else "power_disconnected")
            requestReconcile()
        }
        offBodyMonitor = OffBodyMonitor(this) { onBody, atElapsed ->
            val wasOnBody = conditions.offBodySinceMs == null
            conditions = conditions.copy(offBodySinceMs = if (onBody) null else conditions.offBodySinceMs ?: atElapsed)
            RecorderStore.update { it.copy(onBody = onBody) }
            if (wasOnBody != onBody) graph.log(if (onBody) "on_body" else "off_body")
            requestReconcile()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> arm()
            ACTION_STOP -> disarm()
            ACTION_MARK -> mark()
            // Restarted by the system after the process died (START_STICKY).
            null -> restartAfterProcessDeath()
        }
        return START_STICKY
    }

    private fun arm() {
        graph.prefs.armed = true
        if (!enterForeground()) {
            // From the UI this means a missing permission; after a process death WearApp posts
            // the "tap to resume" reminder.
            stopSelf()
            return
        }
        startMonitors()
        graph.log("armed")
        // Read the charger state now: starting on the charger must not produce a tiny chunk
        // before the (asynchronous) first battery broadcast arrives.
        val battery = currentBattery()
        conditions = conditions.copy(
            armed = true,
            batteryPercent = battery.percent,
            charging = battery.charging,
            freeStorageBytes = freeStorage(),
        )
        requestReconcile()
    }

    private fun restartAfterProcessDeath() {
        if (!graph.prefs.armed) {
            stopSelf()
            return
        }
        // Android 14+ usually refuses a microphone foreground service started from the
        // background; then the wearer has to tap (WearApp posts the reminder).
        graph.log("sticky_restart")
        arm()
    }

    private fun disarm() {
        graph.prefs.armed = false
        conditions = conditions.copy(armed = false)
        graph.log("user_stop")
        reconcile()
    }

    private fun mark() {
        val chunk = current
        val rec = recorder
        if (chunk == null || rec == null) {
            if (!inForeground) stopSelf()
            return
        }
        val offset = chunk.mark(rec.elapsedRealtime())
        writeJournal(chunk)
        RecorderStore.update { it.copy(marksInSession = it.marksInSession + 1) }
        graph.log("mark", "${chunk.chunkId} +${offset}ms")
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        return try {
            ServiceCompat.startForeground(
                this,
                RecorderAlerts.NOTIFICATION_ONGOING,
                graph.alerts.ongoingNotification(mode, sessionStartedAtEpochMs),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            inForeground = true
            RecorderStore.update { it.copy(serviceRunning = true) }
            true
        } catch (e: Exception) {
            // SecurityException / ForegroundServiceStartNotAllowedException: not started from the foreground.
            graph.log("fgs_start_failed", e.toString())
            false
        }
    }

    private fun startMonitors() {
        batteryMonitor.start()
        if (!offBodyMonitor.start()) graph.log("off_body_unavailable", "sensor missing or not permitted; assuming worn")
        if (!recordingCallbackRegistered) {
            audioManager.registerAudioRecordingCallback(recordingCallback, handler)
            recordingCallbackRegistered = true
        }
        if (statusJob == null) {
            statusJob = lifecycleScope.launch {
                while (isActive) {
                    delay(STATUS_INTERVAL_MS)
                    conditions = conditions.copy(freeStorageBytes = freeStorage())
                    graph.log("battery")
                    reconcile()
                }
            }
        }
    }

    private fun stopMonitors() {
        batteryMonitor.stop()
        offBodyMonitor.stop()
        if (recordingCallbackRegistered) {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
            recordingCallbackRegistered = false
        }
        statusJob?.cancel()
        statusJob = null
        reevaluateJob?.cancel()
        reevaluateJob = null
    }

    /** Coalesces bursts of events (sensor + battery + callbacks) into one decision. */
    private fun requestReconcile() {
        if (reconcileQueued) return
        reconcileQueued = true
        handler.post {
            reconcileQueued = false
            reconcile()
        }
    }

    private fun reconcile() {
        val now = SystemClock.elapsedRealtime()
        conditions = backoff.applyTo(conditions)
        val decision = policy.decide(conditions, now)
        val target = decision.mode

        if (target == RecorderMode.Recording) {
            if (recorder == null && !startRecording()) return // failure queued another reconcile
        } else if (recorder != null) {
            stopRecording(endReasonFor(target))
        }
        applyMode(target)
        applyAlert(decision.alert)
        scheduleReevaluation(decision.reevaluateAtMs, now)
        if (target == RecorderMode.Stopped) shutdown()
    }

    private fun endReasonFor(mode: RecorderMode): ChunkEndReason = when (mode) {
        is RecorderMode.Paused -> mode.reason.endReason
        RecorderMode.Stopped -> ChunkEndReason.USER_STOP
        RecorderMode.Recording -> error("not a stop")
    }

    private fun applyMode(target: RecorderMode) {
        if (target == mode) return
        mode = target
        RecorderStore.update { it.copy(mode = target, sessionStartedAtEpochMs = sessionStartedAtEpochMs) }
        graph.prefs.lastStatus = target.statusKey()
        if (inForeground) graph.alerts.updateOngoing(target, sessionStartedAtEpochMs)
        StatusSurfaces.refresh(this)
        graph.log("mode", target.statusKey())
    }

    private fun applyAlert(alert: RecorderAlert?) {
        if (alert != null && alert != shownAlert) {
            graph.alerts.show(alert)
            graph.log("alert", alert.name)
        } else if (alert == null && shownAlert != null && mode == RecorderMode.Recording) {
            graph.alerts.cancelAlert()
        }
        if (alert != null || mode == RecorderMode.Recording) shownAlert = alert
        RecorderStore.update { it.copy(alert = alert) }
    }

    private fun scheduleReevaluation(atMs: Long?, now: Long) {
        reevaluateJob?.cancel()
        reevaluateJob = atMs?.let {
            lifecycleScope.launch {
                delay((it - now).coerceAtLeast(0))
                reconcile()
            }
        }
    }

    // --- Recording -----------------------------------------------------------------------------

    private fun startRecording(): Boolean {
        val chunkId = UUID.randomUUID().toString()
        val file = graph.recordingDir.audioFile(chunkId)
        val rec = graph.recorderInputFactory.create(this, this)
        val startElapsed = try {
            rec.start(file)
        } catch (e: Exception) {
            file.delete()
            onInterrupted(hardFailure = true, ChunkEndReason.ERROR, "start failed: $e")
            return false
        }
        recorder = rec
        recorderStartedAtElapsed = startElapsed
        val startedAt = System.currentTimeMillis()
        // One session per continuous recording: every pause/resume starts a new one.
        val session = UUID.randomUUID().toString()
        sessionId = session
        sessionStartedAtEpochMs = startedAt
        RecorderStore.update { it.copy(sessionStartedAtEpochMs = startedAt, marksInSession = 0) }
        val chunk = ChunkTracker(
            ChunkJournal(
                chunkId = chunkId,
                sessionId = session,
                deviceId = graph.prefs.deviceId,
                startedAtEpochMs = startedAt,
                timeZone = TimeZone.getDefault().id,
                retryProbe = backoff.sinceMs != null,
            ),
            startElapsed,
        )
        current = chunk
        writeJournal(chunk)
        startTicker()
        graph.log("recording_started", "session=$session chunk=$chunkId")
        return true
    }

    private fun stopRecording(endReason: ChunkEndReason) {
        val rec = recorder ?: return
        recorder = null
        val stoppedAt = rec.elapsedRealtime()
        val clean = rec.stop()
        tickerJob?.cancel()
        tickerJob = null
        pendingNextChunkId?.let { graph.recordingDir.audioFile(it).delete() }
        pendingNextChunkId = null
        sessionId = null
        sessionStartedAtEpochMs = null

        val chunk = current ?: return
        current = null
        val (journal, duration) = chunk.end(stoppedAt)
        if (endReason == ChunkEndReason.INTERRUPTION && journal.retryProbe && duration < InterruptionBackoff.PROBE_DISCARD_MS) {
            // A retry silenced right away: the gap that started with the previous chunk continues.
            graph.scope.launch(graph.chunkIo) { graph.recordingDir.delete(journal.chunkId) }
            graph.log("probe_discarded", journal.chunkId)
            return
        }
        finalizeChunk(journal, duration, endReason, clean)
    }

    override fun onNextFileNeeded(): File? {
        if (recorder == null) return null
        val id = UUID.randomUUID().toString()
        pendingNextChunkId = id
        return graph.recordingDir.audioFile(id)
    }

    override fun onRotated(atElapsedMs: Long) {
        val chunk = current ?: return
        val nextId = pendingNextChunkId ?: return
        if (sessionId == null) return
        pendingNextChunkId = null
        val (journal, duration) = chunk.rotate(nextId, atElapsedMs, TimeZone.getDefault().id)
        writeJournal(chunk)
        finalizeChunk(journal, duration, ChunkEndReason.ROTATION, audioUsable = true)
    }

    override fun onRecorderFailed(detail: String) {
        handler.post { onInterrupted(hardFailure = true, ChunkEndReason.ERROR, detail) }
    }

    /** Closes the chunk (if recording) and schedules a retry with backoff. */
    private fun onInterrupted(hardFailure: Boolean, endReason: ChunkEndReason, detail: String) {
        graph.log("interrupted", detail)
        if (recorder != null) stopRecording(endReason)
        backoff.onInterrupted(SystemClock.elapsedRealtime(), hardFailure)
        requestReconcile()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            var lastHeartbeat = SystemClock.elapsedRealtime()
            while (isActive) {
                // Once per second of audio (a debug file input may play faster than real time).
                delay((SilenceDetector.POLL_INTERVAL_MS / (recorder?.speed ?: 1.0)).toLong().coerceAtLeast(1))
                val rec = recorder ?: break
                val chunk = current ?: continue
                chunk.addAmplitude(rec.pollMaxAmplitude())
                val now = SystemClock.elapsedRealtime()
                if (rec.isSilenced) {
                    onInterrupted(hardFailure = false, ChunkEndReason.INTERRUPTION, "silenced (poll)")
                    break
                }
                if (backoff.sinceMs != null && rec.elapsedRealtime() - recorderStartedAtElapsed >= InterruptionBackoff.HEALTHY_AFTER_MS) {
                    backoff.onHealthy()
                    graph.log("recovered")
                    requestReconcile()
                }
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeat = now
                    chunk.heartbeat(System.currentTimeMillis())
                    writeJournal(chunk)
                    val free = freeStorage()
                    if (free < RecorderPolicy.LOW_STORAGE_BYTES) {
                        conditions = conditions.copy(freeStorageBytes = free)
                        requestReconcile()
                    }
                }
            }
        }
    }

    private fun writeJournal(chunk: ChunkTracker) {
        val journal = chunk.snapshot()
        runCatching { graph.recordingDir.write(journal) }.onFailure { graph.log("journal_failed", it.toString()) }
    }

    private fun finalizeChunk(journal: ChunkJournal, durationMs: Long, endReason: ChunkEndReason, audioUsable: Boolean) {
        val app = applicationContext
        graph.scope.launch(graph.chunkIo) {
            val file = graph.recordingDir.audioFile(journal.chunkId)
            // The previous file of a rotation is finalized by the encoder thread; give it a moment.
            val usable = audioUsable && awaitFinalized(file)
            val result = runCatching { graph.finalizer.finalize(journal, durationMs, endReason, usable) }
                .onFailure { graph.log("finalize_failed", "${journal.chunkId} $it") }
                .getOrNull() ?: return@launch
            val m = result.manifest
            graph.log(
                "chunk_closed",
                "${m.chunkId} ${m.endReason.name.lowercase()} ${m.durationMs}ms bytes=${m.audio?.sizeBytes ?: 0} " +
                    "silent=${result.silent} dropped=${result.audioDropped} marks=${m.markOffsetsMs.size}",
            )
            graph.refreshPendingCount()
            UploadScheduler.uploadNow(app)
        }
    }

    private suspend fun awaitFinalized(file: File): Boolean {
        repeat(FINALIZE_WAIT_STEPS) {
            if (Mp4Probe.isFinalized(file)) return true
            delay(FINALIZE_WAIT_STEP_MS)
        }
        return Mp4Probe.isFinalized(file)
    }

    private fun freeStorage(): Long = graph.outbox.dir.usableSpace

    private fun shutdown() {
        stopMonitors()
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        RecorderStore.update { it.copy(serviceRunning = false, mode = RecorderMode.Stopped, alert = null) }
        StatusSurfaces.refresh(this)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) {
            // Destroyed while recording without a Stop (system shutdown of the service).
            stopRecording(ChunkEndReason.TERMINATED)
            if (graph.prefs.armed) graph.alerts.showTerminated()
        }
        stopMonitors()
        handler.removeCallbacksAndMessages(null)
        RecorderStore.update { it.copy(serviceRunning = false, mode = RecorderMode.Stopped) }
        graph.prefs.lastStatus = RecorderMode.Stopped.statusKey()
        StatusSurfaces.refresh(this)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "app.watchaudio.wear.action.START"
        private const val ACTION_MARK = "app.watchaudio.wear.action.MARK"
        private const val ACTION_STOP = "app.watchaudio.wear.action.STOP"

        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val STATUS_INTERVAL_MS = 5 * 60_000L
        private const val FINALIZE_WAIT_STEPS = 40
        private const val FINALIZE_WAIT_STEP_MS = 250L

        /**
         * Must be called while the app is in the foreground (activity visible): since Android 14
         * a `microphone` foreground service cannot be started from the background.
         */
        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java).setAction(ACTION_START),
        )

        fun mark(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_MARK))

        fun stop(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
    }
}
