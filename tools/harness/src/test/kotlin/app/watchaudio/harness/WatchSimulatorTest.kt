package app.watchaudio.harness

import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.RecordingFormat
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path

/** The simulated watch in each codec (needs `ffmpeg` with libopus, no ASR models). */
class WatchSimulatorTest {
    private val rate = Ffmpeg.SAMPLE_RATE

    /** 2 s of tone, 2 s of silence, 1 s of tone. */
    private val samples = FloatArray(5 * rate) { i ->
        if (i < 2 * rate || i >= 4 * rate) (0.2 * sin(2 * PI * 440 * i / rate)).toFloat() else 0f
    }

    @Test
    fun encodesChunksInTheRequestedFormat() {
        for (codec in AudioCodec.entries) {
            val format = RecordingFormat.CURRENT.copy(codec = codec, bitrateBps = 16_000, chunkDurationMs = 2_000)
            val dir = Files.createTempDirectory("sim-$codec").toFile()
            try {
                val chunks = WatchSimulator(dir, format).record(samples, startedAtEpochMs = 1_000)
                assertEquals(listOf(1_000L, 3_000, 5_000), chunks.map { it.manifest.startedAtEpochMs }, "$codec")
                assertEquals(listOf(2_000L, 2_000, 1_000), chunks.map { it.manifest.durationMs }, "$codec")
                assertEquals(ChunkEndReason.USER_STOP, chunks.last().manifest.endReason)
                assertNull(chunks[1].manifest.audio, "$codec: the silent chunk keeps only its time span")
                for (chunk in listOf(chunks[0], chunks[2])) {
                    val audio = assertNotNull(chunk.manifest.audio)
                    val file = File(dir, audio.fileName)
                    assertEquals("${chunk.manifest.chunkId}.${codec.fileExtension}", audio.fileName)
                    assertEquals(format.audioFile(audio.fileName, file.length(), sha256Of(Path(file.path))), audio)
                    val decoded = Ffmpeg.decode(file)
                    val expected = chunk.manifest.durationMs * rate / 1000
                    // Encoders pad a few ms of priming/trailing samples.
                    assertTrue(abs(decoded.size - expected) < rate / 20, "$codec: decoded ${decoded.size} samples, expected ~$expected")
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
