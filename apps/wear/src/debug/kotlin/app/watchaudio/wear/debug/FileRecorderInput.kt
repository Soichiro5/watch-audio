package app.watchaudio.wear.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.watchaudio.wear.recording.RecorderInput
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One playback of an audio file across recorder runs: a run that pauses (charging, off-body,
 * interruption) and resumes continues where the previous run stopped.
 *
 * @property speed audio time per real time (1 = real time).
 * @property loop start over at the end instead of stopping the recording.
 */
class FilePlayback(
    val file: File,
    val speed: Double,
    val loop: Boolean,
    private val onFinished: () -> Unit,
) {
    /** 16 kHz samples already played (resume point). */
    @Volatile
    var positionSamples = 0L
        internal set

    /** Opens the file at the resume point. Throws when it cannot be decoded. */
    fun open(fromStart: Boolean = false): MonoPcmSource {
        val source = openSource(file)
        if (!fromStart) source.skip(positionSamples)
        return source
    }

    internal fun finished() = onFinished()

    companion object {
        /** 16-bit WAV is read directly; everything else goes through the platform decoder. */
        fun openSource(file: File): MonoPcmSource {
            val header = ByteArray(12)
            val read = FileInputStream(file).use { it.read(header) }
            val wav = read == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            return if (wav) WavPcmSource(FileInputStream(file)) else MediaPcmSource(file)
        }
    }
}

/**
 * Debug-only [RecorderInput]: plays [playback] instead of capturing the microphone. A writer
 * thread decodes the file, paces it at [FilePlayback.speed] and encodes it with [AacChunkSink]
 * via [PcmChunker], which rotates every 5 minutes of audio like the size-based rotation of the
 * microphone recorder. The clock is the audio position, so chunk durations and mark offsets are
 * exact at any speed.
 *
 * At the end of the file the input stops producing audio and asks the service to stop
 * ([FilePlayback.finished]), which closes the last chunk as `user_stop`.
 */
class FileRecorderInput(
    private val listener: RecorderInput.Listener,
    private val playback: FilePlayback,
) : RecorderInput {
    private val main = Handler(Looper.getMainLooper())
    private var chunker: PcmChunker? = null
    private var thread: Thread? = null
    private var baseElapsedMs = 0L

    @Volatile
    private var stopRequested = false

    override val audioSessionId: Int? = null
    override val isSilenced: Boolean = false
    override val speed: Double get() = playback.speed

    override fun elapsedRealtime(): Long {
        val c = chunker ?: return SystemClock.elapsedRealtime()
        return baseElapsedMs + c.positionMs
    }

    override fun start(file: File): Long {
        check(chunker == null) { "already started" }
        val source = playback.open()
        val c = PcmChunker(AacChunkSink(), Events())
        try {
            c.start(file)
        } catch (e: Exception) {
            source.close()
            throw e
        }
        baseElapsedMs = SystemClock.elapsedRealtime()
        chunker = c
        thread = Thread({ play(source, c) }, "debug-file-input").apply { start() }
        return baseElapsedMs
    }

    override fun pollMaxAmplitude(): Int = chunker?.pollPeak() ?: 0

    override fun stop(): Boolean {
        val c = chunker ?: return false
        stopRequested = true
        thread?.join(STOP_TIMEOUT_MS)
        if (thread?.isAlive == true) return false
        return runCatching { c.finish() }.getOrDefault(false)
    }

    private fun play(initial: MonoPcmSource, c: PcmChunker) {
        var source = initial
        val buffer = ShortArray(BLOCK_SAMPLES)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (!stopRequested) {
                val n = source.read(buffer)
                if (n < 0) {
                    if (playback.loop && playback.positionSamples > 0) {
                        source.close()
                        source = playback.open(fromStart = true)
                        continue
                    }
                    // Keep the chunk open: the service closes it (user_stop) when it stops.
                    main.post { playback.finished() }
                    return
                }
                if (!c.write(buffer, n)) {
                    if (!stopRequested) main.post { listener.onRecorderFailed("debug file input: no next file") }
                    return
                }
                playback.positionSamples += n
                val due = startedAt + (c.positionMs / playback.speed).toLong()
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
            // stop() timed out
        } catch (e: Exception) {
            if (!stopRequested) main.post { listener.onRecorderFailed("debug file input: $e") }
        } finally {
            runCatching { source.close() }
        }
    }

    /** Called on the writer thread; the listener lives on the main thread. */
    private inner class Events : PcmChunker.Events {
        override fun nextFile(): File? {
            val result = AtomicReference<File?>()
            val done = CountDownLatch(1)
            main.post {
                result.set(runCatching { listener.onNextFileNeeded() }.getOrNull())
                done.countDown()
            }
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                if (stopRequested) return null
            }
            return result.get()
        }

        override fun rotated(positionMs: Long) {
            val at = baseElapsedMs + positionMs
            main.post { listener.onRotated(at) }
        }
    }

    private companion object {
        /** 20 ms of audio per write. */
        const val BLOCK_SAMPLES = 320
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
