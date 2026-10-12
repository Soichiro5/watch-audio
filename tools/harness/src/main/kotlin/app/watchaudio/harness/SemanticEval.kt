package app.watchaudio.harness

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.embedding.Embedder
import app.watchaudio.core.embedding.EmbeddingPassages
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.search.HybridSearch
import app.watchaudio.core.search.SemanticIndex
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search-quality evaluation of semantic search (#24) through the app's code path: realistic
 * transcripts go into a real [LocalStore] as 5-minute chunks of short segments, the
 * [EmbeddingStep] embeds them with the real model, and every query runs through the keyword search
 * alone and through [HybridSearch] (keyword + meaning, RRF). Recorded: whether a relevant segment is
 * among the top 5 results.
 *
 * Data (nothing synthetic about the wording; only the timing is made up):
 * - the 16 test-audio conversations (`tools/test-audio/corpus`, 553 lines cut at sentence ends into
 *   segments like the ASR's), each on its own day, with the conversation-like queries of
 *   `testdata/semantic-eval-queries.json`;
 * - the M0-6 conversational set (`research/embedding-benchmark/data/conv_eval.json`): 50 queries
 *   and 2,136 utterances (46 relevant, 90 hard negatives sharing words with the queries, 2,000
 *   fillers), the utterances shuffled into conversations of 6–12 so each relevant one sits among
 *   unrelated chatter in its passage.
 *
 * Transcripts are the reference text, not ASR output (recognition errors are not part of this).
 */
class SemanticEval(
    private val repoRoot: File,
    private val workDir: File,
    private val embedder: Embedder,
    private val rules: EmbeddingPassages.Rules = EmbeddingPassages.Rules(),
) {
    data class Query(val id: String, val type: String, val text: String, val relevant: Set<String>)

    data class Outcome(
        val query: Query,
        /** Rank (1-based) of the first relevant result, or null if not in the top [TOP]. */
        val keywordRank: Int?,
        val hybridRank: Int?,
        /** Meaning search alone (passages, best first). */
        val meaningRank: Int?,
        /** Similarity of the best relevant passage and of the best other one. */
        val bestRelevantScore: Float?,
        val bestOtherScore: Float?,
        /** Results the hybrid search showed in its top [TOP] that the keyword search did not have. */
        val hybridTop: List<String>,
    )

    data class Report(
        /** Per meaning band tried ([HybridSearch.MEANING_BAND]): the outcome of every query. */
        val outcomes: Map<Float, List<Outcome>>,
        val segments: Int,
        val chunks: Int,
        val passages: Int,
        val embedSeconds: Double,
    )

    /** Builds the store, embeds it and runs every query once per [bands] value for the meaning hits. */
    suspend fun run(bands: List<Float> = listOf(HybridSearch.MEANING_BAND)): Report {
        workDir.deleteRecursively()
        workDir.mkdirs()
        LocalStore.open(File(workDir, LocalStore.FILE_NAME).path).use { store ->
            val keys = HashMap<Long, String>()
            val (chunks, segments) = load(store, keys)
            val index = SemanticIndex(store, embedder, File(workDir, "index").path)
            val step = EmbeddingStep(store, embedder, index, rules = rules, limits = EmbeddingStep.Limits(chunksPerRun = 100_000, maxRunMs = Long.MAX_VALUE))
            val started = System.nanoTime()
            val embedded = step.run(onBattery = false, nowMs = System.currentTimeMillis()) { true }
            val seconds = (System.nanoTime() - started) / 1e9
            val queries = queries()
            val outcomes = bands.associateWith { band -> queries.map { evaluate(store, index, keys, it, band) } }
            return Report(outcomes, segments, chunks, embedded.passages, seconds)
        }
    }

    private suspend fun evaluate(store: LocalStore, index: SemanticIndex, keys: Map<Long, String>, query: Query, band: Float): Outcome {
        fun relevant(segmentIds: Collection<Long>) = segmentIds.any { keys[it] in query.relevant }
        val keyword = store.read { search(query.text, 100) }
        val keywordRank = keyword.take(TOP).indexOfFirst { relevant(listOf(it.segment.id)) }.takeIf { it >= 0 }?.plus(1)

        val hybrid = HybridSearch.search(store, index, query.text, band = band).hits
        val passageOf = store.read { passagesOfSegments(hybrid.map { it.segment.id }, embedder.spec.id) }
        val passages = store.read { passages(passageOf.values.distinct()) }.associateBy { it.id }
        // A meaning hit stands for its passage; a keyword-only hit for its segment.
        val covered = hybrid.map { hit ->
            if (hit.byMeaning) passageOf[hit.segment.id]?.let { passages[it] }?.segments?.map { it.id } ?: listOf(hit.segment.id) else listOf(hit.segment.id)
        }
        val hybridRank = covered.take(TOP).indexOfFirst { relevant(it) }.takeIf { it >= 0 }?.plus(1)

        val meaning = index.search(query.text, 50)
        val meaningPassages = store.read { passages(meaning.map { it.passageId }) }
        val scored = meaning.zip(meaningPassages) { hit, passage -> hit.score to relevant(passage.segments.map { it.id }) }
        val meaningRank = scored.take(TOP).indexOfFirst { it.second }.takeIf { it >= 0 }?.plus(1)
        return Outcome(
            query, keywordRank, hybridRank, meaningRank,
            bestRelevantScore = scored.firstOrNull { it.second }?.first,
            bestOtherScore = scored.firstOrNull { !it.second }?.first,
            hybridTop = hybrid.take(TOP).map { (if (it.byKeyword) "K" else "") + (if (it.byMeaning) "M" else "") + ":" + it.snippet },
        )
    }

    /** Puts every conversation into [store]; [keys] maps segment ids to their source key. @return chunks and segments. */
    private suspend fun load(store: LocalStore, keys: MutableMap<Long, String>): Pair<Int, Int> {
        val conversations = corpusConversations() + convEvalConversations()
        var chunks = 0
        var segments = 0
        conversations.forEachIndexed { index, conversation ->
            // One conversation every ~19 hours from 2026-06-01 09:00 UTC, so days and times vary.
            val start = 1_780_304_400_000L + index * 68_400_000L
            val timed = conversation.mapIndexed { i, (key, text) ->
                val at = start + i * 3_000L
                Triple(key, text, at)
            }
            for ((window, inChunk) in timed.groupBy { (it.third - start) / CHUNK_MS }) {
                val chunkStart = start + window * CHUNK_MS
                val id = "eval-$index-$window"
                store.transaction {
                    insertChunk(manifest(id, chunkStart), "$id.m4a", chunkStart + CHUNK_MS)
                    saveTranscript(
                        id,
                        inChunk.map { (_, text, at) -> NewSegment(at, at + minOf(2_800L, 500L + text.length * 150L), text, "ja") },
                        "reference",
                    )
                }
                val stored = store.read { chunkSegments(id) }
                check(stored.size == inChunk.size) { "segments of $id" }
                stored.forEachIndexed { i, segment -> keys[segment.id] = inChunk[i].first }
                chunks++
                segments += stored.size
            }
        }
        return chunks to segments
    }

    /** The corpus conversations, a segment per sentence (key `corpus:<file>:<line>`). */
    private fun corpusConversations(): List<List<Pair<String, String>>> =
        File(repoRoot, "tools/test-audio/corpus").listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }.map { file ->
            val number = file.name.take(2)
            file.readLines().filter { !it.startsWith("#") && ": " in it }.flatMapIndexed { i, line ->
                val text = RUBY.replace(line.substringAfter(": ").trim()) { it.groupValues[1] }
                SENTENCE_END.split(text).map { it.trim() }.filter { it.isNotEmpty() }.map { "corpus:$number:${i + 1}" to it }
            }
        }

    /** conv_eval's utterances shuffled into conversations of 6–12 (key = the doc id). */
    private fun convEvalConversations(): List<List<Pair<String, String>>> {
        val docs = json("research/embedding-benchmark/data/conv_eval.json")["docs"]!!.jsonArray.map {
            val doc = it.jsonObject
            doc["id"]!!.jsonPrimitive.content to doc["text"]!!.jsonPrimitive.content
        }
        val random = Random(24)
        val shuffled = docs.shuffled(random)
        val out = mutableListOf<List<Pair<String, String>>>()
        var at = 0
        while (at < shuffled.size) {
            val size = 6 + random.nextInt(7)
            out += shuffled.subList(at, minOf(shuffled.size, at + size))
            at += size
        }
        return out
    }

    fun queries(): List<Query> {
        val corpus = json("tools/harness/testdata/semantic-eval-queries.json")["queries"]!!.jsonArray.map {
            val q = it.jsonObject
            val file = q["file"]!!.jsonPrimitive.content
            Query(
                q["id"]!!.jsonPrimitive.content,
                "corpus_" + q["type"]!!.jsonPrimitive.content,
                q["text"]!!.jsonPrimitive.content,
                q["lines"]!!.jsonArray.map { line -> "corpus:$file:${line.jsonPrimitive.int}" }.toSet(),
            )
        }
        val conv = json("research/embedding-benchmark/data/conv_eval.json")["queries"]!!.jsonArray.map {
            val q = it.jsonObject
            Query(
                q["id"]!!.jsonPrimitive.content,
                "conv_" + q["type"]!!.jsonPrimitive.content,
                q["text"]!!.jsonPrimitive.content,
                q["relevant"]!!.jsonArray.map { r -> r.jsonPrimitive.content }.toSet(),
            )
        }
        return corpus + conv
    }

    private fun json(path: String) = Json.parseToJsonElement(File(repoRoot, path).readText()).jsonObject

    private fun manifest(id: String, startedAt: Long) = ChunkManifest(
        chunkId = id,
        sessionId = "eval",
        deviceId = "eval",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = startedAt,
        durationMs = CHUNK_MS,
        timeZone = "Asia/Tokyo",
        audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00"),
        endReason = ChunkEndReason.ROTATION,
    )

    companion object {
        const val TOP = 5
        private const val CHUNK_MS = 300_000L
        private val RUBY = Regex("""\{([^|{}]+)\|[^{}]+\}""")
        private val SENTENCE_END = Regex("(?<=[。？！?!])")

        /** Top-[TOP] hit rates per query type, as a Markdown table. */
        fun table(outcomes: List<Outcome>): String = buildString {
            appendLine("| クエリの種類 | 件数 | キーワードのみ 上位5件 | ハイブリッド 上位5件 | 意味のみ 上位5件 |")
            appendLine("|---|---|---|---|---|")
            val groups = outcomes.groupBy { it.query.type }.toSortedMap() + mapOf("全体" to outcomes)
            for ((type, group) in groups) {
                fun rate(rank: (Outcome) -> Int?) = "%.2f (%d)".format(group.count { rank(it) != null }.toDouble() / group.size, group.count { rank(it) != null })
                appendLine("| $type | ${group.size} | ${rate { it.keywordRank }} | ${rate { it.hybridRank }} | ${rate { it.meaningRank }} |")
            }
        }
    }
}
