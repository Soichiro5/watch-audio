package app.watchaudio.harness

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewPassage
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.db.PassageVector
import app.watchaudio.core.embedding.EmbeddingModelSpec
import app.watchaudio.core.embedding.Embedder
import app.watchaudio.core.embedding.Int8Vectors
import app.watchaudio.core.embedding.OnnxEmbedder
import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.search.SemanticIndex
import app.watchaudio.core.search.VectorCache
import java.io.File
import kotlin.random.Random

/**
 * Brute-force search time of semantic search (#24) on dummy data, through the app's code:
 * [VectorCache.search] (what [SemanticIndex.search] runs after its sync) over N random 256-dim
 * int8 vectors, with 1, 2 and 4 threads; [SemanticIndex] end to end (sync from SQLite, then
 * search) on a smaller database; and the real model's query embedding when it is downloaded.
 * Prints Markdown tables; the files under [workDir] are deleted afterwards.
 */
class SemanticBench(private val workDir: File, private val models: File) {
    private val dim = RuriV3.SPEC.dimension
    private val random = Random(24)

    suspend fun run(sizes: List<Int>, queries: Int = 30) {
        workDir.mkdirs()
        try {
            println("| N | threads | p50 ms | p95 ms | max ms | files MB |")
            println("|---|---|---|---|---|---|")
            for (size in sizes) scan(size, queries)
            println()
            endToEnd(100_000)
            queryEmbedding()
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun unit(): FloatArray = OnnxEmbedder.normalize(FloatArray(dim) { random.nextFloat() * 2 - 1 })

    private suspend fun scan(size: Int, queries: Int) {
        val dir = File(workDir, "scan-$size").path
        VectorCache.open(dir, "bench", "bench", dim, "token").use { cache ->
            var id = 0L
            while (id < size) {
                val batch = (0 until minOf(8192, size - id.toInt())).map {
                    id++
                    val (values, scale) = Int8Vectors.quantize(unit())
                    PassageVector(id, scale, values)
                }
                cache.append(batch)
            }
            cache.commit(0)
            val megabytes = File(dir).listFiles()!!.sumOf { it.length() } / 1e6
            for (threads in listOf(1, 2, 4)) {
                val times = (0 until queries + 3).map {
                    val (q, qs) = Int8Vectors.quantize(unit())
                    val started = System.nanoTime()
                    val hits = cache.search(q, qs, 30, threads)
                    check(hits.size == 30)
                    (System.nanoTime() - started) / 1e6
                }.drop(3).sorted()
                println("| $size | $threads | %.1f | %.1f | %.1f | %.0f |".format(times[times.size / 2], times[(times.size * 95 / 100).coerceAtMost(times.size - 1)], times.last(), megabytes))
            }
        }
        File(dir).deleteRecursively()
    }

    /** SQLite → cache sync (first search after install or a rebuild) and searches through [SemanticIndex]. */
    private suspend fun endToEnd(passages: Int) {
        val dir = File(workDir, "e2e").apply { mkdirs() }
        val embedder = RandomEmbedder()
        LocalStore.open(File(dir, LocalStore.FILE_NAME).path).use { store ->
            val perChunk = 10
            val started = System.nanoTime()
            for (c in 0 until passages / perChunk) {
                val id = "bench-$c"
                val at = 1_780_000_000_000L + c * 300_000L
                store.transaction {
                    insertChunk(manifest(id, at), "$id.m4a", at)
                    saveTranscript(id, (0 until perChunk).map { NewSegment(at + it * 3_000L, at + it * 3_000L + 2_000, "発話 $c $it", "ja") }, "bench")
                    val ids = chunkSegments(id).map { it.id }
                    savePassages(id, embedder.spec.id, ids, (0 until perChunk).map {
                        val (values, scale) = Int8Vectors.quantize(unit())
                        NewPassage(it, 1, at + it * 3_000L, scale, values)
                    })
                }
            }
            println("database with $passages passages written in %.1f s (%.0f MB)".format((System.nanoTime() - started) / 1e9, File(dir, LocalStore.FILE_NAME).length() / 1e6))
            val index = SemanticIndex(store, embedder, File(dir, "index").path)
            val syncStarted = System.nanoTime()
            index.sync(prune = true)
            println("first sync (SQLite → cache, $passages vectors): %.0f ms".format((System.nanoTime() - syncStarted) / 1e6))
            val times = (0 until 23).map {
                val queryStarted = System.nanoTime()
                index.search("query $it", 30)
                (System.nanoTime() - queryStarted) / 1e6
            }.drop(3).sorted()
            println("SemanticIndex.search ($passages, sync + scan, 4 threads, random query vector): p50 %.1f ms, max %.1f ms".format(times[times.size / 2], times.last()))
        }
        dir.deleteRecursively()
    }

    private suspend fun queryEmbedding() {
        if (!HarnessEmbedder.available(models)) {
            println("query embedding: model not downloaded")
            return
        }
        val embedder = HarnessEmbedder.create(models)
        try {
            val loadStarted = System.nanoTime()
            embedder.embedQuery("先週話した旅行の件")
            println("model load + first query: %.0f ms".format((System.nanoTime() - loadStarted) / 1e6))
            val times = listOf("歯医者の予約いつだっけ", "会議の結論どうなった", "子供のお迎え誰が行くんだっけ", "エアコン修理の人いつ来る", "健康診断の結果").map {
                val started = System.nanoTime()
                embedder.embedQuery(it)
                (System.nanoTime() - started) / 1e6
            }
            println("query embedding (1 thread): %s ms".format(times.joinToString(" / ") { "%.1f".format(it) }))
        } finally {
            embedder.release()
        }
    }

    /** Random unit vectors; the query's text does not matter for timing. */
    private inner class RandomEmbedder : Embedder {
        override val spec = EmbeddingModelSpec("bench", dim, "", "", 512)

        override suspend fun countTokens(text: String) = text.length

        override suspend fun embedDocuments(texts: List<String>) = texts.map { unit() }

        override suspend fun embedQuery(text: String) = unit()
    }

    private fun manifest(id: String, startedAt: Long) = ChunkManifest(
        chunkId = id,
        sessionId = "bench",
        deviceId = "bench",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = startedAt,
        durationMs = 300_000L,
        timeZone = "Asia/Tokyo",
        audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00"),
        endReason = ChunkEndReason.ROTATION,
    )
}
