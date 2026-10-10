package app.watchaudio.harness

import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.util.UUID
import kotlinx.io.files.Path

/**
 * Plays the watch's role without a device: cuts a long recording into fixed-length chunks,
 * encodes them as AAC like the watch apps, drops chunks that are completely silent (keeping
 * their time span), and produces the same [ChunkManifest]s a watch would send.
 */
class WatchSimulator(
    private val outputDir: File,
    private val chunkSeconds: Int = 300,
    private val bitrateBps: Int = 24_000,
    private val silenceRms: Float = 0.003f,
) {
    data class Chunk(val manifest: ChunkManifest, val file: Path?)

    fun record(samples: FloatArray, startedAtEpochMs: Long, sessionId: String = UUID.randomUUID().toString()): List<Chunk> {
        outputDir.mkdirs()
        val chunkSamples = chunkSeconds * Ffmpeg.SAMPLE_RATE
        val chunks = (samples.indices step chunkSamples).map { start ->
            samples.copyOfRange(start, minOf(start + chunkSamples, samples.size))
        }
        return chunks.mapIndexed { index, pcm ->
            val chunkId = UUID.randomUUID().toString()
            val isLast = index == chunks.lastIndex
            val audio = if (rms(pcm) < silenceRms) {
                null
            } else {
                File(outputDir, "$chunkId.m4a").also { Ffmpeg.encodeAac(pcm, it, bitrateBps) }
            }
            val manifest = ChunkManifest(
                chunkId = chunkId,
                sessionId = sessionId,
                deviceId = "harness-watch",
                source = SourcePlatform.WEAROS,
                startedAtEpochMs = startedAtEpochMs + index.toLong() * chunkSeconds * 1000,
                durationMs = pcm.size * 1000L / Ffmpeg.SAMPLE_RATE,
                timeZone = "Asia/Tokyo",
                audio = audio?.let {
                    AudioFile(
                        fileName = it.name,
                        codec = AudioCodec.AAC,
                        sampleRateHz = Ffmpeg.SAMPLE_RATE,
                        channels = 1,
                        bitrateBps = bitrateBps,
                        sizeBytes = it.length(),
                        sha256 = sha256Of(Path(it.path)),
                    )
                },
                endReason = if (isLast) ChunkEndReason.USER_STOP else ChunkEndReason.ROTATION,
            )
            Chunk(manifest, audio?.let { Path(it.path) })
        }
    }

    private fun rms(samples: FloatArray): Float =
        if (samples.isEmpty()) 0f else kotlin.math.sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
}
