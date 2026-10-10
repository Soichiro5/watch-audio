package app.watchaudio.core.db

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Search latency on a year of transcripts (#23): ~500,000 segments of deterministic Japanese
 * conversation, queried through [StoreTransaction.search] (trigram FTS for 3+ characters, LIKE
 * for shorter queries, snippets included).
 *
 * Not part of `jvmTest`; run it with `./gradlew :shared:core-db:jvmBenchmark`. Prints a
 * `SEARCH_BENCHMARK key=value ...` line and writes a Markdown summary to the path in the
 * `benchmark.report` system property. Fails if the p95 latency of any query exceeds the
 * 300 ms target. `-Pbenchmark.segments=N` changes the data size.
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
        val targetSegments = System.getProperty("benchmark.segments")?.toInt() ?: 500_000
        val rounds = System.getProperty("benchmark.rounds")?.toInt() ?: 40

        val loadStarted = System.nanoTime()
        val loaded = TranscriptGenerator(seed = 20_261_010).populate(store, targetSegments)
        val loadSeconds = (System.nanoTime() - loadStarted) / 1e9
        val dbBytes = File(dir.resolve(LocalStore.FILE_NAME).toString()).length() +
            File(dir.resolve(LocalStore.FILE_NAME + "-wal").toString()).length()

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
            "segments" to loaded.segments.toString(),
            "chunks" to loaded.chunks.toString(),
            "db_mb" to "%.0f".format(Locale.ROOT, dbBytes / 1e6),
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
            appendLine("### Search benchmark (#23)")
            appendLine()
            appendLine(
                "${loaded.segments} segments in ${loaded.chunks} chunks (${summary["db_mb"]} MB, loaded in ${summary["load_s"]} s), " +
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
            appendLine()
            appendLine("`$line`")
        }
        println(report)
        System.getProperty("benchmark.report")?.let { path -> File(path).apply { parentFile.mkdirs() }.writeText(report) }

        assertTrue(loaded.segments >= targetSegments)
        val slow = samples.filterValues { it.percentile(95) > TARGET_MS }.keys.map { it.text }
        assertTrue(slow.isEmpty(), "p95 above $TARGET_MS ms for $slow")
    }

    private data class BenchmarkQuery(val text: String, val note: String)

    private companion object {
        const val TARGET_MS = 300

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

/**
 * Deterministic everyday Japanese speech: 5-minute chunks spread over a year of waking hours,
 * ~10 utterances each, built from work, family and errand vocabulary with fillers.
 */
internal class TranscriptGenerator(seed: Int) {
    private val random = Random(seed)

    data class Loaded(val chunks: Int, val segments: Int)

    suspend fun populate(store: LocalStore, targetSegments: Int): Loaded {
        val chunkMs = 300_000L
        val start = 1_760_054_400_000L // 2025-10-10T00:00:00Z (09:00 JST)
        var chunks = 0
        var segments = 0
        var at = start
        while (segments < targetSegments) {
            store.transaction {
                repeat(CHUNKS_PER_TRANSACTION) {
                    if (segments >= targetSegments) return@repeat
                    val id = "c$chunks"
                    val manifest = ChunkManifest(
                        chunkId = id,
                        sessionId = "s${chunks / 100}",
                        deviceId = "watch",
                        source = SourcePlatform.WEAROS,
                        startedAtEpochMs = at,
                        durationMs = chunkMs,
                        timeZone = "Asia/Tokyo",
                        audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 900_000, "00"),
                        endReason = ChunkEndReason.ROTATION,
                    )
                    insertChunk(manifest, "$id.m4a", at + chunkMs)
                    val count = 6 + random.nextInt(9) // 6..14, ~10 on average
                    val slot = chunkMs / count
                    saveTranscript(
                        id,
                        List(count) { i ->
                            val begin = at + i * slot + random.nextLong(slot / 4)
                            NewSegment(begin, begin + slot / 2 + random.nextLong(slot / 4), sentence(), "ja")
                        },
                        "benchmark",
                    )
                    segments += count
                    chunks++
                    // ~137 chunks (11.4 h of speech) per day, then skip the night.
                    at += if (chunks % 137 == 0) chunkMs + 12 * 3_600_000L else chunkMs
                }
            }
        }
        return Loaded(chunks, segments)
    }

    private fun sentence(): String = buildString {
        if (random.nextInt(4) == 0) append(FILLERS.pick()).append('、')
        repeat(1 + random.nextInt(3)) { clause ->
            if (clause > 0) append('、')
            when (random.nextInt(10)) {
                0 -> append(TIMES.pick()).append(PEOPLE.pick()).append("と").append(topic()).append(PREDICATES.pick())
                1 -> append("案件番号").append(1_000 + random.nextInt(9_000)).append("の").append(topic()).append(PREDICATES.pick())
                2, 3 -> append(PEOPLE.pick()).append("が").append(topic()).append(PREDICATES.pick())
                else -> append(TIMES.pick()).append("の").append(topic()).append(PREDICATES.pick())
            }
        }
        append(ENDINGS.pick())
    }

    /** Mostly common topics; "鰻丼" is rare (about 1 in 5,000 clauses). */
    private fun topic(): String = if (random.nextInt(5_000) == 0) "鰻丼" else TOPICS.pick()

    private fun <T> List<T>.pick(): T = this[random.nextInt(size)]

    private companion object {
        const val CHUNKS_PER_TRANSACTION = 500

        val FILLERS = listOf("えーと", "あの", "そうですね", "なるほど", "うん", "まあ", "ちなみに", "そういえば")
        val TIMES = listOf(
            "今日", "明日", "昨日", "来週", "先週", "午後", "朝", "夕方", "今週末", "月曜", "金曜", "来月", "さっき", "後で",
        )
        val PEOPLE = listOf(
            "田中さん", "佐藤さん", "鈴木さん", "高橋さん", "部長", "課長", "お客さん", "妻", "子供", "母", "先生", "チーム",
        )
        val TOPICS = listOf(
            "会議", "会議の資料", "打ち合わせ", "ミーティング", "定例ミーティング", "見積もり", "企画書", "予算", "契約",
            "請求書", "締め切り", "プレゼン", "出張", "新幹線", "ホテルの予約", "歯医者の予約", "美容院の予約", "病院",
            "薬", "保育園のお迎え", "宿題", "夕飯", "買い物", "洗濯", "掃除", "ゴミ出し", "電車", "電車の遅延", "天気",
            "雨", "傘", "銀行の振り込み", "誕生日のプレゼント", "旅行の計画", "映画", "ラーメン", "カレー", "散歩", "ジム",
            "引っ越し", "車の点検", "保険", "税金", "年末調整", "健康診断", "ランチ", "コーヒー", "新しいプロジェクト",
            "リリース", "バグの修正", "レビュー", "面接", "研修", "飲み会", "週報",
        )
        val PREDICATES = listOf(
            "について話しました", "を確認しておいてください", "の件ですが", "はどうなりましたか", "を忘れないでね",
            "が遅れています", "に間に合いそうです", "を来週に変更しました", "は十時からです", "をお願いできますか",
            "はもう終わりました", "を準備しないといけない", "の連絡が来ていました", "で少し相談があります",
            "はキャンセルになりました", "を送っておきます",
        )
        val ENDINGS = listOf("", "", "。", "。", "ね", "よ", "かな")
    }
}
