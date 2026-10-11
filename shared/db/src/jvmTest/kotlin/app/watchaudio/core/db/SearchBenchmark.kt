package app.watchaudio.core.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.watchaudio.testing.TempDir
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Search latency on a year of transcripts (#23, #72): deterministic Japanese conversation cut
 * into utterances the way the pipeline stores them since #68 (about 3.2 million segments a
 * year), queried through [StoreTransaction.search] (trigram FTS for 3+ characters, LIKE for
 * shorter queries, snippets included).
 *
 * Not part of `jvmTest`; run it with `./gradlew :shared:db:jvmBenchmark`. Prints a
 * `SEARCH_BENCHMARK key=value ...` line and writes a Markdown summary to the path in the
 * `benchmark.report` system property. Fails if the p95 latency of any query exceeds the
 * 300 ms target. `-Pbenchmark.segments=N` changes the data size and `-Pbenchmark.profile=sentences`
 * switches to the #23 segment shape ([TranscriptGenerator.Profile]).
 */
class SearchBenchmark {
    private val dir = TempDir("search-benchmark")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun searchAYearOfTranscripts() = runBlocking {
        val targetSegments = System.getProperty("benchmark.segments")?.toInt() ?: YEAR_OF_UTTERANCES
        val rounds = System.getProperty("benchmark.rounds")?.toInt() ?: 40
        val profile = System.getProperty("benchmark.profile")
            ?.let { name -> TranscriptGenerator.Profile.valueOf(name.uppercase(Locale.ROOT)) }
            ?: TranscriptGenerator.Profile.UTTERANCES

        val loadStarted = System.nanoTime()
        val loaded = TranscriptGenerator(seed = 20_261_010, profile).populate(store, targetSegments)
        val loadSeconds = (System.nanoTime() - loadStarted) / 1e9
        val dbBytes = File(dir.resolve(LocalStore.FILE_NAME).toString()).length() +
            File(dir.resolve(LocalStore.FILE_NAME + "-wal").toString()).length()
        val sizes = sizeByTable()

        // Warm up (page cache, statement compilation), then interleave queries over rounds.
        QUERIES.forEach { query -> repeat(2) { store.read { search(query.text) } } }
        val samples = QUERIES.associateWith { mutableListOf<Double>() }
        val hits = mutableMapOf<BenchmarkQuery, Int>()
        repeat(rounds) {
            for (query in QUERIES) {
                val started = System.nanoTime()
                val result = store.read { search(query.text) }
                samples.getValue(query) += (System.nanoTime() - started) / 1e6
                hits[query] = result.size
            }
        }

        val fts = samples.filterKeys { it.text.length >= 3 }.values.flatten()
        val like = samples.filterKeys { it.text.length < 3 }.values.flatten()
        val all = fts + like
        val summary = linkedMapOf(
            "profile" to profile.name.lowercase(Locale.ROOT),
            "segments" to loaded.segments.toString(),
            "chunks" to loaded.chunks.toString(),
            "days" to loaded.days.toString(),
            "chars_per_segment" to "%.1f".format(Locale.ROOT, loaded.textChars.toDouble() / loaded.segments),
            "median_segment_s" to "%.2f".format(Locale.ROOT, loaded.medianSegmentMs / 1e3),
            "db_mb" to "%.0f".format(Locale.ROOT, dbBytes / 1e6),
            "bytes_per_segment" to (dbBytes / loaded.segments).toString(),
            "load_s" to "%.1f".format(Locale.ROOT, loadSeconds),
            "queries" to all.size.toString(),
            "fts_p50_ms" to fts.percentile(50).ms(),
            "fts_p95_ms" to fts.percentile(95).ms(),
            "like_p50_ms" to like.percentile(50).ms(),
            "like_p95_ms" to like.percentile(95).ms(),
            "all_p50_ms" to all.percentile(50).ms(),
            "all_p95_ms" to all.percentile(95).ms(),
            "max_ms" to all.max().ms(),
            "target_ms" to TARGET_MS.toString(),
            "cpus" to Runtime.getRuntime().availableProcessors().toString(),
            "jvm" to System.getProperty("java.version"),
        )
        val line = "SEARCH_BENCHMARK " + summary.entries.joinToString(" ") { "${it.key}=${it.value}" }
        println(line)

        val report = buildString {
            appendLine("### Search benchmark (#23, #72)")
            appendLine()
            appendLine(
                "${loaded.segments} segments (`${summary["profile"]}`: ${summary["chars_per_segment"]} characters, " +
                    "median ${summary["median_segment_s"]} s) in ${loaded.chunks} chunks over ${loaded.days} days " +
                    "(${summary["db_mb"]} MB = ${summary["bytes_per_segment"]} bytes per segment, loaded in ${summary["load_s"]} s), " +
                    "$rounds rounds per query, target p95 <= $TARGET_MS ms. " +
                    "JVM ${summary["jvm"]} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${summary["cpus"]} CPUs.",
            )
            appendLine()
            appendLine("| Path | p50 (ms) | p95 (ms) |")
            appendLine("| --- | ---: | ---: |")
            appendLine("| FTS trigram (3+ chars) | ${summary["fts_p50_ms"]} | ${summary["fts_p95_ms"]} |")
            appendLine("| LIKE fallback (1-2 chars) | ${summary["like_p50_ms"]} | ${summary["like_p95_ms"]} |")
            appendLine("| All | ${summary["all_p50_ms"]} | ${summary["all_p95_ms"]} |")
            appendLine()
            appendLine("| Query | Path | Hits (limit 50) | p50 (ms) | p95 (ms) | max (ms) |")
            appendLine("| --- | --- | ---: | ---: | ---: | ---: |")
            samples.forEach { (query, times) ->
                appendLine(
                    "| ${query.text} | ${if (query.text.length >= 3) "FTS" else "LIKE"} (${query.note}) | ${hits[query]} | " +
                        "${times.percentile(50).ms()} | ${times.percentile(95).ms()} | ${times.max().ms()} |",
                )
            }
            if (sizes.isNotEmpty()) {
                appendLine()
                appendLine("| Table / index | MB |")
                appendLine("| --- | ---: |")
                sizes.forEach { (name, bytes) -> appendLine("| $name | ${"%.1f".format(Locale.ROOT, bytes / 1e6)} |") }
            }
            appendLine()
            appendLine("`$line`")
        }
        println(report)
        System.getProperty("benchmark.report")?.let { path -> File(path).apply { parentFile.mkdirs() }.writeText(report) }

        assertTrue(loaded.segments >= targetSegments)
        val slow = samples.filterValues { it.percentile(95) > TARGET_MS }.keys.map { it.text }
        assertTrue(slow.isEmpty(), "p95 above $TARGET_MS ms for $slow")
    }

