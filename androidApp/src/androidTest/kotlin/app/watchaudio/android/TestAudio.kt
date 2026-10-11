package app.watchaudio.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Test fixtures: real AAC audio in an MP4 container, like the watch's 16 kHz mono chunks. */
object TestAudio {
    private const val TIMEOUT_US = 10_000L

    /** Encodes [durationMs] of a quiet two-tone signal to [file] (AAC-LC, 16 kHz, mono). */
    fun writeM4a(file: File, durationMs: Long, sampleRate: Int = 16_000) =
        encode(file, sampleRate * durationMs / 1000, sampleRate) { n ->
            val t = n.toDouble() / sampleRate
            val tone = if ((t.toLong() % 2L) == 0L) 440.0 else 660.0
            (sin(2 * PI * tone * t) * 4_000).toInt().toShort()
        }

    /** Encodes 16-bit PCM [samples] (mono, [sampleRate]) to [file] as AAC-LC, like a watch chunk. */
    fun writeM4a(file: File, samples: ShortArray, sampleRate: Int = 16_000) =
        encode(file, samples.size.toLong(), sampleRate) { n -> samples[n.toInt()] }

    private fun encode(file: File, totalSamples: Long, sampleRate: Int, sample: (Long) -> Short) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var queued = 0L
            var inputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.clear()
                        val count = minOf(buffer.capacity() / 2L, totalSamples - queued).toInt()
                        val ptsUs = queued * 1_000_000 / sampleRate
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            repeat(count) { i -> buffer.putShort(sample(queued + i)) }
                            codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
                            queued += count
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (out >= 0) {
                    val data = codec.getOutputBuffer(out)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
            muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
    }
}
