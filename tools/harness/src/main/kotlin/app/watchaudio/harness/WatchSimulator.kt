package app.watchaudio.harness

import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.RecordingFormat
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.util.UUID
import kotlinx.io.files.Path

/**
 * Plays the watch's role without a device: cuts a long recording into chunks of
 * [RecordingFormat.chunkDurationMs], encodes them in [format] (by default the watch apps' own,
 * [RecordingFormat.CURRENT]), drops chunks that are completely silent (keeping their time span),
 * and produces the same [ChunkManifest]s a watch would send.
 */
class WatchSimulator(
    private val outputDir: File,
    private val format: RecordingFormat = RecordingFormat.CURRENT,
    private val silenceRms: Float = 0.003f,
) {
    data class Chunk(val manifest: ChunkManifest, val file: Path?)

    fun record(samples: FloatArray, startedAtEpochMs: Long, sessionId: String = UUID.randomUUID().toString()): List<Chunk> {
        outputDir.mkdirs()
        val chunkSamples = (format.chunkDurationMs * Ffmpeg.SAMPLE_RATE / 1000).toInt()
        val chunks = (samples.indices step chunkSamples).map { start ->
            samples.copyOfRange(start, minOf(start + chunkSamples, samples.size))
        }
        return chunks.mapIndexed { index, pcm ->
            val chunkId = UUID.randomUUID().toString()
            val isLast = index == chunks.lastIndex
            val audio = if (rms(pcm) < silenceRms) {
                null
            } else {
                File(outputDir, "$chunkId.${format.codec.fileExtension}").also { Ffmpeg.encode(pcm, it, format) }
            }
            val manifest = ChunkManifest(
                chunkId = chunkId,
                sessionId = sessionId,
                deviceId = "harness-watch",
                source = SourcePlatform.WEAROS,
                startedAtEpochMs = startedAtEpochMs + index * format.chunkDurationMs,
                durationMs = pcm.size * 1000L / Ffmpeg.SAMPLE_RATE,
                timeZone = "Asia/Tokyo",
                audio = audio?.let { format.audioFile(it.name, sizeBytes = it.length(), sha256 = sha256Of(Path(it.path))) },
                endReason = if (isLast) ChunkEndReason.USER_STOP else ChunkEndReason.ROTATION,
            )
            Chunk(manifest, audio?.let { Path(it.path) })
        }
    }

    private fun rms(samples: FloatArray): Float =
        if (samples.isEmpty()) 0f else kotlin.math.sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
}
