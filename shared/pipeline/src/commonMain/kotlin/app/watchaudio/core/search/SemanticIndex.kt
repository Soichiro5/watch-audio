package app.watchaudio.core.search

import app.watchaudio.core.db.EmbeddingProgress
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.Embedder
import app.watchaudio.core.embedding.Int8Vectors
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.platform.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** What the search screen says about searching by meaning (#24). */
sealed interface SemanticStatus {
    /** The embedding model is not on the device yet ([downloading]: its download's progress, 0..1). */
    data class WaitingForModel(val downloading: Float? = null) : SemanticStatus

    /** Older conversations are still being embedded; [fraction] (0..1) of them can be found by meaning. */
    data class Indexing(val fraction: Float) : SemanticStatus

    data object Ready : SemanticStatus
}

/**
 * Searching by meaning (#24), as the shared UI uses it (`AppServices.semanticSearch`; null where the
 * platform has no embedding model: keyword search only).
 */
interface SemanticSearch {
    /** The id of the embedding model whose passages [search] returns. */
    val modelId: String

    suspend fun status(): SemanticStatus

    /** Passages closest in meaning to [query], best first; empty while the model is missing. */
    suspend fun search(query: String, limit: Int): List<VectorHit>
}

/**
 * The on-device semantic index: the query is embedded with [embedder] and compared with every
 * passage vector of its model in a [VectorCache] under [directory], which [sync] keeps in step with
 * the database (new passages appended, deleted ones wiped through the deletion log). Nothing is
 * sent anywhere: the query, its vector and the passages stay on the device.
 *
 * One instance per process, shared by the background embedding step (which calls [sync] with
 * pruning after it stored passages, so a search rarely has much to catch up) and the search screen.
 *
 * @param modelState whether the embedding model's files are on the device.
 */
class SemanticIndex(
    private val store: LocalStore,
    private val embedder: Embedder,
    private val directory: String,
    private val modelState: () -> ModelState = { ModelState.Ready },
    private val parallelism: Int = VectorCache.DEFAULT_PARALLELISM,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
) : SemanticSearch {
    private val mutex = Mutex()
    private var cache: VectorCache? = null
    private val spec get() = embedder.spec

    override val modelId: String get() = spec.id

    override suspend fun status(): SemanticStatus {
        when (val state = modelState()) {
            ModelState.Ready -> Unit
            is ModelState.Downloading -> return SemanticStatus.WaitingForModel(state.progress)
            else -> return SemanticStatus.WaitingForModel()
        }
        val progress = progress()
        return if (progress.pending == 0) SemanticStatus.Ready else SemanticStatus.Indexing(progress.fraction)
    }

    /** How many transcribed chunks have passages for the current model. */
    suspend fun progress(): EmbeddingProgress = store.read { embeddingProgress(spec.id) }

    override suspend fun search(query: String, limit: Int): List<VectorHit> {
        if (query.isBlank() || modelState() != ModelState.Ready) return emptyList()
        val vector = embedder.embedQuery(query.trim())
        val (values, scale) = Int8Vectors.quantize(vector)
        return mutex.withLock {
            val cache = syncLocked(prune = false)
            cache.search(values, scale, limit, parallelism)
        }
    }

    /**
     * Brings the vector cache up to date with the database: a few small queries when nothing
     * changed. With [prune], the applied part of the deletion log is removed afterwards (a write
     * transaction; the background step does it, the search screen does not).
     */
    suspend fun sync(prune: Boolean = false) {
        mutex.withLock { syncLocked(prune) }
    }

    /** Deletes the cache files (e.g. after all data was deleted); the next [sync] rebuilds them. */
    suspend fun clear() = mutex.withLock {
        withContext(dispatcher) {
            cache?.close()
            cache = null
            VectorCache.delete(directory, fileName(spec.id))
        }
    }

    /** Number of passages the cache holds (for tests and diagnostics). */
    suspend fun cachedPassages(): Int = mutex.withLock { cache?.liveCount ?: 0 }

    private suspend fun syncLocked(prune: Boolean): VectorCache {
        val state = store.read { embeddingSyncState() }
        val cache = cache ?: withContext(dispatcher) { open(state.token) }.also { cache = it }
        withContext(dispatcher) {
            if (cache.token != state.token || state.deletionFloor > cache.deletionSeq) cache.reset(state.token)
        }
        var seq = cache.deletionSeq
        while (true) {
            val deletions = store.read { passageDeletions(seq, PAGE) }
            if (deletions.isEmpty()) break
            withContext(dispatcher) { cache.delete(deletions.map { it.passageId }) }
            seq = deletions.last().seq
        }
        var changed = seq != cache.deletionSeq
        while (true) {
            val after = cache.lastPassageId
            val vectors = store.read { passageVectors(spec.id, after, PAGE) }
            if (vectors.isEmpty()) break
            withContext(dispatcher) { cache.append(vectors) }
            changed = true
        }
        if (changed) {
            withContext(dispatcher) {
                cache.commit(seq)
                if (cache.needsCompaction) cache.compact()
            }
        }
        if (prune && seq > state.deletionFloor) store.transaction { pruneDeletions(seq) }
        return cache
    }

    /** Opens the cache of the current model, removing caches of other models. */
    private fun open(token: String): VectorCache {
        val name = fileName(spec.id)
        val dir = Path(directory)
        if (SystemFileSystem.exists(dir)) {
            SystemFileSystem.list(dir).filter { !it.name.startsWith("$name.") }.forEach {
                SystemFileSystem.delete(it, mustExist = false)
            }
        }
        return VectorCache.open(directory, name, spec.id, spec.dimension, token)
    }

    private companion object {
        const val PAGE = 4096

        /** A file name for a model id (`ruri-v3-30m-int8@1b41a27` → `ruri-v3-30m-int8_1b41a27`). */
        fun fileName(modelId: String): String = modelId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
    }
}
