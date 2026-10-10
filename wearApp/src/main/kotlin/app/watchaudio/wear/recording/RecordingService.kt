package app.watchaudio.wear.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.watchaudio.wear.MainActivity
import app.watchaudio.wear.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground recorder for the M0-4 spike (#10): records 16 kHz mono AAC in ~5 minute
 * chunks, logs battery every 5 minutes and optionally sends closed chunks to the phone.
 *
 * Must be started while the activity is in the foreground: since Android 14 a
 * `microphone` foreground service cannot be started from the background.
 */
class RecordingService : LifecycleService() {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var chunkIndex = 0
    private var batteryJob: Job? = null
    private lateinit var log: SpikeLog
    private lateinit var sessionDir: File
    private lateinit var sender: ChunkSender
    private var sendChunks = true
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            // Another client taking the mic silences us (Android 10+ concurrent capture rules).
            val silenced = configs.any { it.clientAudioSessionId == recorder?.activeRecordingConfiguration?.clientAudioSessionId && it.isClientSilenced }
            if (silenced) logEvent("silenced", "another app is using the microphone")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent.getBooleanExtra(EXTRA_SEND_CHUNKS, true))
            ACTION_MARK -> mark()
            ACTION_STOP -> stop("user")
        }
        return START_NOT_STICKY
    }

    private fun start(sendChunks: Boolean) {
        if (RecorderStore.state.value.isRecording) return
        this.sendChunks = sendChunks
        val sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        sessionDir = File(getExternalFilesDir(null), "spike/$sessionId").apply { mkdirs() }
        log = SpikeLog(sessionDir, sessionId)
        sender = ChunkSender(this)
        chunkIndex = 0

        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val battery = batteryPercent()
        RecorderStore.update {
            RecorderState(isRecording = true, startedAtMillis = System.currentTimeMillis(), batteryAtStart = battery, batteryNow = battery)
        }
        audioManager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
        startRecorder()
        logEvent("start", "sendChunks=$sendChunks bitrate=$BITRATE")

        batteryJob = lifecycleScope.launch {
            while (isActive) {
                delay(BATTERY_LOG_INTERVAL_MS)
                RecorderStore.update { it.copy(batteryNow = batteryPercent()) }
                logEvent("battery")
            }
        }
    }

    private fun startRecorder() {
        val file = nextFile()
        currentFile = file
        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioChannels(1)
            setAudioEncodingBitRate(BITRATE)
            // Rotate by size (~5 min at BITRATE); setNextOutputFile keeps the split gapless.
            setMaxFileSize(CHUNK_BYTES)
            setOutputFile(file)
            setOnInfoListener { _, what, _ -> onRecorderInfo(what) }
            setOnErrorListener { _, what, extra -> logEvent("error", "what=$what extra=$extra") }
            prepare()
            start()
        }
    }

    private fun onRecorderInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                val next = nextFile()
                recorder?.setNextOutputFile(next)
                pendingNext = next
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                currentFile?.let(::onChunkClosed)
                currentFile = pendingNext
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> logEvent("max_size_reached", "next file was not ready")
        }
    }

    private var pendingNext: File? = null

    private fun nextFile() = File(sessionDir, "chunk-%04d.m4a".format(chunkIndex++))

    private fun onChunkClosed(file: File) {
        RecorderStore.update { it.copy(chunksClosed = it.chunksClosed + 1, bytesRecorded = it.bytesRecorded + file.length()) }
        logEvent("chunk_closed", file.name)
        if (!sendChunks) return
        lifecycleScope.launch {
            val sent = runCatching { sender.send(file) }
                .onFailure { logEvent("send_failed", it.message.orEmpty()) }
                .getOrDefault(false)
            if (sent) {
                RecorderStore.update { it.copy(chunksSent = it.chunksSent + 1) }
                logEvent("chunk_sent", file.name)
            }
        }
    }

    private fun mark() {
        if (!RecorderStore.state.value.isRecording) return
        RecorderStore.update { it.copy(marks = it.marks + 1) }
        logEvent("mark")
    }

    private fun stop(reason: String) {
        if (!RecorderStore.state.value.isRecording) return
        runCatching { recorder?.stop() }.onFailure { logEvent("stop_failed", it.message.orEmpty()) }
        recorder?.release()
        recorder = null
        currentFile?.let(::onChunkClosed)
        currentFile = null
        batteryJob?.cancel()
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        RecorderStore.update { it.copy(isRecording = false, batteryNow = batteryPercent()) }
        logEvent("stop", reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop("service_destroyed")
        super.onDestroy()
    }

    private fun logEvent(event: String, detail: String = "") {
        RecorderStore.update { it.copy(lastEvent = event) }
        if (::log.isInitialized) log.append(RecorderStore.state.value, isCharging(), event, detail)
    }

    private fun batteryPercent(): Int =
        getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun isCharging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_title))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setContentIntent(openApp)

        // Shows the recording state on the watch face / launcher while the app is not visible.
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.ic_btn_speak_now)
            .setTouchIntent(openApp)
            .setStatus(
                Status.Builder()
                    .addTemplate("#elapsed#")
                    .addPart("elapsed", Status.StopwatchPart(System.currentTimeMillis()))
                    .build(),
            )
            .build()
            .apply(this)
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_MARK = "mark"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_SEND_CHUNKS = "send_chunks"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16_000
        private const val BITRATE = 32_000
        private const val CHUNK_BYTES = BITRATE / 8L * 300 // ~5 minutes
        private const val BATTERY_LOG_INTERVAL_MS = 5 * 60_000L

        fun start(context: Context, sendChunks: Boolean) = ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_SEND_CHUNKS, sendChunks),
        )

        fun mark(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_MARK))

        fun stop(context: Context) = context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
    }
}
