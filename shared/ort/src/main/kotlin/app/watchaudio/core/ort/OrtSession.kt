package app.watchaudio.core.ort

import app.watchaudio.core.ort.OrtApi.Fn
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** A float tensor returned by [OrtSession.run]: [values] in row-major order and its [shape]. */
class FloatTensor(val values: FloatArray, val shape: LongArray)

/** An int64 input tensor for [OrtSession.run]. */
class LongTensor(val values: LongArray, val shape: LongArray) {
    init {
        require(values.size.toLong() == shape.fold(1L) { a, b -> a * b }) { "values do not fill the shape" }
    }
}

/**
 * A loaded model ([OrtApi.openSession]). [run] may be called from several threads (ONNX Runtime
 * sessions are thread-safe); [close] must not overlap a run.
 */
class OrtSession internal constructor(private val api: OrtApi, private var handle: Pointer?) : AutoCloseable {

    /** Runs the model on int64 [inputs] (by input name) and returns the float output [output]. */
    fun run(inputs: Map<String, LongTensor>, output: String): FloatTensor {
        val session = handle ?: throw IllegalStateException("session closed")
        val buffers = ArrayList<Memory>()
        val values = ArrayList<Pointer>()
        val outputs = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, null) }
        try {
            for (tensor in inputs.values) {
                val data = Memory(tensor.values.size * 8L).apply { write(0, tensor.values, 0, tensor.values.size) }
                val shape = Memory(tensor.shape.size * 8L).apply { write(0, tensor.shape, 0, tensor.shape.size) }
                buffers += data
                buffers += shape
                val value = PointerByReference()
                api.ensure(
                    api.call(
                        Fn.CreateTensorWithDataAsOrtValue, api.cpuMemory, data, data.size(), shape, tensor.shape.size.toLong(),
                        OrtApi.ELEMENT_INT64, value,
                    ),
                )
                values += value.value
            }
            val valueArray = Memory(values.size * Native.POINTER_SIZE.toLong())
            values.forEachIndexed { i, v -> valueArray.setPointer(i.toLong() * Native.POINTER_SIZE, v) }
            api.ensure(
                api.call(
                    Fn.Run, session, null, OrtApi.names(inputs.keys.toList()), valueArray, values.size.toLong(),
                    OrtApi.names(listOf(output)), 1L, outputs,
                ),
            )
            val result = outputs.getPointer(0) ?: throw OrtException("no output")
            return readFloatTensor(result)
        } finally {
            outputs.getPointer(0)?.let { api.release(Fn.ReleaseValue, it) }
            values.forEach { api.release(Fn.ReleaseValue, it) }
            buffers.forEach { it.close() }
        }
    }

    private fun readFloatTensor(value: Pointer): FloatTensor {
        val info = PointerByReference()
        api.ensure(api.call(Fn.GetTensorTypeAndShape, value, info))
        val shape = try {
            val count = OrtApi.longOut()
            api.ensure(api.call(Fn.GetDimensionsCount, info.value, count))
            val dims = Memory(maxOf(1L, count.value) * 8)
            api.ensure(api.call(Fn.GetDimensions, info.value, dims, count.value))
            dims.getLongArray(0, count.value.toInt()).also { dims.close() }
        } finally {
            api.release(Fn.ReleaseTensorTypeAndShapeInfo, info.value)
        }
        val size = shape.fold(1L) { a, b -> a * b }
        require(size in 0..Int.MAX_VALUE) { "output too large" }
        val data = PointerByReference()
        api.ensure(api.call(Fn.GetTensorMutableData, value, data))
        return FloatTensor(data.value.getFloatArray(0, size.toInt()), shape)
    }

    override fun close() {
        api.release(Fn.ReleaseSession, handle)
        handle = null
    }
}
