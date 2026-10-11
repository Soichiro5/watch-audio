package app.watchaudio.wear.debug

import app.watchaudio.core.protocol.RecordingFormat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Encodes 16 kHz mono PCM into one chunk file at a time. */
interface ChunkSink {
    /** Starts a new file. Throws when the encoder cannot be set up. */
    fun open(file: File)

    fun write(samples: ShortArray, offset: Int, count: Int)

    /**
     * Finalizes the current file.
     * @return true when it is complete and decodable.
     */
    fun close(): Boolean
}

/**
 * Writes a 16 kHz mono PCM stream into chunk files the way the microphone recorder does: a new
 * file every [chunkDurationMs] of audio (the size-based rotation of `MediaRecorder` amounts to
 * [RecordingFormat.CURRENT.chunkDurationMs]), the peak level since the previous poll (like
 * `MediaRecorder.getMaxAmplitude()`), and the audio position as the clock.
 *
 * Called from one writer thread; [positionMs] and [pollPeak] may be read from any thread.
 */
class PcmChunker(
    private val sink: ChunkSink,
    private val events: Events,
    chunkDurationMs: Long = RecordingFormat.CURRENT.chunkDurationMs,
) {
    interface Events {
        /** The current file is full: the file for the next chunk, or null to stop. */
        fun nextFile(): File?

        /** The previous file is closed; the next chunk starts at [positionMs]. */
        fun rotated(positionMs: Long)
    }

    private val chunkSamples = chunkDurationMs * RecordingFormat.CURRENT.sampleRateHz / 1000
    private var samplesInChunk = 0L
    private val peak = AtomicInteger(0)

    @Volatile
    var positionSamples = 0L
        private set

    /** Audio written so far, in ms. */
    val positionMs: Long get() = positionSamples * 1000 / RecordingFormat.CURRENT.sampleRateHz

    fun start(file: File) = sink.open(file)

    /**
     * Writes the first [count] samples, rotating at chunk boundaries.
     * @return false when no next file was available (the input has to stop).
     */
    fun write(samples: ShortArray, count: Int = samples.size): Boolean {
        var offset = 0
        while (offset < count) {
            if (samplesInChunk >= chunkSamples && !rotate()) return false
            val n = minOf((count - offset).toLong(), chunkSamples - samplesInChunk).toInt()
            sink.write(samples, offset, n)
            var max = 0
            for (i in offset until offset + n) max = maxOf(max, abs(samples[i].toInt()))
            peak.accumulateAndGet(max.coerceAtMost(Short.MAX_VALUE.toInt()), ::maxOf)
            offset += n
            samplesInChunk += n
            positionSamples += n
        }
        return true
    }

    /** Peak amplitude (0..32767) since the previous call. */
    fun pollPeak(): Int = peak.getAndSet(0)

    /** Finalizes the current file; see [ChunkSink.close]. */
    fun finish(): Boolean = sink.close()

    private fun rotate(): Boolean {
        val next = events.nextFile() ?: return false
        sink.close()
        sink.open(next)
        samplesInChunk = 0
        events.rotated(positionMs)
        return true
    }
}
