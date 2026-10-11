package app.watchaudio.harness

import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.RecordingFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thin wrapper around the `ffmpeg` CLI, which stands in for the platform codecs on Linux. */
object Ffmpeg {
    const val SAMPLE_RATE = AsrSettings.SAMPLE_RATE

    /** Decodes any audio file to 16 kHz mono float PCM. */
    fun decode(file: File): FloatArray {
        val bytes = run("-i", file.path, "-f", "f32le", "-ac", "1", "-ar", "$SAMPLE_RATE", "-")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also(buffer::get)
    }

    /**
     * Encodes float PCM at [SAMPLE_RATE] in [format]: AAC-LC in .m4a (ffmpeg's native `aac`
     * encoder) like the watch apps, or Opus in .ogg (`libopus`, for codec comparisons). These are
     * not the watches' platform encoders (Wear OS MediaCodec, watchOS AVAudioRecorder), so sizes
     * and quality approximate theirs.
     */
    fun encode(samples: FloatArray, output: File, format: RecordingFormat) {
        val pcm = File.createTempFile("pcm", ".f32").apply { deleteOnExit() }
        val codec = when (format.codec) {
            AudioCodec.AAC -> listOf("-c:a", "aac", "-movflags", "+faststart")
            AudioCodec.OPUS -> listOf("-c:a", "libopus")
        }
        try {
            ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                asFloatBuffer().put(samples)
                pcm.writeBytes(array())
            }
            run(
                "-f", "f32le", "-ac", "1", "-ar", "$SAMPLE_RATE", "-i", pcm.path,
                "-ac", "${format.channels}", "-ar", "${format.sampleRateHz}", *codec.toTypedArray(),
                "-b:a", "${format.bitrateBps}", "-y", output.path,
            )
        } finally {
            pcm.delete()
        }
    }

    private fun run(vararg args: String): ByteArray {
        val process = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin") + args)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val out = ByteArrayOutputStream()
        val errors = StringBuilder()
        val stderr = Thread { errors.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        process.inputStream.copyTo(out)
        stderr.join()
        check(process.waitFor() == 0) { "ffmpeg ${args.joinToString(" ")} failed: $errors" }
        return out.toByteArray()
    }
}
