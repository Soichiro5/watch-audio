package app.watchaudio.harness

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

/**
 * Device-free end-to-end run: audio files → simulated watch chunks → ingest → on-device
 * transcription → local SQLite. Everything stays on this machine.
 *
 * Usage:
 *   ./gradlew :tools:harness:run --args="record <dataDir> <audio files...>"
 *   ./gradlew :tools:harness:run --args="search <dataDir> <query>"
 *   ./gradlew :tools:harness:run --args="show <dataDir>"
 *   ./gradlew :tools:harness:run --args="evaluate <manifest.json> <workDir> [recordingIds...]"
 *
 * `evaluate` scores a test audio set from tools/test-audio (see docs/dev/test-audio.md).
 */
fun main(args: Array<String>) = runBlocking {
    if (args.size < 2) usage()
    val models = File(System.getProperty("watchaudio.models") ?: "tools/harness/models")
    if (args[0] == "evaluate") {
        if (args.size < 3) usage()
        evaluate(models, File(args[1]), File(args[2]).absoluteFile, args.drop(3).toSet())
        return@runBlocking
    }
    val dataDir = File(args[1]).absoluteFile.apply { mkdirs() }
    LocalStore.open(File(dataDir, LocalStore.FILE_NAME).path).use { store ->
        when (args[0]) {
            "record" -> {
                val inputs = args.drop(2).map(::File).ifEmpty { usage() }
                val samples = inputs.map(Ffmpeg::decode).reduce { a, b -> a + FloatArray(Ffmpeg.SAMPLE_RATE) + b }
                val chunks = WatchSimulator(File(dataDir, "outbox")).record(samples, System.currentTimeMillis() - samples.size / 16L)
                val ingestor = Ingestor(store, Path(File(dataDir, "audio").path))
                chunks.forEach { println("chunk ${it.manifest.chunkId}: ${ingestor.ingest(it.manifest, it.file).status}") }
                SherpaTranscriber(models).use { transcriber ->
                    val started = System.nanoTime()
                    val result = TranscriptionPipeline(store, Path(File(dataDir, "audio").path), transcriber).processPending()
                    val seconds = (System.nanoTime() - started) / 1e9
                    println("transcribed=${result.transcribed} failed=${result.failed} in %.1fs (audio %.1fs)".format(seconds, samples.size / 16_000.0))
                }
                printDay(store)
            }
            "search" -> store.read { search(args.drop(2).joinToString(" ")) }.forEach { println("${time(it.segment.startAt)}  ${it.snippet}") }
            "show" -> printDay(store)
            else -> usage()
        }
    }
}

private suspend fun evaluate(models: File, manifest: File, workDir: File, only: Set<String>) {
    TestSetEvaluator(models, workDir).evaluate(manifest, only)
    val table = TestSetEvaluator.markdownTable(manifest, workDir)
    File(workDir, "results.md").writeText(table)
    println()
    print(table)
    println("\nper-conversation transcripts: $workDir/<recording>/report.md")
}

private suspend fun printDay(store: LocalStore) {
    val stats = store.read { stats() }
    println("chunks=${stats.chunks} pending=${stats.pendingChunks} segments=${stats.segments} audio=${stats.audioBytes / 1024}KB recorded=${stats.recordedMs / 1000}s")
    store.read { segmentsBetween(0, Long.MAX_VALUE) }.forEach { println("${time(it.startAt)}  ${it.text}") }
}

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun time(epochMs: Long) = formatter.format(Instant.ofEpochMilli(epochMs))

private fun usage(): Nothing {
    System.err.println("usage: record <dataDir> <audio...> | search <dataDir> <query> | show <dataDir> | evaluate <manifest.json> <workDir> [ids...]")
    exitProcess(2)
}
