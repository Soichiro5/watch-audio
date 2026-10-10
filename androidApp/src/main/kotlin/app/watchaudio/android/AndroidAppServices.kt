package app.watchaudio.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import app.watchaudio.android.asr.ModelManager
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.app.AppPermission
import app.watchaudio.app.AppServices
import app.watchaudio.app.ModelState
import app.watchaudio.app.PermissionStatus
import app.watchaudio.app.ProcessingStatus
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.StorageManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * [AppServices] for Android: the shared UI reads data from [store] and status from [status];
 * transcription runs in WorkManager and playback uses [MediaPlayer].
 */
class AndroidAppServices(
    private val context: Context,
    override val store: LocalStore,
    override val storage: StorageManager,
    audioRootDir: File,
    private val models: ModelManager,
    private val scheduler: TranscriptionScheduler,
    lastChunkReceivedAt: StateFlow<Long?>,
    scope: CoroutineScope,
    private val onGapAlertSettingsChanged: () -> Unit,
) : AppServices {
    override val audioRoot: String = audioRootDir.absolutePath

    override val status: StateFlow<ProcessingStatus> = combine(
        models.state,
        models.downloadWaiting,
        scheduler.running,
        lastChunkReceivedAt,
    ) { model, waiting, running, lastChunk ->
        ProcessingStatus(
            asrModel = model,
            transcribing = running,
            lastChunkReceivedAt = lastChunk,
            message = if (model == ModelState.Missing && waiting) context.getString(R.string.model_waiting_for_wifi) else null,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ProcessingStatus())

    private var player: MediaPlayer? = null

    // Permission prompts go through the visible activity; main thread only.
    private var permissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var notificationsRefused = false

    /** POST_NOTIFICATIONS is a runtime permission from Android 13 (API 33). */
    override val permissions: List<AppPermission> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(AppPermission.NOTIFICATIONS) else emptyList()

    override suspend fun permissionStatus(permission: AppPermission): PermissionStatus = when {
        permission !in permissions || notificationsGranted() -> PermissionStatus.GRANTED
        notificationsRefused -> PermissionStatus.DENIED
        else -> PermissionStatus.NOT_REQUESTED
    }

    override suspend fun requestPermission(permission: AppPermission): PermissionStatus {
        val launcher = permissionLauncher
        if (permission in permissions && !notificationsGranted() && launcher != null) {
            val result = CompletableDeferred<Boolean>()
            pendingPermission?.cancel()
            pendingPermission = result
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            // After repeated refusals Android answers false at once without showing a prompt.
            if (!result.await()) notificationsRefused = true
        }
        return permissionStatus(permission)
    }

    fun attachPermissionLauncher(launcher: ActivityResultLauncher<String>) {
        permissionLauncher = launcher
    }

    fun detachPermissionLauncher(launcher: ActivityResultLauncher<String>) {
        if (permissionLauncher === launcher) permissionLauncher = null
    }

    /** Result of a prompt started by [requestPermission] (delivered to the current activity). */
    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun gapAlertSettingsChanged() = onGapAlertSettingsChanged()

    override fun requestTranscription() = scheduler.requestNow()

    /**
     * Starts downloading the speech models. Without [allowMetered] it waits for Wi-Fi; pass true
     * only after the user agreed to use mobile data (the archive is several hundred MB).
     */
    override fun downloadModels(allowMetered: Boolean) {
        models.download(allowMetered)
    }

    /** Call on the main thread. */
    override fun play(audioPath: String, offsetMs: Long) {
        stopPlayback()
        val file = File(audioRoot, audioPath)
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnPreparedListener { prepared ->
                prepared.seekTo(offsetMs.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
                prepared.start()
            }
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "playback of $audioPath failed ($what/$extra)")
                stopPlayback()
                true
            }
        }
        try {
            mediaPlayer.setDataSource(file.path)
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (e: Exception) {
            Log.w(TAG, "cannot play $audioPath", e)
            mediaPlayer.release()
        }
    }

    override fun stopPlayback() {
        player?.release()
        player = null
    }

    private companion object {
        const val TAG = "AndroidAppServices"
    }
}
