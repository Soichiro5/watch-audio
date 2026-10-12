package app.watchaudio.core.search

import app.watchaudio.core.db.PassageVector
import app.watchaudio.core.platform.PositionalFile
import app.watchaudio.core.platform.openPositionalFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A stored passage and how close it is to the query (≈ cosine similarity). */
data class VectorHit(val passageId: Long, val score: Float)

/**
 * The passage vectors of one embedding model as flat files, for brute-force search (#24, M0-6
 * §4): reading every vector from SQLite on each query takes seconds, a scan over a flat array a
 * few tens of ms. The files are a cache of `passage_embedding` ([SemanticIndex] keeps them in step
 * through the deletion log) and can be deleted at any time; they live with the app's other data
 * and are never backed up.
 *
 * - `<name>.ids`: a header (format, dimension, generation, record count, the last passage id and
 *   deletion log position applied, the database token and model id) and per record the passage
 *   id (ascending) and scale. Loaded into memory on open (12 bytes per passage).
 * - `<name>.vec`: a header with the same generation, then [dimension] int8 values per record, read
 *   in blocks by several threads while scanning (the OS page cache keeps them in memory; the Java
 *   heap does not hold them).
 *
 * Deleted passages are wiped in place (scale 0 and zero values: no trace of the deleted text's
 * meaning is left) and skipped; [compact] rewrites the files once many are deleted. Appends and
 * patches become valid with [commit], which writes the header last, so after a crash the files
 * are cut back to the last committed record count. Not thread-safe: the caller serializes calls.
 */
