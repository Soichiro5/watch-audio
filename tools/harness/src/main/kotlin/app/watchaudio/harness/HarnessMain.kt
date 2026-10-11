package app.watchaudio.harness

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.SegmentFilter
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
 *   ./gradlew :tools:harness:run --args="evaluate <manifest.json> <workDir> [--vad name=value,...] [--filter off|name=value,...] [recordingIds...]"
 *   ./gradlew :tools:harness:run --args="filter-study <manifest.json> <workDir> [recordingIds...]"
 *
 * `evaluate` scores a test audio set from tools/test-audio (see docs/dev/test-audio.md); `--vad`
 * overrides [app.watchaudio.core.pipeline.VadSettings] and `--filter` the app's
 * [FalsePositiveFilter] by property name, or turns the filter off (see [EvaluateArgs]).
 * `filter-study` re-scores an `evaluate --filter off` run with candidate filters ([FilterStudy]).
 */
fun main(args: Array<String>) = runBlocking {
    if (args.size < 2) usage()
    val models = File(System.getProperty("watchaudio.models") ?: "tools/harness/models")
    if (args[0] == "filter-study") {
        if (args.size < 3) usage()
        FilterStudy.run(File(args[1]), File(args[2]), args.drop(3).toSet())
        return@runBlocking
    }
    if (args[0] == "evaluate") {
        val parsed = try {
            EvaluateArgs.parse(args.drop(1))
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            usage()
        }
        evaluate(models, parsed)
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
                    val result = TranscriptionPipeline(store, Path(File(dataDir, "audio").path), transcriber, segmentFilter = FalsePositiveFilter.DEFAULT)
                        .processPending()
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

private suspend fun evaluate(models: File, args: EvaluateArgs) {
    val workDir = args.workDir.absoluteFile
    println("VAD: ${args.vad}")
    println("Filter: ${args.filter ?: "off (everything is stored)"}")
    TestSetEvaluator(models, workDir, vadSettings = args.vad, segmentFilter = args.filter ?: SegmentFilter.KEEP_ALL)
        .evaluate(args.manifest, args.recordings)
    val table = TestSetEvaluator.markdownTable(args.manifest, workDir)
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
    System.err.println("usage: record <dataDir> <audio...> | search <dataDir> <query> | show <dataDir> | evaluate <manifest.json> <workDir> [--vad name=value,...] [--filter off|name=value,...] [ids...] | filter-study <manifest.json> <workDir> [ids...]")
    exitProcess(2)
}
