package app.watchaudio.harness

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.AsrModelSpec
import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
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
    private val testWavs = File(models, "${AsrModelSpec.JA_REAZONSPEECH.id}/test_wavs")

    private fun modelsAvailable(): Boolean {
        if (testWavs.isDirectory) return true
        check(System.getProperty("watchaudio.requireModels") != "true") { "models missing in $models" }
        println("SKIPPED: run tools/harness/download-models.sh to enable the end-to-end test")
        return false
    }

    @Test
    fun watchChunksBecomeSearchableJapaneseTranscripts() = runBlocking {
        if (!modelsAvailable()) return@runBlocking
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

    /**
     * Utterances cut by the watch's chunk rotation are transcribed as if there were no cut,
     * whether the chunks are transcribed in recording order or the later chunk arrives first.
     */
    @Test
    fun utterancesAcrossChunkBoundariesStayWhole() = runBlocking {
        if (!modelsAvailable()) return@runBlocking
        // With 15 s chunks the 15 s boundary falls 3.0 s into 5.wav (mid-word in "持ち主とはぐれた")
        // and the 30 s boundary 1.8 s into 3.wav (in "ヤンバルクイナ").
        fun silence(ms: Int) = FloatArray(ms * Ffmpeg.SAMPLE_RATE / 1000)
        fun wav(n: Int) = Ffmpeg.decode(File(testWavs, "$n.wav"))
        val five = wav(5)
        val gapMs = 30_000 - 1_800 - (12_000 + five.size * 1000 / Ffmpeg.SAMPLE_RATE)
        val samples = silence(12_000) + five + silence(gapMs) + wav(3) + silence(2_000)

        val reference = transcribeAsChunks(samples, chunkSeconds = 60, inOrder = true)
        assertTrue(reference.any { "持ち主" in it } && reference.any { "ヤンバルクイナ" in it }, "$reference")
        for (inOrder in listOf(true, false)) {
            val joined = transcribeAsChunks(samples, chunkSeconds = 15, inOrder = inOrder)
            assertEquals(reference, joined, "15 s chunks, inOrder=$inOrder")
        }
    }

    /**
     * Records [samples] as watch chunks, ingests and transcribes them (in recording order, or
     * each chunk before the one recorded before it arrives) and returns the stored texts.
     */
    private suspend fun transcribeAsChunks(samples: FloatArray, chunkSeconds: Int, inOrder: Boolean): List<String> {
        val dir = Files.createTempDirectory("boundary").toFile()
        try {
            val start = 1_791_622_800_000L
            val chunks = WatchSimulator(File(dir, "outbox"), chunkSeconds = chunkSeconds).record(samples, start)
            return LocalStore.open(File(dir, "db.sqlite").path).use { store ->
                val audioRoot = Path(File(dir, "audio").path)
                val ingestor = Ingestor(store, audioRoot)
                SherpaTranscriber(models).use { sherpa ->
                    val transcriber = AudioCounter(sherpa)
                    val pipeline = TranscriptionPipeline(store, audioRoot, transcriber)
                    for (chunk in if (inOrder) chunks else chunks.reversed()) {
                        assertEquals(AckStatus.STORED, ingestor.ingest(chunk.manifest, chunk.file).status)
                        if (!inOrder) assertEquals(0, pipeline.processPending().failed)
                    }
                    assertEquals(0, pipeline.processPending().failed)
                    val recordedMs = chunks.sumOf { if (it.file == null) 0L else it.manifest.durationMs }
                    println("${chunkSeconds}s chunks, inOrder=$inOrder: transcribed ${transcriber.audioMs} ms of audio for $recordedMs ms recorded")
                }
                val transcript = store.read { segmentsBetween(0, Long.MAX_VALUE) }
                transcript.forEach { println("  +%5.1f-%5.1fs %s".format((it.startAt - start) / 1000.0, (it.endAt - start) / 1000.0, it.text)) }
                transcript.map { it.text }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Sums the audio handed to [delegate], to see what boundary joins cost. */
    private class AudioCounter(private val delegate: Transcriber) : Transcriber by delegate {
        var audioMs = 0L

        override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
            audioMs += parts.sumOf { it.durationMs }
            return delegate.transcribe(parts)
        }
    }
}