class VectorCache private constructor(
    private val idsPath: Path,
    private val vecPath: Path,
    val modelId: String,
    val dimension: Int,
    private var idsFile: PositionalFile,
    private var vecFile: PositionalFile,
    private var header: Header,
) : AutoCloseable {
    private var ids = LongArray(maxOf(INITIAL_CAPACITY, header.count))
    private var scales = FloatArray(ids.size)
    private var count = 0
    private var deleted = 0

    /** Records in the files, deleted ones included. */
    val size: Int get() = count

    /** Records of passages that still exist. */
    val liveCount: Int get() = count - deleted

    /** The database this cache was built from (`embedding_state.token`). */
    val token: String get() = header.token

    /** Highest passage id appended (committed or not). */
    var lastPassageId: Long = header.lastPassageId
        private set

    /** Position in the deletion log applied so far (committed with [commit]). */
    val deletionSeq: Long get() = header.deletionSeq

    init {
        loadIds()
    }

    /** Appends [vectors] (ids ascending and above [lastPassageId]); valid once [commit]ted. */
    fun append(vectors: List<PassageVector>) {
        if (vectors.isEmpty()) return
        ensureCapacity(count + vectors.size)
        val idBytes = ByteArray(vectors.size * ID_RECORD)
        val vecBytes = ByteArray(vectors.size * dimension)
        vectors.forEachIndexed { i, v ->
            require(v.id > lastPassageId) { "passage ids must ascend" }
            require(v.vector.size == dimension) { "vector of ${v.vector.size} values, expected $dimension" }
            idBytes.putLong(i * ID_RECORD, v.id)
            idBytes.putInt(i * ID_RECORD + 8, v.scale.toRawBits())
            v.vector.copyInto(vecBytes, i * dimension)
            ids[count + i] = v.id
            scales[count + i] = v.scale
            lastPassageId = v.id
        }
        idsFile.write(ID_HEADER + count.toLong() * ID_RECORD, idBytes)
        vecFile.write(VEC_HEADER + count.toLong() * dimension, vecBytes)
        count += vectors.size
    }

    /** Wipes the records of [passageIds] that are cached; @return how many were. */
    fun delete(passageIds: Collection<Long>): Int {
        var wiped = 0
        val zeros = ByteArray(dimension)
        val zeroScale = ByteArray(4)
        for (id in passageIds) {
            val index = ids.binarySearch(id, 0, count)
            if (index < 0 || scales[index] == 0f) continue
            scales[index] = 0f
            idsFile.write(ID_HEADER + index.toLong() * ID_RECORD + 8, zeroScale)
            vecFile.write(VEC_HEADER + index.toLong() * dimension, zeros)
            deleted++
            wiped++
        }
        return wiped
    }

    /** Makes the appends and deletions so far durable and records how far the log was applied. */
    fun commit(deletionSeq: Long) {
        header = header.copy(count = count, lastPassageId = lastPassageId, deletionSeq = deletionSeq)
        vecFile.sync()
        idsFile.write(0, header.encode())
        idsFile.sync()
    }

    /** Drops every record and starts over for the database [token]. */
    fun reset(token: String) {
        val generation = header.generation + 1
        header = Header(dimension, generation, 0, 0, 0, token, modelId)
        count = 0
        deleted = 0
        lastPassageId = 0
        idsFile.truncate(0)
        vecFile.truncate(0)
        vecFile.write(0, vecHeader(generation))
        commit(0)
    }

    /** True when deleted records make up enough of the files to be worth a [compact]. */
    val needsCompaction: Boolean get() = deleted >= COMPACT_MIN_DELETED && deleted * 4 >= count

    /** Rewrites the files without deleted records. */
    fun compact() {
        val generation = header.generation + 1
        val idsTemp = Path(idsPath.toString() + TEMP_SUFFIX)
        val vecTemp = Path(vecPath.toString() + TEMP_SUFFIX)
        var kept = 0
        openPositionalFile(idsTemp.toString()).use { newIds ->
            openPositionalFile(vecTemp.toString()).use { newVec ->
                newIds.truncate(0)
                newVec.truncate(0)
                newVec.write(0, vecHeader(generation))
                val block = ByteArray(BLOCK_RECORDS * dimension)
                var start = 0
                while (start < count) {
                    val n = minOf(BLOCK_RECORDS, count - start)
                    vecFile.read(VEC_HEADER + start.toLong() * dimension, block, 0, n * dimension)
                    for (j in 0 until n) {
                        val index = start + j
                        if (scales[index] == 0f) continue
                        newVec.write(VEC_HEADER + kept.toLong() * dimension, block, j * dimension, dimension)
                        val record = ByteArray(ID_RECORD)
                        record.putLong(0, ids[index])
                        record.putInt(8, scales[index].toRawBits())
                        newIds.write(ID_HEADER + kept.toLong() * ID_RECORD, record)
                        ids[kept] = ids[index]
                        scales[kept] = scales[index]
                        kept++
                    }
                    start += n
                }
                header = header.copy(generation = generation, count = kept)
                newVec.sync()
                newIds.write(0, header.encode())
                newIds.sync()
            }
        }
        idsFile.close()
        vecFile.close()
        // A crash between the two moves leaves files of different generations: open() starts over.
        SystemFileSystem.atomicMove(vecTemp, vecPath)
        SystemFileSystem.atomicMove(idsTemp, idsPath)
        idsFile = openPositionalFile(idsPath.toString())
        vecFile = openPositionalFile(vecPath.toString())
        count = kept
        deleted = 0
    }

    /**
     * The [k] cached passages closest to the quantized [query] (scale [queryScale]), best first:
     * an int8 dot product with every live record, the records split into [parallelism] ranges
     * scanned at the same time.
     */
    suspend fun search(query: ByteArray, queryScale: Float, k: Int, parallelism: Int = DEFAULT_PARALLELISM): List<VectorHit> {
        require(query.size == dimension) { "query of ${query.size} values, expected $dimension" }
        if (k <= 0 || count == 0) return emptyList()
        val total = count
        val parts = parallelism.coerceIn(1, maxOf(1, total / MIN_RECORDS_PER_PART))
        val tops = coroutineScope {
            (0 until parts).map { part ->
                val from = (total.toLong() * part / parts).toInt()
                val to = (total.toLong() * (part + 1) / parts).toInt()
                async(Dispatchers.Default) { scan(from, to, query, k) }
            }.awaitAll()
        }
        val merged = TopK(k)
        tops.forEach { top -> top.forEach { index, score -> merged.offer(index, score) } }
        return merged.sortedDescending().map { (index, score) -> VectorHit(ids[index], score * queryScale) }
    }

    /** Raw int dot products times the record's scale for records `[from, to)`. */
    private fun scan(from: Int, to: Int, query: ByteArray, k: Int): TopK {
        val top = TopK(k)
        val dim = dimension
        val block = ByteArray(BLOCK_RECORDS * dim)
        var start = from
        while (start < to) {
            val n = minOf(BLOCK_RECORDS, to - start)
            val read = vecFile.read(VEC_HEADER + start.toLong() * dim, block, 0, n * dim)
            check(read == n * dim) { "vector file shorter than its header says" }
            for (j in 0 until n) {
                val scale = scales[start + j]
                if (scale == 0f) continue
                top.offer(start + j, dot(query, block, j * dim, dim) * scale)
            }
            start += n
        }
        return top
    }

    private fun loadIds() {
        val buffer = ByteArray(BLOCK_RECORDS * ID_RECORD)
        var start = 0
        while (start < header.count) {
            val n = minOf(BLOCK_RECORDS, header.count - start)
            val read = idsFile.read(ID_HEADER + start.toLong() * ID_RECORD, buffer, 0, n * ID_RECORD)
            check(read == n * ID_RECORD) { "id file shorter than its header says" }
            for (j in 0 until n) {
                ids[start + j] = buffer.getLong(j * ID_RECORD)
                scales[start + j] = Float.fromBits(buffer.getInt(j * ID_RECORD + 8))
                if (scales[start + j] == 0f) deleted++
            }
            start += n
        }
        count = header.count
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= ids.size) return
        val capacity = maxOf(needed, ids.size * 2)
        ids = ids.copyOf(capacity)
        scales = scales.copyOf(capacity)
    }

    override fun close() {
        idsFile.close()
        vecFile.close()
    }

    private data class Header(
        val dimension: Int,
        val generation: Long,
        val count: Int,
        val lastPassageId: Long,
        val deletionSeq: Long,
        val token: String,
        val modelId: String,
    ) {
        fun encode(): ByteArray {
            val out = ByteArray(ID_HEADER)
            ID_MAGIC.encodeToByteArray().copyInto(out)
            out.putInt(8, dimension)
            out.putLong(12, generation)
            out.putInt(20, count)
            out.putLong(24, lastPassageId)
            out.putLong(32, deletionSeq)
            val names = "$token\n$modelId".encodeToByteArray()
            require(names.size <= ID_HEADER - 44) { "token and model id too long" }
            out.putInt(40, names.size)
            names.copyInto(out, 44)
            return out
        }

        companion object {
            fun decode(bytes: ByteArray): Header? {
                if (bytes.size < ID_HEADER || bytes.decodeToString(0, 8) != ID_MAGIC) return null
                val length = bytes.getInt(40)
                if (length !in 0..(ID_HEADER - 44)) return null
                val names = bytes.decodeToString(44, 44 + length).split('\n', limit = 2)
                if (names.size != 2) return null
                return Header(bytes.getInt(8), bytes.getLong(12), bytes.getInt(20), bytes.getLong(24), bytes.getLong(32), names[0], names[1])
            }
        }
    }

    companion object {
        private const val ID_MAGIC = "WAIDX001"
        private const val VEC_MAGIC = "WAVEC001"
        private const val ID_HEADER = 256
        private const val VEC_HEADER = 64
        private const val ID_RECORD = 12
        private const val INITIAL_CAPACITY = 1024
        private const val BLOCK_RECORDS = 2048
        private const val MIN_RECORDS_PER_PART = 4096
        private const val COMPACT_MIN_DELETED = 1024
        private const val TEMP_SUFFIX = ".tmp"

        /** Threads that scan at once: the big cores of a phone. */
        const val DEFAULT_PARALLELISM = 4

        /**
         * Opens (or creates) the cache files `<name>.ids` / `<name>.vec` in [directory] for
         * [modelId] and the database [token]. Files of another format, model, dimension or database,
         * or of different generations, are started over (the caller then fills them from the
         * database).
         */
        fun open(directory: String, name: String, modelId: String, dimension: Int, token: String): VectorCache {
            require(dimension > 0 && dimension % 4 == 0) { "dimension must be a positive multiple of 4" }
            SystemFileSystem.createDirectories(Path(directory))
            val idsPath = Path(directory, "$name.ids")
            val vecPath = Path(directory, "$name.vec")
            val idsFile = openPositionalFile(idsPath.toString())
            val vecFile = openPositionalFile(vecPath.toString())
            val headerBytes = ByteArray(ID_HEADER)
            val stored = if (idsFile.read(0, headerBytes) == ID_HEADER) Header.decode(headerBytes) else null
            val vecHeader = ByteArray(VEC_HEADER)
            val vecGeneration = if (vecFile.read(0, vecHeader) == VEC_HEADER && vecHeader.decodeToString(0, 8) == VEC_MAGIC) vecHeader.getLong(8) else null
            val valid = stored != null && stored.dimension == dimension && stored.modelId == modelId && stored.token == token &&
                stored.generation == vecGeneration && stored.count >= 0 &&
                idsFile.size >= ID_HEADER + stored.count.toLong() * ID_RECORD &&
                vecFile.size >= VEC_HEADER + stored.count.toLong() * dimension
            val cache = if (valid) {
                // Drop anything appended after the last commit.
                idsFile.truncate(ID_HEADER + stored!!.count.toLong() * ID_RECORD)
                vecFile.truncate(VEC_HEADER + stored.count.toLong() * dimension)
                VectorCache(idsPath, vecPath, modelId, dimension, idsFile, vecFile, stored)
            } else {
                val empty = Header(dimension, (stored?.generation ?: 0) + 1, 0, 0, 0, token, modelId)
                VectorCache(idsPath, vecPath, modelId, dimension, idsFile, vecFile, empty).also { it.reset(token) }
            }
            return cache
        }

        /** Deletes the cache files `<name>.*` in [directory] (e.g. of a model no longer used). */
        fun delete(directory: String, name: String) {
            for (suffix in listOf(".ids", ".vec", ".ids$TEMP_SUFFIX", ".vec$TEMP_SUFFIX")) {
                SystemFileSystem.delete(Path(directory, name + suffix), mustExist = false)
            }
        }

        private fun vecHeader(generation: Long): ByteArray {
            val out = ByteArray(VEC_HEADER)
            VEC_MAGIC.encodeToByteArray().copyInto(out)
            out.putLong(8, generation)
            return out
        }

        /** Integer dot product of [query] with [dim] values of [block] from [offset] (dim % 4 == 0). */
        internal fun dot(query: ByteArray, block: ByteArray, offset: Int, dim: Int): Int {
            var s0 = 0
            var s1 = 0
            var s2 = 0
            var s3 = 0
            var i = 0
            while (i < dim) {
                s0 += query[i] * block[offset + i]
                s1 += query[i + 1] * block[offset + i + 1]
                s2 += query[i + 2] * block[offset + i + 2]
                s3 += query[i + 3] * block[offset + i + 3]
                i += 4
            }
            return s0 + s1 + s2 + s3
        }
    }
}

private fun ByteArray.putInt(at: Int, value: Int) {
    for (i in 0 until 4) this[at + i] = (value ushr (8 * i)).toByte()
}

private fun ByteArray.putLong(at: Int, value: Long) {
    for (i in 0 until 8) this[at + i] = (value ushr (8 * i)).toByte()
}

private fun ByteArray.getInt(at: Int): Int {
    var value = 0
    for (i in 0 until 4) value = value or ((this[at + i].toInt() and 0xff) shl (8 * i))
    return value
}

private fun ByteArray.getLong(at: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = value or ((this[at + i].toLong() and 0xff) shl (8 * i))
    return value
}
