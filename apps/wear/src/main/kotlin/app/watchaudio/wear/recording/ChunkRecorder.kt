package app.watchaudio.wear.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/**
 * `MediaRecorder` producing AAC-LC 16 kHz mono chunks with gapless rotation: when a file
 * approaches [AudioFormat.CHUNK_BYTES] the next file is handed over with `setNextOutputFile`, and
 * the encoder switches to it without dropping samples.
 *
 * Rotation is size based because `setNextOutputFile` only works with the file size limit; at a
 * constant bitrate that is ~5 minutes. All callbacks arrive on the thread that created the
 * recorder (the service's main thread). The production [RecorderInput].
 */
class ChunkRecorder(
    private val context: Context,
    private val listener: RecorderInput.Listener,
) : RecorderInput {
    private var recorder: MediaRecorder? = null

    override val audioSessionId: Int?
        get() = runCatching { recorder?.activeRecordingConfiguration?.clientAudioSessionId }.getOrNull()

    override val isSilenced: Boolean
        get() = runCatching { recorder?.activeRecordingConfiguration?.isClientSilenced }.getOrNull() == true

    /** Starts recording into [file]. Throws when the microphone cannot be opened. */
    override fun start(file: File): Long {
        check(recorder == null) { "already started" }
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(AudioFormat.SAMPLE_RATE_HZ)
            r.setAudioChannels(AudioFormat.CHANNELS)
            r.setAudioEncodingBitRate(AudioFormat.BITRATE_BPS)
            r.setMaxFileSize(AudioFormat.CHUNK_BYTES)
            r.setOutputFile(file)
            r.setOnInfoListener { _, what, extra -> onInfo(what, extra) }
            r.setOnErrorListener { _, what, extra -> listener.onRecorderFailed("error what=$what extra=$extra") }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        return SystemClock.elapsedRealtime()
    }

    override fun pollMaxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        // stop() throws when no audio was captured yet; the file is then unusable.
        val clean = runCatching { r.stop() }.isSuccess
        r.release()
        return clean
    }

    private fun onInfo(what: Int, extra: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                val next = listener.onNextFileNeeded() ?: return
                runCatching { recorder?.setNextOutputFile(next) }
                    .onFailure { listener.onRecorderFailed("setNextOutputFile failed: ${it.message}") }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED ->
                listener.onRotated(SystemClock.elapsedRealtime())
            // The next file was not ready in time: the recorder has stopped on its own.
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
            -> listener.onRecorderFailed("recorder stopped (info what=$what extra=$extra)")
        }
    }
}
