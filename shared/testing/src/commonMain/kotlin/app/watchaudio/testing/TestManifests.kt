package app.watchaudio.testing

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform

/** Length of a chunk as the watches rotate them (5 minutes). */
const val TEST_CHUNK_MS = 300_000L

/** An AAC file description for chunk [id] (16 kHz mono, 24 kbps) as a watch sends it. */
fun testAudio(id: String, sizeBytes: Long = 1_000, sha256: String = "00", fileName: String = "$id.m4a") =
    AudioFile(fileName, AudioCodec.AAC, 16_000, 1, 24_000, sizeBytes, sha256)

/**
 * A chunk manifest for tests: a [TEST_CHUNK_MS] rotation chunk from a Wear OS watch with
 * [testAudio], unless overridden. Pass `audio = null` for a silent chunk.
 */
fun testManifest(
    id: String,
    startedAt: Long,
    durationMs: Long = TEST_CHUNK_MS,
    audio: AudioFile? = testAudio(id),
    sessionId: String = "s",
    deviceId: String = "watch",
    source: SourcePlatform = SourcePlatform.WEAROS,
    timeZone: String = "UTC",
    endReason: ChunkEndReason = ChunkEndReason.ROTATION,
    marks: List<Long> = emptyList(),
) = ChunkManifest(
    chunkId = id,
    sessionId = sessionId,
    deviceId = deviceId,
    source = source,
    startedAtEpochMs = startedAt,
    durationMs = durationMs,
    timeZone = timeZone,
    audio = audio,
    markOffsetsMs = marks,
    endReason = endReason,
)
