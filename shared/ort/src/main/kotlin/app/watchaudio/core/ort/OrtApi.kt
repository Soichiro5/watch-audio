package app.watchaudio.core.ort

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/** An error returned by ONNX Runtime (its message, no user data). */
class OrtException(message: String) : RuntimeException(message)

/**
 * The few functions of ONNX Runtime's C API (`onnxruntime_c_api.h`) the embedding model needs
 * (#24), called through JNA on the `libonnxruntime.so` the process already has.
 *
 * Why not ONNX Runtime's Java package (`com.microsoft.onnxruntime:onnxruntime-android`): the app
 * already ships ONNX Runtime 1.28.2 inside the sherpa-onnx AAR, and both packages put a
 * `libonnxruntime.so` at the same path. Only one can be packaged, and their JNI libraries require
 * different symbol versions (`OrtGetApiBase@VERS_1.28.2` for sherpa-onnx,
 * `@VERS_1.28.0` for the newest Java package on Maven Central), which Android's and glibc's
 * linkers enforce: whichever copy wins, the other JNI library fails to load. Calling the C API
 * through JNA needs only `OrtGetApiBase` without a version and adds no second runtime (~28 MB per
 * ABI saved); the ASR and the embedding model share one ONNX Runtime.
 *
 * The C API is a table of function pointers ([Fn] gives their positions in `struct OrtApi`, which
 * only ever grows); [API_VERSION] asks for the oldest table that has them all, so any newer
 * runtime serves it. Only 64-bit platforms (`size_t` is passed as a Java long).
 */
class OrtApi private constructor(private val table: Pointer, val runtimeVersion: String) {
    private val functions = HashMap<Fn, Function>()

    /** Opens a session for the model at [modelPath] using [threads] intra-op threads. */
    fun openSession(modelPath: String, threads: Int): OrtSession {
        val options = PointerByReference()
        ensure(call(Fn.CreateSessionOptions, options))
        val opts = options.value
        try {
            ensure(call(Fn.SetIntraOpNumThreads, opts, threads))
            ensure(call(Fn.SetInterOpNumThreads, opts, 1))
            ensure(call(Fn.SetSessionExecutionMode, opts, ORT_SEQUENTIAL))
            ensure(call(Fn.SetSessionGraphOptimizationLevel, opts, ORT_ENABLE_ALL))
            val session = PointerByReference()
            ensure(call(Fn.CreateSession, env, cString(modelPath), opts, session))
            return OrtSession(this, session.value)
        } finally {
            release(Fn.ReleaseSessionOptions, opts)
        }
    }

    /** CPU memory descriptor for tensors over JNA memory (lives as long as the process). */
    internal val cpuMemory: Pointer by lazy {
        val out = PointerByReference()
        ensure(call(Fn.CreateCpuMemoryInfo, ORT_ARENA_ALLOCATOR, ORT_MEM_TYPE_DEFAULT, out))
        out.value
    }

    private val env: Pointer by lazy {
        // Returns the process's existing environment if sherpa-onnx created one (ORT keeps one).
        val out = PointerByReference()
        ensure(call(Fn.CreateEnv, ORT_LOGGING_LEVEL_WARNING, cString("watchaudio-embedding"), out))
        out.value
    }

    /** Calls a status-returning function. @return the OrtStatus pointer (null on success). */
    internal fun call(fn: Fn, vararg args: Any?): Pointer? = function(fn).invokePointer(args)

    /** Calls a `Release*` function (no status) when [handle] is set. */
    internal fun release(fn: Fn, handle: Pointer?) {
        if (handle != null) function(fn).invokeVoid(arrayOf(handle))
    }

    /** Throws [OrtException] with the status's message when [status] is an error. */
    internal fun ensure(status: Pointer?): Boolean {
        if (status == null) return true
        val message = function(Fn.GetErrorMessage).invokeString(arrayOf(status), false)
        release(Fn.ReleaseStatus, status)
        throw OrtException(message ?: "ONNX Runtime error")
    }

    private fun function(fn: Fn): Function = synchronized(functions) {
        functions.getOrPut(fn) { Function.getFunction(table.getPointer(fn.index.toLong() * Native.POINTER_SIZE)) }
    }

    /**
     * Positions in `struct OrtApi` (ONNX Runtime 1.16+), counted from `onnxruntime_c_api.h`; the
     * table only ever grows, so they never change. Checked by [OrtApiTest] against a real runtime.
     */
    internal enum class Fn(val index: Int) {
        GetErrorMessage(2),
        CreateEnv(3),
        CreateSession(7),
        Run(9),
        CreateSessionOptions(10),
        SetSessionExecutionMode(13),
        SetSessionGraphOptimizationLevel(23),
        SetIntraOpNumThreads(24),
        SetInterOpNumThreads(25),
        CreateTensorWithDataAsOrtValue(49),
        GetTensorMutableData(51),
        GetDimensionsCount(61),
        GetDimensions(62),
        GetTensorTypeAndShape(65),
        CreateCpuMemoryInfo(69),
        ReleaseStatus(93),
        ReleaseSession(95),
        ReleaseValue(96),
        ReleaseTensorTypeAndShapeInfo(99),
        ReleaseSessionOptions(100),
    }

    companion object {
        /** The C API version requested: 16 (ONNX Runtime 1.16) has every function in [Fn]. */
        const val API_VERSION = 16

        private const val ORT_LOGGING_LEVEL_WARNING = 2
        private const val ORT_SEQUENTIAL = 0
        private const val ORT_ENABLE_ALL = 99
        private const val ORT_ARENA_ALLOCATOR = 1
        private const val ORT_MEM_TYPE_DEFAULT = 0
        internal const val ELEMENT_FLOAT = 1
        internal const val ELEMENT_INT64 = 7

        private val loaded = HashMap<String, OrtApi>()

        /**
         * The C API of the ONNX Runtime library [library]: a name (`onnxruntime`, as on Android,
         * where sherpa-onnx's copy is in the APK) or an absolute path. Loaded once per process.
         */
        fun load(library: String = "onnxruntime"): OrtApi = synchronized(loaded) {
            loaded.getOrPut(library) {
                check(Native.POINTER_SIZE == 8 && Native.SIZE_T_SIZE == 8) { "64-bit platforms only" }
                val native = NativeLibrary.getInstance(library)
                val base = native.getFunction("OrtGetApiBase").invokePointer(arrayOf())
                    ?: throw OrtException("OrtGetApiBase returned null")
                val version = Function.getFunction(base.getPointer(Native.POINTER_SIZE.toLong())).invokeString(arrayOf(), false)
                val table = Function.getFunction(base.getPointer(0)).invokePointer(arrayOf(API_VERSION))
                    ?: throw OrtException("ONNX Runtime $version does not provide C API version $API_VERSION")
                OrtApi(table, version ?: "unknown")
            }
        }

        /** A NUL-terminated UTF-8 copy of [text] (JNA's own String conversion depends on a property). */
        internal fun cString(text: String): Memory {
            val bytes = text.encodeToByteArray()
            return Memory(bytes.size + 1L).apply {
                write(0, bytes, 0, bytes.size)
                setByte(bytes.size.toLong(), 0)
            }
        }

        internal fun names(names: List<String>): StringArray = StringArray(names.toTypedArray(), "UTF-8")

        internal fun longOut() = LongByReference()
    }
}
