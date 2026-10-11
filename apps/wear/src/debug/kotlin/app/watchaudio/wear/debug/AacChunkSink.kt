package app.watchaudio.wear.debug

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.watchaudio.core.protocol.RecordingFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Encodes PCM into the same format `MediaRecorder` writes in production (AAC-LC, 16 kHz mono,
 * 24 kbps in MPEG-4) with the platform encoder and `MediaMuxer`. One encoder per file: closing
 * writes the `moov` box, so the file passes the same [app.watchaudio.wear.recording.Mp4Probe]
 * check as a microphone chunk.
 */
class AacChunkSink : ChunkSink {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var samplesQueued = 0L
    private var framesWritten = 0
    private val info = MediaCodec.BufferInfo()

    override fun open(file: File) {
        check(codec == null) { "previous file not closed" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RecordingFormat.CURRENT.sampleRateHz, RecordingFormat.CURRENT.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, RecordingFormat.CURRENT.bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val m = try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            runCatching { c.stop() }
            c.release()
            throw e
        }
        codec = c
        muxer = m
        track = -1
        samplesQueued = 0
        framesWritten = 0
    }

    override fun write(samples: ShortArray, offset: Int, count: Int) {
        val c = codec ?: return
        val m = muxer ?: return
        var position = offset
        val end = offset + count
        while (position < end) {
            val index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(c, m, endOfStream = false)
                continue
            }
            val buffer = c.getInputBuffer(index)!!
            buffer.clear()
            val n = minOf(end - position, buffer.remaining() / 2)
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, position, n)
            c.queueInputBuffer(index, 0, n * 2, presentationUs(samplesQueued), 0)
            samplesQueued += n
            position += n
            drain(c, m, endOfStream = false)
        }
    }

    override fun close(): Boolean {
        val c = codec ?: return false
        val m = muxer ?: return false
        codec = null
        muxer = null
        var complete = runCatching {
            var queued = false
            var attempts = 0
            while (!queued && attempts++ < MAX_IDLE_POLLS) {
                val index = c.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    c.queueInputBuffer(index, 0, 0, presentationUs(samplesQueued), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drain(c, m, endOfStream = false)
                }
            }
            queued && drain(c, m, endOfStream = true)
        }.getOrDefault(false)
        runCatching { c.stop() }
        c.release()
        // stop() writes the moov box; it throws when the muxer never started (no audio).
        complete = complete && framesWritten > 0 && track >= 0 && runCatching { m.stop() }.isSuccess
        runCatching { m.release() }
        return complete
    }

    /** Moves encoded frames to the muxer. With [endOfStream], waits for the end and returns whether it came. */
    private fun drain(c: MediaCodec, m: MediaMuxer, endOfStream: Boolean): Boolean {
        var idle = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++idle > MAX_IDLE_POLLS) return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                }
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && track >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        m.writeSampleData(track, buffer, info)
                        framesWritten++
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun presentationUs(samples: Long) = samples * 1_000_000 / RecordingFormat.CURRENT.sampleRateHz

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_BYTES = 16 * 1024
        const val MAX_IDLE_POLLS = 500
    }
}
