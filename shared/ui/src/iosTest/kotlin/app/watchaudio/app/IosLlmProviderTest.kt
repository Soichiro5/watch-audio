package app.watchaudio.app

import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.SummaryPrompts
import app.watchaudio.core.summary.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The Kotlin side of the Foundation Models bridge (#196): Swift's availability and error codes as
 * the shared states, token counts, the schema handed over as JSON, and cancellation reaching the
 * Swift task. The Swift side is checked by `LanguageModelTests` (XCTest).
 */
class IosLlmProviderTest {
    private class SwiftStub(
        override val availability: String = "available",
        private val count: Long = 12,
        private val answer: Pair<String?, String?>? = """{"summary": "要約。"}""" to null,
    ) : IosLanguageModel {
        override val modelVersion = "ios-26.5"
        override val contextSize = 4_096L
        val schemas = mutableListOf<String>()
        var cancelled = 0
        private var pending: ((String?, String?) -> Unit)? = null

        override fun tokenCount(text: String, callback: (Long) -> Unit) = callback(count)

        override fun generate(
            instructions: String,
            prompt: String,
            schemaJson: String,
            maxOutputTokens: Long,
            callback: (String?, String?) -> Unit,
        ): IosLanguageModelTask {
            schemas += schemaJson
            if (answer != null) callback(answer.first, answer.second) else pending = callback
            return object : IosLanguageModelTask {
                override fun cancel() {
                    cancelled++
                    pending?.invoke(null, "cancelled")
                }
            }
        }
    }

    private val request = LlmRequest("指示", "[1] 発話", SummaryPrompts.SCHEMA, 500)

    @Test
    fun availabilityCodesAreTheSharedStates() = runBlocking {
        val expected = mapOf(
            "available" to LlmAvailability.Available("ios-26.5"),
            "device_not_eligible" to LlmAvailability.Unsupported("device_not_eligible"),
            "apple_intelligence_not_enabled" to LlmAvailability.TurnedOff,
            "model_not_ready" to LlmAvailability.Downloading(null),
            "language_not_supported" to LlmAvailability.LanguageNotSupported,
            "unavailable_other" to LlmAvailability.Unsupported("unavailable_other"),
        )
        for ((code, availability) in expected) assertEquals(availability, IosLlmProvider(SwiftStub(availability = code)).availability(), code)
        assertEquals("apple-fm", IosLlmProvider(SwiftStub()).id)
        assertEquals(4_096, IosLlmProvider(SwiftStub()).contextTokens())
    }

    @Test
    fun tokenCountsBelowIos264AreUnknown() = runBlocking {
        assertEquals(12, IosLlmProvider(SwiftStub(count = 12)).countTokens("abc"))
        assertNull(IosLlmProvider(SwiftStub(count = -1)).countTokens("abc"))
    }

    @Test
    fun answersAndErrorsAreTheSharedResults() = runBlocking {
        val stub = SwiftStub()
        assertEquals(LlmResult.Ok("""{"summary": "要約。"}"""), IosLlmProvider(stub).generate(request))
        assertEquals(SummaryPrompts.SCHEMA.toJson(), stub.schemas.single())
        val expected = mapOf(
            "guardrail_violation" to LlmResult.Refused,
            "refusal" to LlmResult.Refused,
            "exceeded_context_window" to LlmResult.ContextExceeded,
            "rate_limited" to LlmResult.RateLimited(null),
            "concurrent_requests" to LlmResult.RateLimited(null),
            "assets_unavailable" to LlmResult.Failed("assets_unavailable"),
        )
        for ((code, result) in expected) {
            assertEquals(result, IosLlmProvider(SwiftStub(answer = null to code)).generate(request), code)
        }
    }

    @Test
    fun cancellingTheCoroutineCancelsTheSwiftTask() = runBlocking {
        val stub = SwiftStub(answer = null)
        val call = async(start = CoroutineStart.UNDISPATCHED) { IosLlmProvider(stub).generate(request) }
        yield()
        call.cancel()
        assertTrue(runCatching { call.await() }.isFailure)
        assertEquals(1, stub.cancelled)
    }
}
