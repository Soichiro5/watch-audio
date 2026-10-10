package app.watchaudio.harness

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.protocol.AckStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

/**
 * The core goal without devices: watch-style chunks → ingest → on-device transcription →
 * accumulated, searchable local data. Uses the real Japanese ASR model and the reference
 * recordings shipped with it.
 */
class EndToEndTest {
    private val models = File(System.getProperty("watchaudio.models") ?: "models")
    private val testWavs = File(models, "${SherpaTranscriber.MODEL_NAME}/test_wavs")

    @Test
    fun watchChunksBecomeSearchableJapaneseTranscripts() = runBlocking {
        if (!testWavs.isDirectory) {
            check(System.getProperty("watchaudio.requireModels") != "true") { "models missing in $models" }
            println("SKIPPED: run tools/harness/download-models.sh to enable the end-to-end test")
            return@runBlocking
        }
        val dir = Files.createTempDirectory("e2e").toFile()
        try {
            // Five reference utterances separated by 2 s of silence, plus 20 s of silence at the
            // end, cut into 15 s "watch chunks": some chunks are speech, the last one is silent.
            val gap = FloatArray(2 * Ffmpeg.SAMPLE_RATE)
            val speech = (1..5).map { Ffmpeg.decode(File(testWavs, "$it.wav")) }.reduce { a, b -> a + gap + b }
            val samples = speech + FloatArray(20 * Ffmpeg.SAMPLE_RATE)
            val start = 1_791_622_800_000L
            val chunks = WatchSimulator(File(dir, "outbox"), chunkSeconds = 15).record(samples, start)
            assertTrue(chunks.last().manifest.audio == null, "trailing silence should not be stored as audio")

            LocalStore.open(File(dir, "db.sqlite").path).use { store ->
                val audioRoot = Path(File(dir, "audio").path)
                val ingestor = Ingestor(store, audioRoot)
                chunks.forEach { assertEquals(AckStatus.STORED, ingestor.ingest(it.manifest, it.file).status) }
                // A resend after a lost ACK must not duplicate data.
                assertEquals(AckStatus.DUPLICATE, ingestor.ingest(chunks.first().manifest, null).status)

                SherpaTranscriber(models).use { transcriber ->
                    val result = TranscriptionPipeline(store, audioRoot, transcriber).processPending()
                    assertEquals(0, result.failed)
                }

                val stats = store.read { stats() }
                assertEquals(chunks.size, stats.chunks)
                assertEquals(0, stats.pendingChunks)
                assertTrue(store.read { chunksBetween(0, Long.MAX_VALUE) }.all { it.state in setOf(ChunkState.TRANSCRIBED, ChunkState.NO_AUDIO) })

                val transcript = store.read { segmentsBetween(0, Long.MAX_VALUE) }
                transcript.forEach { println("+%5.1fs %s".format((it.startAt - start) / 1000.0, it.text)) }
                val text = transcript.joinToString("") { it.text }
                listOf("気象庁", "ヤンバルクイナ", "傘").forEach { assertTrue(it in text, "missing '$it' in: $text") }
                // Timestamps are absolute and ordered.
                assertTrue(transcript.zipWithNext().all { (a, b) -> a.startAt <= b.startAt })
                assertTrue(transcript.first().startAt >= start)

                val hits = store.read { search("ヤンバルクイナ") }
                assertEquals(1, hits.size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
