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
import android.provider.DocumentsContract
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
import app.watchaudio.app.BACKUP_MIME_TYPE
import app.watchaudio.app.PermissionStatus
import app.watchaudio.app.Strings
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.PauseReason
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StatusNotice
import app.watchaudio.core.pipeline.StorageManager
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
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
    scope: CoroutineScope,
    override val diagnostics: DiagnosticsLog,
    private val onGapAlertSettingsChanged: () -> Unit,
) : AppServices {
    override val audioRoot: String = audioRootDir.absolutePath

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
    }.stateIn(scope, SharingStarted.Eagerly, ProcessingStatus())

    private var player: MediaPlayer? = null

    // Permission prompts go through the visible activity; main thread only.
    private var permissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var notificationsRefused = false

    // The save dialogs of the data export (#155) and the backup (#40), by MIME type, and the
    // backup's open dialog go through the visible activity; main thread only.
    private val saveLaunchers = mutableMapOf<String, ActivityResultLauncher<String>>()
    private var pendingExport: CompletableDeferred<Uri?>? = null
    private var openBackupLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingBackup: CompletableDeferred<Uri?>? = null

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
            val dir = File(context.cacheDir, AppGraph.DIAGNOSTICS_EXPORT_DIR).apply {
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

    /** The save dialog for documents of [mimeType] ([EXPORT_MIME_TYPE] or [BACKUP_MIME_TYPE]). */
    fun attachSaveLauncher(mimeType: String, launcher: ActivityResultLauncher<String>) {
        saveLaunchers[mimeType] = launcher
    }

    fun detachSaveLauncher(mimeType: String, launcher: ActivityResultLauncher<String>) {
        if (saveLaunchers[mimeType] === launcher) saveLaunchers.remove(mimeType)
    }

    /** The document the user created in the save dialog started by [saveExport], or null if they cancelled. */
    fun onExportDestination(uri: Uri?) {
        pendingExport?.complete(uri)
        pendingExport = null
    }

    /**
     * Opens the system's save dialog (SAF `ACTION_CREATE_DOCUMENT` for [mimeType]: the data export
     * or an encrypted backup) and streams the file straight into the document the user created
     * there: no copy in the app's storage, and the only place it is written is the one they
     * chose. A failed or cancelled write deletes the partial document.
     */
    override suspend fun saveExport(fileName: String, mimeType: String, write: suspend (RawSink) -> Unit): Boolean {
        val uri = withContext(Dispatchers.Main) {
            val launcher = saveLaunchers[mimeType] ?: error("no save dialog for $mimeType (no visible activity, or an unknown type)")
            val result = CompletableDeferred<Uri?>()
            pendingExport?.complete(null)
            pendingExport = result
            launcher.launch(fileName)
            result
        }.await() ?: return false
        try {
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri, "w") ?: throw FileNotFoundException("no stream for the chosen document")
                stream.asSink().use { write(it) }
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
            throw e
        }
        return true
    }

    fun attachOpenBackupLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        openBackupLauncher = launcher
    }

    fun detachOpenBackupLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        if (openBackupLauncher === launcher) openBackupLauncher = null
    }

    /** The document the user picked in the dialog started by [openBackup], or null if they cancelled. */
    fun onBackupPicked(uri: Uri?) {
        pendingBackup?.complete(uri)
        pendingBackup = null
    }

    /**
     * Opens the system's file picker (SAF `ACTION_OPEN_DOCUMENT`; any type, since backups have no
     * registered one) and lets the restore read the picked document directly, as often as it
     * needs. Nothing is copied into the app's storage, and the document is only read.
     */
    override suspend fun openBackup(read: suspend (open: () -> RawSource) -> Unit): Boolean {
        val uri = withContext(Dispatchers.Main) {
            val launcher = openBackupLauncher ?: error("no visible activity to show the file picker from")
            val result = CompletableDeferred<Uri?>()
            pendingBackup?.complete(null)
            pendingBackup = result
            launcher.launch(arrayOf("*/*"))
            result
        }.await() ?: return false
        withContext(Dispatchers.IO) {
            read {
                val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no stream for the picked document")
                stream.asSource()
            }
        }
        return true
    }

    /** Only called from the user's tap: the run waives the battery rules (not the thermal one, #75). */
    override fun requestTranscription() = scheduler.requestForUser()

    override fun transcriptionSettingsChanged() = scheduler.settingsChanged()

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

    companion object {
        /** The data export (#155) is one ZIP archive; [MainActivity] registers its save dialog for it. */
        const val EXPORT_MIME_TYPE = "application/zip"

        private const val TAG = "AndroidAppServices"

        /** Must match the diagnostics FileProvider in the manifest. */
        const val DIAGNOSTICS_AUTHORITY_SUFFIX = ".diagnostics"
    }
}
