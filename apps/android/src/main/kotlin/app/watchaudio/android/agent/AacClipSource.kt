package app.watchaudio.android.agent

import app.watchaudio.android.asr.AudioDecoder
import app.watchaudio.core.agent.AgentTools
import app.watchaudio.core.agent.AudioClipSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audio for `get_audio_clip` (#184), only for pairings the user allowed audio: decodes the chunk's
 * archived file (AAC in .m4a, or Opus) with the platform codecs into 16 kHz mono, the format of
 * [AgentTools.CLIP_SAMPLE_RATE], and cuts out the asked-for part (at most 30 s, AgentLimits).
 */
class AacClipSource(private val audioRoot: File) : AudioClipSource {
    override suspend fun read(audioPath: String, offsetMs: Long, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val samples = AudioDecoder.decode(File(audioRoot, audioPath))
        clip(samples, offsetMs, durationMs)
    }

    companion object {
        /** The samples of [samples] (16 kHz) from [offsetMs] for [durationMs], clamped to what exists. */
        fun clip(samples: FloatArray, offsetMs: Long, durationMs: Long): FloatArray {
            val perMs = AgentTools.CLIP_SAMPLE_RATE / 1_000
            val from = (offsetMs * perMs).coerceIn(0, samples.size.toLong()).toInt()
            val to = ((offsetMs + durationMs) * perMs).coerceIn(from.toLong(), samples.size.toLong()).toInt()
            return samples.copyOfRange(from, to)
        }
    }
}
