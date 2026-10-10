package app.watchaudio.android.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.LinearResampler
import app.watchaudio.core.pipeline.downmixToMono
import app.watchaudio.core.pipeline.pcm16ToFloat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes an archived chunk (AAC in .m4a, or Opus in .ogg) with the platform codecs into
 * 16 kHz mono float PCM, the input sherpa-onnx expects. Stereo is downmixed and other sample
 * rates are linearly resampled.
 */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /** Polls without output after end of input before giving up (~5 s). */
    private const val MAX_IDLE_POLLS = 500

    /** [checkActive] is called between codec buffers and may throw to abort. */
    fun decode(file: File, checkActive: () -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, format, checkActive)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, checkActive: () -> Unit): FloatArray {
        var output = PcmShape(
            sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            encoding = AudioFormat.ENCODING_PCM_16BIT,
        )
        var resampler = LinearResampler(output.sampleRate, AsrSettings.SAMPLE_RATE)
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val result = FloatBuffer((durationUs * AsrSettings.SAMPLE_RATE / 1_000_000).toInt() + AsrSettings.SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var scratch = FloatArray(0)
        var idleAfterInput = 0

        while (true) {
            checkActive()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idleAfterInput > MAX_IDLE_POLLS) {
                throw IOException("decoder stalled before end of stream")
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    output = PcmShape(
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        },
                    )
                    resampler = LinearResampler(output.sampleRate, AsrSettings.SAMPLE_RATE)
                }
                index >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val count = when (output.encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> info.size / 4
                            AudioFormat.ENCODING_PCM_16BIT -> info.size / 2
                            else -> throw IOException("unsupported PCM encoding ${output.encoding}")
                        }
                        if (scratch.size < count) scratch = FloatArray(count)
                        if (output.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            buffer.asFloatBuffer().get(scratch, 0, count)
                        } else {
                            val shorts = buffer.asShortBuffer()
                            for (i in 0 until count) scratch[i] = pcm16ToFloat(shorts.get(i))
                        }
                        val frames = count / output.channels
                        result.append(resampler.process(downmixToMono(scratch, frames, output.channels)))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return result.toArray()
                }
            }
        }
    }

    private data class PcmShape(val sampleRate: Int, val channels: Int, val encoding: Int)

    /** Growable float array; avoids boxing millions of samples. */
    private class FloatBuffer(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        fun append(values: FloatArray) {
            if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
            values.copyInto(data, size)
            size += values.size
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
