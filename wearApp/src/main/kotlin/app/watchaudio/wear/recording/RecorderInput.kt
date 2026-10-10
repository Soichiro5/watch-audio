package app.watchaudio.wear.recording

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Audio input of [RecordingService]: captures audio into AAC chunk files ([AudioFormat]) with
 * gapless rotation and reports the peak level for silence detection.
 *
 * Production always uses the microphone ([ChunkRecorder]). Debug builds can substitute an audio
 * file so the rest of the pipeline (journal, rotation, silence, manifest, outbox) runs on known
 * audio (`src/debug`, docs/impl/wearos.md 「音声ファイル入力（開発用）」).
 */
interface RecorderInput {
    /** Callbacks, delivered on the main thread. */
    interface Listener {
        /** The current file is nearly full: return the file for the next chunk (or null to skip). */
        fun onNextFileNeeded(): File?

        /**
         * The input switched to the file returned by [onNextFileNeeded]; the previous file is
         * closed. [atElapsedMs] is on the [elapsedRealtime] clock.
         */
        fun onRotated(atElapsedMs: Long)

        /** The input failed or stopped on its own; it must be released and restarted. */
        fun onRecorderFailed(detail: String)
    }

    /** Creates an input for one recorder run (a new one after every pause or failure). */
    fun interface Factory {
        fun create(context: Context, listener: Listener): RecorderInput
    }

    /** Audio session used to match this input in `AudioManager` recording callbacks, if any. */
    val audioSessionId: Int?

    /** True while another client has silenced this input (Android 10+ concurrent capture). */
    val isSilenced: Boolean

    /**
     * Audio time per real time. Always 1 for the microphone; a debug file input may play faster,
     * and the level is then polled correspondingly more often (once per second of audio).
     */
    val speed: Double get() = 1.0

    /**
     * Clock of the captured audio in milliseconds: chunk durations and mark offsets are measured
     * on it. Real time ([SystemClock.elapsedRealtime]) for the microphone.
     */
    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    /**
     * Starts capturing into [file]. Throws when the input cannot be opened.
     * @return [elapsedRealtime] at which capture started.
     */
    fun start(file: File): Long

    /** Peak amplitude since the previous call (0..32767), 0 when not capturing. */
    fun pollMaxAmplitude(): Int

    /**
     * Stops and releases the input.
     * @return true when the current file was finalized cleanly (decodable).
     */
    fun stop(): Boolean

    companion object {
        /** The production input: the microphone. */
        val Microphone = Factory { context, listener -> ChunkRecorder(context, listener) }
    }
}
