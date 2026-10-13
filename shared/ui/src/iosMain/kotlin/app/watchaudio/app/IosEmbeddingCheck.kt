package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.embedding.EmbeddingStep
import app.watchaudio.core.embedding.ModelDownload
import app.watchaudio.core.embedding.ModelFetcher
import app.watchaudio.core.embedding.ModelInstaller
import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.search.HybridSearch
import app.watchaudio.core.search.SemanticIndex
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/** One reference text of [IosEmbeddingCheck.compare]: its cosine with the JVM's vector. */
class IosEmbeddingAgreement(val text: String, val query: Boolean, val cosine: Double, val norm: Double)

/** A ranking query of [IosEmbeddingCheck.compare]: its scores over the passages and which must win. */
class IosEmbeddingRanking(val query: String, val scores: List<Double>, val expected: Int, val best: Int)

/** What [IosEmbeddingCheck.compare] measured on this device. */
class IosEmbeddingComparison(
    val runtimeVersion: String,
    val dimension: Int,
    /** Opening the model and embedding the first query. */
    val loadMs: Long,
    /** Per passage, on average. */
    val passageMs: Long,
    val queryMs: Long,
    val agreements: List<IosEmbeddingAgreement>,
    val rankings: List<IosEmbeddingRanking>,
)

/** What [IosEmbeddingCheck.search] found: the chunk ids of the hits, best first, and how. */
class IosEmbeddingSearch(
    val embeddedChunks: Int,
    val hitChunks: List<String>,
    val expectedChunk: String,
    val topByMeaning: Boolean,
    val anyByKeyword: Boolean,
)

/** What [IosEmbeddingCheck.download] observed. */
class IosEmbeddingDownload(
    /** Bytes in the `.part` file after the interrupted first attempt. */
    val interruptedAt: Long,
    /** The second attempt continued the file (asked for the rest and appended to it). */
    val resumed: Boolean,
    val installed: Boolean,
    val state: String,
    /** The models directory has `isExcludedFromBackup`. */
    val excludedFromBackup: Boolean,
    val seconds: Double,
)

/**
 * Semantic search on iOS end to end, for the simulator checks in the app process
 * (`apps/ios/Tests/EmbeddingTests`, #178), through the app's own Swift [IosEmbeddingPlatform]
 * (ONNX Runtime, `URLSession`) and the shared Kotlin code, in temporary directories (the app's own
 * data is not touched):
 *
 * - [compare]: the reference texts of `embedding-reference.json` (vectors computed on the JVM by
 *   tools/harness `DeviceEmbeddingFixtureTest`, as Android's `EmbeddingDeviceTest` uses them) embed
 *   to the same vectors here, and the ranking queries rank their passage first (also used with the
 *   Python reference `ruri-reference.json`, which has short and unusual texts);
 * - [search]: the embedding step and the hybrid search find a conversation by a paraphrase that
 *   shares no word with it;
 * - [download]: the app's download ([ModelInstaller] over [IosModelFetcher]) fetches a model file,
 *   continues after an interruption and checks its SHA-256.
 *
 * Callbacks run on a background thread, exactly once, with the result or an error message.
 */
