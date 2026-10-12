package app.watchaudio.core.embedding

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-vector symmetric int8 quantization of embeddings (#24, M0-6 §3: no measurable loss in
 * recall): `q[i] = round(v[i] / scale)` with `scale = max|v| / 127`, so `v ≈ q * scale`. The dot
 * product of two quantized vectors is an integer sum times both scales ([dot]), which is what the
 * brute-force search computes for every stored passage.
 */
object Int8Vectors {
    /** The quantized values and scale of [vector]. A zero vector gets scale 0. */
    fun quantize(vector: FloatArray): Pair<ByteArray, Float> {
        val out = ByteArray(vector.size)
        return out to quantizeInto(vector, out, 0)
    }

    /** Writes the quantized [vector] into [out] at [offset]; @return its scale. */
    fun quantizeInto(vector: FloatArray, out: ByteArray, offset: Int): Float {
        var max = 0f
        for (v in vector) max = maxOf(max, abs(v))
        if (max == 0f) {
            out.fill(0, offset, offset + vector.size)
            return 0f
        }
        val scale = max / 127f
        for (i in vector.indices) out[offset + i] = (vector[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
        return scale
    }

    /** The float vector [values] (at [offset], [dimension] values) stands for. */
    fun dequantize(values: ByteArray, scale: Float, offset: Int = 0, dimension: Int = values.size - offset): FloatArray =
        FloatArray(dimension) { values[offset + it] * scale }

    /** Approximate dot product (cosine for unit vectors) of two quantized vectors. */
    fun dot(a: ByteArray, aScale: Float, b: ByteArray, bScale: Float): Float {
        require(a.size == b.size) { "dimensions differ" }
        var sum = 0
        for (i in a.indices) sum += a[i] * b[i]
        return sum * aScale * bScale
    }
}
