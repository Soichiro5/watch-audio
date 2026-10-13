package app.watchaudio.app

import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmProvider
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.toJson
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What hour summaries (#27) need from Swift on iOS (#196), implemented in
 * `apps/ios/App/Llm/LanguageModelPlatform.swift` over Apple's Foundation Models and handed to
 * [IosAppGraph.start], like [IosSpeechTranscriber]. Only the on-device `SystemLanguageModel` is
 * used: never Private Cloud Compute, another company's model or feedback attachments
 * (`tools/privacy-check` allows `FoundationModels` in one Swift file only). Kotlin keeps the logic
 * (chunking, checking, storing: [app.watchaudio.core.summary.SummaryRun]).
 *
 * Callback-based because Kotlin cannot call Swift `async` APIs; every callback is invoked exactly
 * once, from any thread.
 */
interface IosLanguageModel {
    /**
     * `available`, or why not: `device_not_eligible`, `apple_intelligence_not_enabled`,
     * `model_not_ready`, `language_not_supported` (Japanese is not supported), or another code.
     */
    val availability: String

    /** What decides the model's version: the OS version (e.g. `ios-26.5`). */
    val modelVersion: String

    /** Context size in tokens (4,096 on iOS 26). */
    val contextSize: Long

    /** Calls back with the token count of [text], or -1 where the OS cannot count (before iOS 26.4). */
    fun tokenCount(text: String, callback: (Long) -> Unit)

    /**
     * One stateless generation of JSON shaped by [schemaJson] ([app.watchaudio.core.summary.OutputSchema.toJson]):
     * calls back with (json, null), or (null, error code) — `guardrail_violation`, `refusal`,
     * `exceeded_context_window`, `rate_limited`, `cancelled` or another code without user data.
     */
    fun generate(
        instructions: String,
        prompt: String,
        schemaJson: String,
        maxOutputTokens: Long,
        callback: (String?, String?) -> Unit,
    ): IosLanguageModelTask
}

/** A running [IosLanguageModel.generate]; cancelling still invokes its callback (with `cancelled`). */
interface IosLanguageModelTask {
    fun cancel()
}

/** [LlmProvider] over the Swift [IosLanguageModel] (#196). */
internal class IosLlmProvider(private val platform: IosLanguageModel) : LlmProvider {
    override val id: String = ID

    override suspend fun availability(): LlmAvailability = availabilityOf(platform.availability, platform.modelVersion)

    override suspend fun contextTokens(): Int = platform.contextSize.toInt()

    override suspend fun countTokens(text: String): Int? = suspendCancellableCoroutine { cont ->
        platform.tokenCount(text) { count -> cont.resume(count.takeIf { it >= 0 }?.toInt()) }
    }

    override suspend fun generate(request: LlmRequest): LlmResult = suspendCancellableCoroutine { cont ->
        val task = platform.generate(
            request.instructions,
            request.prompt,
            request.schema.toJson(),
            request.maxOutputTokens.toLong(),
        ) { json, error ->
            // Resuming an already-cancelled continuation is a no-op.
            cont.resume(if (json != null) LlmResult.Ok(json) else resultOf(error ?: "unknown"))
        }
        cont.invokeOnCancellation { task.cancel() }
    }

    companion object {
        /** Stored with every summary (`hour_summary.provider`). */
        const val ID = "apple-fm"

        /** The Swift availability code as the shared states (§4.6 of the design). */
        fun availabilityOf(code: String, modelVersion: String): LlmAvailability = when (code) {
            "available" -> LlmAvailability.Available(modelVersion)
            "apple_intelligence_not_enabled" -> LlmAvailability.TurnedOff
            "model_not_ready" -> LlmAvailability.Downloading(null)
            "language_not_supported" -> LlmAvailability.LanguageNotSupported
            else -> LlmAvailability.Unsupported(code)
        }

        /** A Swift error code as the shared result. */
        fun resultOf(error: String): LlmResult = when (error) {
            "guardrail_violation", "refusal" -> LlmResult.Refused
            "exceeded_context_window" -> LlmResult.ContextExceeded
            "rate_limited", "concurrent_requests" -> LlmResult.RateLimited(null)
            else -> LlmResult.Failed(error)
        }
    }
}
