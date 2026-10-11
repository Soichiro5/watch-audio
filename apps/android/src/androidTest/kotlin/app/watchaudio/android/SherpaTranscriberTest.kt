package app.watchaudio.android

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.watchaudio.android.asr.AndroidSherpaTranscriber
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #22: the Android transcriber (MediaCodec decoding of an AAC watch chunk + the sherpa-onnx AAR
 * with Silero VAD and the ReazonSpeech Zipformer) turns known Japanese speech into searchable
 * segments in the database. Uses its own database so the app's data is not touched.
 */
@RunWith(AndroidJUnit4::class)
class SherpaTranscriberTest {
    @Test
    fun transcribesAJapaneseChunkIntoTheDatabase() = runBlocking {
        val models = SpeechFixtures.ensureModels()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "asr-test-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            LocalStore.open(File(dir, "db.sqlite").path).use { store ->
                val audioRoot = Path(File(dir, "audio").path)
                val samples = SpeechFixtures.speech(1, 2, 3, 4, 5)
                val startedAt = System.currentTimeMillis() - 3_600_000
                val chunk = SpeechFixtures.ingest(Ingestor(store, audioRoot), "asr-" + UUID.randomUUID(), "asr-test", startedAt, samples)

                val transcriber = AndroidSherpaTranscriber(models)
                val started = SystemClock.uptimeMillis()
                val result = try {
                    TranscriptionPipeline(store, audioRoot, transcriber).processPending()
                } finally {
                    transcriber.release()
                }
                val elapsedMs = SystemClock.uptimeMillis() - started

                val record = store.read { chunk(chunk.chunkId) }!!
                val segments = store.read { segmentsBetween(startedAt, startedAt + chunk.durationMs + 60_000) }
                segments.forEach { Evidence.log("ASR_SEGMENT +%.1fs %s".format((it.startAt - startedAt) / 1000.0, it.text)) }
                val text = segments.joinToString("") { it.text }
                val hits = store.read { search("ヤンバルクイナ") }
                Evidence.log(
                    "ASR_CHECK engine=${transcriber.engineId} audio_ms=${chunk.durationMs} elapsed_ms=$elapsedMs " +
                        "rtf=%.2f state=${record.state} segments=${segments.size} search_hits=${hits.size}".format(elapsedMs.toDouble() / chunk.durationMs),
                )

                assertEquals(TranscriptionPipeline.RunResult(transcribed = 1, failed = 0), result)
                assertEquals(ChunkState.TRANSCRIBED, record.state)
                SpeechFixtures.KEYWORDS.values.forEach { assertTrue(it in text, "missing '$it' in: $text") }
                assertTrue(segments.zipWithNext().all { (a, b) -> a.startAt <= b.startAt })
                assertEquals(1, hits.size)
                assertEquals(chunk.chunkId, hits.single().segment.chunkId)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
