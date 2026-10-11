package app.watchaudio.wear.debug

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio file the platform supports (m4a/AAC, mp3, ogg/Opus, flac, ...) with
 * `MediaExtractor` + `MediaCodec`; the PCM is converted to 16 kHz mono by [ConvertingPcmSource].
 */
class MediaPcmSource(file: File) : ConvertingPcmSource() {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var sampleRate: Int
    private var channels: Int
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()

    init {
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    override fun readBlock(): Block? {
        var idle = 0
        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
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
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER ->
                    if (inputDone && ++idle > MAX_IDLE_POLLS) throw IOException("decoder stalled before end of stream")
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                index >= 0 -> {
                    val block = if (info.size > 0) toBlock(index) else null
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (block != null) return block
                }
            }
        }
        return null
    }

    private fun toBlock(index: Int): Block {
        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val samples = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> ShortArray(info.size / 2).also { buffer.asShortBuffer().get(it) }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = FloatArray(info.size / 4).also { buffer.asFloatBuffer().get(it) }
                ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
            }
            else -> throw IOException("unsupported PCM encoding $encoding")
        }
        return Block(samples, samples.size, sampleRate, channels)
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 500
    }
}
