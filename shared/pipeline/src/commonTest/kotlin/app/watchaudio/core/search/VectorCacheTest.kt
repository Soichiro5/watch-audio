package app.watchaudio.core.search

import app.watchaudio.core.db.PassageVector
import app.watchaudio.core.embedding.Int8Vectors
import app.watchaudio.core.embedding.OnnxEmbedder
import app.watchaudio.core.platform.openPositionalFile
import app.watchaudio.testing.TempDir
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The flat-file vector cache and its brute-force search (#24). */
class VectorCacheTest {
    private val dir = TempDir("vector-cache")
    private val directory = dir.path.toString()
    private val dim = 16
    private val random = Random(7)

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    private fun open(token: String = "t1", model: String = "m") = VectorCache.open(directory, "cache", model, dim, token)

    private fun unit(): FloatArray = OnnxEmbedder.normalize(FloatArray(dim) { random.nextFloat() - 0.5f })

    private fun stored(id: Long, vector: FloatArray): PassageVector {
        val (values, scale) = Int8Vectors.quantize(vector)
        return PassageVector(id, scale, values)
    }

    /** The exact top [k] by the same int8 scores, for comparison. */
    private fun expectedTop(query: FloatArray, vectors: Map<Long, FloatArray>, k: Int): List<Long> {
        val (q, qs) = Int8Vectors.quantize(query)
        return vectors.entries.map { (id, v) ->
            val (values, scale) = Int8Vectors.quantize(v)
            id to Int8Vectors.dot(q, qs, values, scale)
        }.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first }).take(k).map { it.first }
    }

    @Test
    fun findsTheSameTopKAsAnExactScanWithAnyParallelism() = runTest {
        val vectors = (1L..20_000L).associateWith { unit() }
        open().use { cache ->
            vectors.entries.chunked(3_000).forEach { batch -> cache.append(batch.map { stored(it.key, it.value) }) }
            cache.commit(0)
            repeat(5) {
                val query = unit()
                val expected = expectedTop(query, vectors, 10)
                val (q, qs) = Int8Vectors.quantize(query)
                for (parallelism in listOf(1, 3, 4)) {
                    val hits = cache.search(q, qs, 10, parallelism)
                    assertEquals(expected, hits.map { it.passageId }, "parallelism $parallelism")
                }
            }
            // A passage is closest to itself, with a score close to 1.
            val (q, qs) = Int8Vectors.quantize(vectors.getValue(1234))
            val best = cache.search(q, qs, 1).single()
            assertEquals(1234, best.passageId)
            assertTrue(best.score > 0.99f, "score ${best.score}")
        }
    }

    @Test
    fun deletedPassagesAreWipedAndSkipped() = runTest {
        val vectors = (1L..10L).associateWith { unit() }
        open().use { cache ->
            cache.append(vectors.map { stored(it.key, it.value) })
            assertEquals(2, cache.delete(listOf(3, 7, 99)))
            assertEquals(0, cache.delete(listOf(3)))
            cache.commit(5)
            assertEquals(8, cache.liveCount)
            val (q, qs) = Int8Vectors.quantize(vectors.getValue(3))
            val hits = cache.search(q, qs, 10)
            assertEquals(8, hits.size)
            assertTrue(hits.none { it.passageId == 3L || it.passageId == 7L })
        }
        // The deleted vector's bytes are zero on disk.
        open().use { cache ->
            assertEquals(5, cache.deletionSeq)
            assertEquals(8, cache.liveCount)
            assertEquals(10, cache.size)
        }
        val bytes = ByteArray(dim)
        openPositionalFile(Path(dir.path, "cache.vec").toString()).use { it.read(64L + 2 * dim, bytes) }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun reopeningKeepsCommittedRecordsAndDropsTheRest() = runTest {
        open().use { cache ->
            cache.append(listOf(stored(1, unit()), stored(2, unit())))
            cache.commit(0)
            // Appended but never committed (the app died).
            cache.append(listOf(stored(3, unit())))
        }
        open().use { cache ->
            assertEquals(2, cache.size)
            assertEquals(2, cache.lastPassageId)
            cache.append(listOf(stored(3, unit())))
            cache.commit(0)
            assertEquals(3, cache.size)
        }
    }

    @Test
    fun anotherDatabaseModelOrBrokenFileStartsOver() = runTest {
        open().use { cache ->
            cache.append(listOf(stored(1, unit())))
            cache.commit(0)
        }
        open(token = "t2").use { assertEquals(0, it.size) }
        open(token = "t2").use { cache ->
            cache.append(listOf(stored(5, unit())))
            cache.commit(0)
        }
        open(token = "t2", model = "other").use { assertEquals(0, it.size) }
        SystemFileSystem.delete(Path(dir.path, "cache.vec"))
        open(token = "t2", model = "other").use { assertEquals(0, it.size) }
        VectorCache.delete(directory, "cache")
        assertTrue(SystemFileSystem.list(dir.path).isEmpty())
    }

    @Test
    fun compactionKeepsTheLivePassagesAndTheirOrder() = runTest {
        val vectors = (1L..4_000L).associateWith { unit() }
        open().use { cache ->
            cache.append(vectors.map { stored(it.key, it.value) })
            cache.commit(0)
            cache.delete((1L..4_000L).filter { it % 3 == 0L })
            cache.commit(9)
            assertTrue(cache.needsCompaction)
            cache.compact()
            assertEquals(cache.size, cache.liveCount)
            assertTrue(!cache.needsCompaction)
        }
        val live = vectors.filterKeys { it % 3 != 0L }
        open().use { cache ->
            assertEquals(live.size, cache.size)
            assertEquals(9, cache.deletionSeq)
            assertEquals(4_000, cache.lastPassageId)
            // Deleting after compaction still finds the record by id.
            assertEquals(1, cache.delete(listOf(4_000)))
            val query = unit()
            val (q, qs) = Int8Vectors.quantize(query)
            assertEquals(expectedTop(query, live - 4_000L, 5), cache.search(q, qs, 5).map { it.passageId })
        }
    }

    @Test
    fun emptyCacheFindsNothing() = runTest {
        open().use { cache ->
            val (q, qs) = Int8Vectors.quantize(unit())
            assertEquals(emptyList(), cache.search(q, qs, 10))
        }
    }
}
