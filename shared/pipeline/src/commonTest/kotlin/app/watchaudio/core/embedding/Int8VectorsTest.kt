package app.watchaudio.core.embedding

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Per-vector symmetric int8 quantization (#24, M0-6). */
class Int8VectorsTest {
    @Test
    fun theLargestValueMapsTo127() {
        val (values, scale) = Int8Vectors.quantize(floatArrayOf(0.5f, -0.3f, 0f, 0.125f))
        assertContentEquals(byteArrayOf(127, -76, 0, 32), values)
        assertEquals(0.5f / 127, scale)
        assertContentEquals(floatArrayOf(127 * scale, -76 * scale, 0f, 32 * scale), Int8Vectors.dequantize(values, scale))
    }

    @Test
    fun aZeroVectorHasScaleZero() {
        val (values, scale) = Int8Vectors.quantize(FloatArray(4))
        assertContentEquals(ByteArray(4), values)
        assertEquals(0f, scale)
    }

    @Test
    fun theQuantizedDotProductStaysCloseToTheCosine() {
        val random = Random(42)
        repeat(100) {
            val a = OnnxEmbedder.normalize(FloatArray(256) { random.nextFloat() - 0.5f })
            val b = OnnxEmbedder.normalize(FloatArray(256) { a[it] + (random.nextFloat() - 0.5f) * random.nextFloat() })
            val exact = a.indices.sumOf { a[it].toDouble() * b[it] }
            val (qa, sa) = Int8Vectors.quantize(a)
            val (qb, sb) = Int8Vectors.quantize(b)
            val approx = Int8Vectors.dot(qa, sa, qb, sb)
            assertTrue(abs(exact - approx) < 0.01, "exact $exact, int8 $approx")
        }
    }

    @Test
    fun quantizeIntoWritesAtTheOffset() {
        val out = ByteArray(6) { 9 }
        val scale = Int8Vectors.quantizeInto(floatArrayOf(1f, -1f), out, 2)
        assertContentEquals(byteArrayOf(9, 9, 127, -127, 9, 9), out)
        assertEquals(1f / 127, scale)
    }
}