@OptIn(ExperimentalForeignApi::class)
object IosEmbeddingCheck {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun compare(platform: IosEmbeddingPlatform, modelsRoot: String, referenceJson: String, callback: (IosEmbeddingComparison?, String?) -> Unit) {
        scope.launch {
            val result = runCatching { compareNow(platform, modelsRoot, Reference.parse(referenceJson)) }
            callback(result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: it.toString() })
        }
    }

    fun search(platform: IosEmbeddingPlatform, modelsRoot: String, referenceJson: String, query: String, callback: (IosEmbeddingSearch?, String?) -> Unit) {
        scope.launch {
            val dir = Path(NSTemporaryDirectory(), "embedding-check-${Random.nextLong().toULong().toString(16)}")
            val result = runCatching { searchNow(platform, modelsRoot, Reference.parse(referenceJson), query, dir) }
            deleteRecursively(dir)
            callback(result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: it.toString() })
        }
    }

    /**
     * Downloads the tokenizer of [RuriV3.FILES] (6.7 MB) twice into a temporary directory: the first
     * attempt is cancelled once [interruptAfterBytes] arrived, the second must continue the `.part`
     * file and install the checked file.
     */
    fun download(platform: IosEmbeddingPlatform, interruptAfterBytes: Long, callback: (IosEmbeddingDownload?, String?) -> Unit) {
        scope.launch {
            val dir = Path(NSTemporaryDirectory(), "embedding-download-${Random.nextLong().toULong().toString(16)}")
            val result = runCatching { downloadNow(platform, interruptAfterBytes, dir) }
            deleteRecursively(dir)
            callback(result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: it.toString() })
        }
    }

    private suspend fun compareNow(platform: IosEmbeddingPlatform, modelsRoot: String, reference: Reference): IosEmbeddingComparison {
        val embedder = IosEmbedding.embedder(platform, modelsRoot)
        try {
            val firstQuery = reference.texts.first { it.query }
            var mark = TimeSource.Monotonic.markNow()
            embedder.embedQuery(firstQuery.text)
            val loadMs = mark.elapsedNow().inWholeMilliseconds
            val passages = reference.texts.filter { !it.query }
            mark = TimeSource.Monotonic.markNow()
            val passageVectors = embedder.embedDocuments(passages.map { it.text })
            val passageMs = mark.elapsedNow().inWholeMilliseconds / passages.size
            var next = 0
            val vectors = reference.texts.map { if (it.query) embedder.embedQuery(it.text) else passageVectors[next++] }
            mark = TimeSource.Monotonic.markNow()
            embedder.embedQuery(firstQuery.text)
            val queryMs = mark.elapsedNow().inWholeMilliseconds
            val agreements = reference.texts.mapIndexed { i, text ->
                IosEmbeddingAgreement(text.text, text.query, dot(vectors[i], text.vector), dot(vectors[i], vectors[i]))
            }
            val rankings = reference.ranking.map { (query, passage) ->
                val scores = reference.passages.map { dot(vectors[query], vectors[it]) }
                IosEmbeddingRanking(reference.texts[query].text, scores, reference.passages.indexOf(passage), scores.indices.maxBy { scores[it] })
            }
            return IosEmbeddingComparison(platform.runtimeVersion, vectors.first().size, loadMs, passageMs, queryMs, agreements, rankings)
        } finally {
            embedder.release()
        }
    }

    private suspend fun searchNow(platform: IosEmbeddingPlatform, modelsRoot: String, reference: Reference, query: String, dir: Path): IosEmbeddingSearch {
        SystemFileSystem.createDirectories(dir)
        val embedder = IosEmbedding.embedder(platform, modelsRoot)
        try {
            return LocalStore.open(Path(dir, "search.db").toString()).use { store ->
                val start = Clock.System.now().toEpochMilliseconds() - 7 * 86_400_000L
                reference.passages.forEachIndexed { i, p ->
                    val id = "passage-$i"
                    val at = start + i * 3_600_000L
                    store.transaction {
                        insertChunk(manifest(id, at), "$id.m4a", at)
                        val parts = reference.texts[p].text.split(' ')
                        saveTranscript(id, parts.mapIndexed { j, part -> NewSegment(at + j * 3_000L, at + j * 3_000L + 2_000, part, "ja") }, "check")
                    }
                }
                val index = SemanticIndex(store, embedder, Path(dir, "index").toString())
                val outcome = EmbeddingStep(store, embedder, index).run(onBattery = false, nowMs = Clock.System.now().toEpochMilliseconds()) { true }
                val (queryIndex, passage) = reference.ranking.first { (q, _) -> reference.texts[q].text == query }
                val hits = HybridSearch.search(store, index, reference.texts[queryIndex].text).hits
                IosEmbeddingSearch(
                    embeddedChunks = outcome.embeddedChunks,
                    hitChunks = hits.map { it.segment.chunkId },
                    expectedChunk = "passage-${reference.passages.indexOf(passage)}",
                    topByMeaning = hits.firstOrNull()?.byMeaning == true,
                    anyByKeyword = hits.any { it.byKeyword },
                )
            }
        } finally {
            embedder.release()
        }
    }

    private suspend fun downloadNow(platform: IosEmbeddingPlatform, interruptAfterBytes: Long, dir: Path): IosEmbeddingDownload {
        val modelsRoot = Path(dir, "models")
        LocalDataDirectory.prepare(modelsRoot.toString())
        val file: ModelDownload = RuriV3.FILES.single { it.path.endsWith(RuriV3.TOKENIZER_FILE) }
        val started = TimeSource.Monotonic.markNow()
        // First attempt: cancelled once enough bytes arrived (or as soon as it ended).
        val first = ModelInstaller(listOf(file), modelsRoot, IosModelFetcher(platform), scope)
        coroutineScope {
            val job = launch { first.install() }
            first.state.first { state ->
                (state is ModelState.Downloading && state.progress * file.size >= interruptAfterBytes) ||
                    state == ModelState.Ready || state is ModelState.Failed
            }
            job.cancel()
            job.join()
        }
        check(!first.isReady()) { "the first attempt was not interrupted (state ${first.state.value})" }
        val part = Path(modelsRoot, file.path + ModelInstaller.PART_SUFFIX)
        val interruptedAt = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
        // Second attempt: must continue the part file.
        var firstProgress: Long? = null
        var askedOffset = -1L
        val resuming = object : ModelFetcher {
            override suspend fun fetch(url: String, part: Path, offset: Long, onProgress: (Long) -> Unit, onWaiting: () -> Unit) {
                askedOffset = offset
                IosModelFetcher(platform).fetch(url, part, offset, { bytes -> if (firstProgress == null) firstProgress = bytes; onProgress(bytes) }, onWaiting)
            }
        }
        val second = ModelInstaller(listOf(file), modelsRoot, resuming, scope)
        val installed = second.install()
        val excluded = NSURL.fileURLWithPath(modelsRoot.toString()).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
            ?.get(NSURLIsExcludedFromBackupKey) as? NSNumber
        return IosEmbeddingDownload(
            interruptedAt = interruptedAt,
            resumed = interruptedAt > 0 && askedOffset == interruptedAt && (firstProgress ?: 0L) > interruptedAt,
            installed = installed && second.currentState() == ModelState.Ready,
            state = second.currentState().toString(),
            excludedFromBackup = excluded?.boolValue == true,
            seconds = started.elapsedNow().inWholeMilliseconds / 1000.0,
        )
    }

    private fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] }

    private fun manifest(id: String, startedAt: Long) = ChunkManifest(
        chunkId = id,
        sessionId = "embedding-check",
        deviceId = "simulator",
        source = SourcePlatform.WATCHOS,
        startedAtEpochMs = startedAt,
        durationMs = 300_000L,
        timeZone = "Asia/Tokyo",
        audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00"),
        endReason = ChunkEndReason.ROTATION,
    )

    private fun deleteRecursively(path: Path) {
        runCatching {
            val metadata = SystemFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    /**
     * `embedding-reference.json`: texts with the JVM's vectors, and which passage each ranking query
     * must put first; or `ruri-reference.json` (the Python reference of M0-6, texts only).
     */
    private class Reference(val texts: List<Text>, val ranking: List<Pair<Int, Int>>) {
        class Text(val query: Boolean, val text: String, val vector: FloatArray)

        val passages: List<Int> get() = texts.indices.filter { !texts[it].query }

        companion object {
            fun parse(json: String): Reference {
                val root = Json.parseToJsonElement(json).jsonObject
                val texts = root["texts"]!!.jsonArray.map { element ->
                    val entry = element.jsonObject
                    Text(
                        entry["query"]!!.jsonPrimitive.boolean,
                        entry["text"]!!.jsonPrimitive.content,
                        entry["vector"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                    )
                }
                // The Python reference (tools/harness/testdata/ruri-reference.json) has no rankings.
                val ranking = root["ranking"]?.jsonArray?.map { element ->
                    element.jsonObject.let { it["query"]!!.jsonPrimitive.int to it["passage"]!!.jsonPrimitive.int }
                }.orEmpty()
                check(texts.all { abs(it.vector.sumOf { v -> v.toDouble() * v } - 1.0) < 1e-3 }) { "reference vectors are not unit length" }
                return Reference(texts, ranking)
            }
        }
    }
}