    /**
     * Bytes per table and index (FTS shadow tables grouped under their FTS table), largest first;
     * empty if the bundled SQLite lacks the dbstat table.
     */
    private fun sizeByTable(): Map<String, Long> = runCatching {
        BundledSQLiteDriver().open(dir.resolve(LocalStore.FILE_NAME).toString()).use { connection ->
            connection.prepare("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name").use { st ->
                val sizes = mutableMapOf<String, Long>()
                while (st.step()) {
                    val name = st.getText(0).replace(FTS_SHADOW, "")
                    sizes[name] = (sizes[name] ?: 0L) + st.getLong(1)
                }
                sizes.entries.sortedByDescending { it.value }.associate { it.key to it.value }
            }
        }
    }.getOrElse { emptyMap() }

    private data class BenchmarkQuery(val text: String, val note: String)

    private companion object {
        const val TARGET_MS = 300

        val FTS_SHADOW = Regex("_(data|idx|docsize|config|content)$")

        /**
         * A year of utterances after #68 (#72): 16 worn hours with ~5 h of speech a day and
         * ~1,720 segments per hour of speech (the test audio set's conversations, docs/dev/test-audio.md)
         * give ~8,600 a day, ~3.14 million a year; rounded up.
         */
        const val YEAR_OF_UTTERANCES = 3_200_000

        val QUERIES = listOf(
            BenchmarkQuery("ミーティング", "frequent"),
            BenchmarkQuery("打ち合わせ", "frequent"),
            BenchmarkQuery("見積もり", "frequent"),
            BenchmarkQuery("田中さん", "frequent"),
            BenchmarkQuery("会議の資料", "phrase"),
            BenchmarkQuery("保育園のお迎え", "phrase"),
            BenchmarkQuery("案件番号4821", "rare"),
            BenchmarkQuery("存在しない言葉", "no hits"),
            BenchmarkQuery("会議", "frequent"),
            BenchmarkQuery("予約", "frequent"),
            BenchmarkQuery("電車", "frequent"),
            BenchmarkQuery("雨", "1 char"),
            BenchmarkQuery("鰻丼", "rare"),
            BenchmarkQuery("猫舌", "no hits"),
        )

        /** Nearest-rank percentile. */
        fun List<Double>.percentile(p: Int): Double {
            val sorted = sorted()
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt() - 1
            return sorted[rank.coerceIn(0, sorted.lastIndex)]
        }

        fun Double.ms() = "%.1f".format(Locale.ROOT, this)
    }
}
