package app.watchaudio.core.ort

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JNA binding of ONNX Runtime's C API ([OrtApi], #24) against the real runtime sherpa-onnx
 * ships for Linux (1.28.2, the version inside the app's sherpa-onnx AAR): loading, a session, a
 * run with int64 inputs and a float output, and an error coming back as [OrtException]. Every
 * function position in [OrtApi.Fn] is used here, so a wrong index fails (or crashes) this test.
 */
class OrtApiTest {
    private val api = OrtApi.load(sherpaRuntime().absolutePath)
    private val model = System.getProperty("watchaudio.ortTestModel")

    @Test
    fun reportsTheRuntimeVersion() {
        assertEquals("1.28.2", api.runtimeVersion)
    }

    @Test
    fun runsAModelWithInt64InputsAndAFloatOutput() {
        api.openSession(model, threads = 1).use { session ->
            val shape = longArrayOf(2, 3)
            val out = session.run(
                linkedMapOf(
                    "input_ids" to LongTensor(longArrayOf(1, 2, 3, 4, 5, 0), shape),
                    "attention_mask" to LongTensor(longArrayOf(1, 1, 1, 1, 1, 0), shape),
                ),
                "last_hidden_state",
            )
            assertContentEquals(longArrayOf(2, 3, 4), out.shape)
            val expected = listOf(1, 2, 3, 4, 5, 0).flatMap { id -> listOf(1f, 2f, 3f, 4f).map { it * id } }
            assertContentEquals(expected.toFloatArray(), out.values)
        }
    }

    @Test
    fun runsFromSeveralThreads() {
        api.openSession(model, threads = 1).use { session ->
            val results = (1..4).map { n ->
                Thread {
                    repeat(50) {
                        val out = session.run(
                            mapOf("input_ids" to LongTensor(longArrayOf(n.toLong()), longArrayOf(1, 1)), "attention_mask" to LongTensor(longArrayOf(1), longArrayOf(1, 1))),
                            "last_hidden_state",
                        )
                        check(out.values[3] == 4f * n)
                    }
                }.also { it.start() }
            }
            results.forEach { it.join() }
        }
    }

    @Test
    fun errorsBecomeExceptionsWithTheRuntimesMessage() {
        api.openSession(model, threads = 1).use { session ->
            val error = assertFailsWith<OrtException> {
                session.run(mapOf("no_such_input" to LongTensor(longArrayOf(1), longArrayOf(1, 1))), "last_hidden_state")
            }
            assertTrue(error.message!!.contains("no_such_input"), error.message)
        }
        assertFailsWith<OrtException> { api.openSession("/nonexistent/model.onnx", threads = 1) }
    }

    private companion object {
        /** sherpa-onnx's libonnxruntime.so from its native-library jar on the test classpath. */
        fun sherpaRuntime(): File {
            val resource = "sherpa-onnx/native/linux-x64/libonnxruntime.so"
            val dir = Files.createTempDirectory("ort-test").toFile().apply { deleteOnExit() }
            val file = File(dir, "libonnxruntime.so").apply { deleteOnExit() }
            OrtApiTest::class.java.classLoader.getResourceAsStream(resource)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        }
    }
}
