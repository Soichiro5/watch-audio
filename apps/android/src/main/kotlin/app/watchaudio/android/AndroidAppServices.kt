package app.watchaudio.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.watchaudio.android.asr.ModelManager
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.app.AppPermission
import app.watchaudio.app.AppServices
import app.watchaudio.app.PermissionStatus
import app.watchaudio.app.Strings
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.PauseReason
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StatusNotice
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.settings.loadSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

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
    transcriptionPause: StateFlow<PauseReason?>,
    private val scope: CoroutineScope,
    override val diagnostics: DiagnosticsLog,
    private val onGapAlertSettingsChanged: () -> Unit,
) : AppServices {
    override val audioRoot: String = audioRootDir.absolutePath

    /** The English model's state and whether it waits for Wi-Fi, while 「英語も文字起こしする」 is on (#81). */
    private val english = combine(
        store.changes.map { store.loadSettings().transcribeEnglish }.distinctUntilChanged(),
        models.englishState,
        models.englishDownloadWaiting,
    ) { enabled, model, waiting -> if (enabled) model to (model == ModelState.Missing && waiting) else null to false }

    override val status: StateFlow<ProcessingStatus> = combine(
        models.state,
        models.downloadWaiting,
        scheduler.running,
        lastChunkReceivedAt,
        transcriptionPause,
    ) { model, waiting, running, lastChunk, paused ->
        ProcessingStatus(
            asrModel = model,
            transcribing = running,
            lastChunkReceivedAt = lastChunk,
            notice = if (model == ModelState.Missing && waiting) StatusNotice.ModelWaitingForWifi else null,
            paused = paused,
        )
    }.combine(english) { status, (englishModel, englishWaiting) ->
        status.copy(englishModel = englishModel, englishModelWaitingForWifi = englishWaiting)
    }.stateIn(scope, SharingStarted.Eagerly, ProcessingStatus())

    /** English needs the extra SenseVoice model ([ModelManager.englishFiles]). */
    override val supportsEnglish: Boolean get() = true

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

    /** The app's page in the system settings ("App info"), where notifications can be allowed. */
    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no settings screen for the app", e)
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun gapAlertSettingsChanged() = onGapAlertSettingsChanged()

    /**
     * Copies the diagnostics log into the cache (`cache/diagnostics-export/`, replacing earlier
     * exports) and offers it through the share sheet; the user picks the destination. Read access
     * is granted only to the app they pick, through [FileProvider].
     */
    override suspend fun exportDiagnostics(): Boolean {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIAGNOSTICS_EXPORT_DIR).apply {
                deleteRecursively()
                mkdirs()
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(dir, "watch-audio-diagnostics-$stamp.csv")
            target.takeIf { diagnostics.exportTo(Path(it.path)) > 0 }
        } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$DIAGNOSTICS_AUTHORITY_SUFFIX", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The grant must also reach the chooser's preview.
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, Strings.EXPORT_DIAGNOSTICS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        withContext(Dispatchers.Main) { context.startActivity(chooser) }
        return true
    }

    /** Only called from the user's tap: the run waives the battery rules (not the thermal one, #75). */
    override fun requestTranscription() = scheduler.requestForUser()

    /** Re-plans the transcription work and fetches or removes the English model to match the setting. */
    override fun transcriptionSettingsChanged() {
        scheduler.settingsChanged()
        scope.launch { models.syncEnglish(store.loadSettings().transcribeEnglish) }
    }

    /** The user asked for the English model now: also over mobile data (~240 MB). */
    override fun downloadEnglishModel(allowMetered: Boolean) {
        scope.launch { models.syncEnglish(store.loadSettings().transcribeEnglish, allowMetered) }
    }

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

    /**
     * Position of the current playback in ms while audio is actually playing, else null.
     * Lets instrumented tests verify real playback (#19, #25). Call on the main thread.
     */
    @VisibleForTesting
    internal fun playingPositionMs(): Int? =
        runCatching { player?.takeIf { it.isPlaying }?.currentPosition }.getOrNull()

    private companion object {
        const val TAG = "AndroidAppServices"

        /** Must match `res/xml/diagnostics_paths.xml` and the provider in the manifest. */
        const val DIAGNOSTICS_EXPORT_DIR = "diagnostics-export"
        const val DIAGNOSTICS_AUTHORITY_SUFFIX = ".diagnostics"
    }
}
