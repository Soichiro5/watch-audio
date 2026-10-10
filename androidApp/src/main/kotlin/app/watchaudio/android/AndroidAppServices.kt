package app.watchaudio.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import app.watchaudio.android.asr.ModelManager
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.app.AppServices
import app.watchaudio.app.ModelState
import app.watchaudio.app.ProcessingStatus
import app.watchaudio.core.db.LocalStore
import java.io.File
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
    audioRootDir: File,
    private val models: ModelManager,
    private val scheduler: TranscriptionScheduler,
    lastChunkReceivedAt: StateFlow<Long?>,
    scope: CoroutineScope,
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

    override fun requestTranscription() = scheduler.requestNow()

    /**
     * Starts downloading the speech models. Without [allowMetered] it waits for Wi-Fi; pass true
     * only after the user agreed to use mobile data (the archive is several hundred MB).
     */
    fun downloadModels(allowMetered: Boolean) = models.download(allowMetered)

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
