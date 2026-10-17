package app.watchaudio.core.summary

/**
 * A language model the app may use (#27, docs/research/on-device-llm.md §4.1); for now only
 * on-device ones: Apple's Foundation Models on the iPhone (#196) and Gemini Nano through ML Kit on
 * Android phones that have it (#205). A request carries text only
 * (instructions, transcript lines, times and marks): audio cannot be handed over by type, and an
 * on-device provider must not send anything off the device.
 *
 * Platforms without one use [UnsupportedLlmProvider], which the UI shows as 「使えません」.
 */
interface LlmProvider {
    /** Stable id stored with every result, e.g. `apple-fm`. */
    val id: String

    suspend fun availability(): LlmAvailability

    /** Context size in tokens (instructions + prompt + output schema + output), from the platform when it can tell. */
    suspend fun contextTokens(): Int

    /** Token count of [text], or null where the platform cannot count (then 1 character = 1 token). */
    suspend fun countTokens(text: String): Int?

    /**
     * One stateless generation; [LlmRequest.schema] asks for JSON of that shape. Never throws for
     * model errors; a [kotlin.coroutines.cancellation.CancellationException] means the run must stop
     * as if the screen had closed (e.g. Android refuses generation once the app left the foreground).
     */
    suspend fun generate(request: LlmRequest): LlmResult

    /**
     * Gets the model ready when [availability] is [LlmAvailability.NeedsDownload]: called only after
     * the user asked for a summary (「この日を要約」), never on its own. [onProgress] gets the
     * fraction done when the platform tells. Returns the availability afterwards. The default (a
     * provider without a download of its own) changes nothing.
     */
    suspend fun prepare(onProgress: (Float?) -> Unit = {}): LlmAvailability = availability()
}

/** Whether an [LlmProvider] can generate now, and if not, what the user can do about it. */
sealed interface LlmAvailability {
    data class Available(val modelVersion: String?) : LlmAvailability

    /**
     * The model must be downloaded first ([bytes] large, null when the platform does not say before
     * it starts); [LlmProvider.prepare] does it once the user asks for a summary.
     */
    data class NeedsDownload(val bytes: Long?) : LlmAvailability

    /** The model is being downloaded or prepared (iOS `modelNotReady`, Android AICore `DOWNLOADING`); [fraction] if known. */
    data class Downloading(val fraction: Float?) : LlmAvailability

    /** The user turned the platform's AI off (Apple Intelligence). */
    data object TurnedOff : LlmAvailability

    /** The model does not support Japanese (in the user's current settings). */
    data object LanguageNotSupported : LlmAvailability

    /** This device cannot run it at all; [reason] is a short code for diagnostics. */
    data class Unsupported(val reason: String) : LlmAvailability
}

/**
 * One generation: [instructions] (the system part), [prompt] (the transcript lines), the shape of
 * the answer and the most tokens it may take.
 */
data class LlmRequest(
    val instructions: String,
    val prompt: String,
    val schema: OutputSchema,
    val maxOutputTokens: Int,
)

/** What [LlmProvider.generate] returned. */
sealed interface LlmResult {
    /** The answer as JSON of the requested [OutputSchema] (still to be checked). */
    data class Ok(val json: String) : LlmResult

    /** The model declined the content (a guardrail); retrying does not help. */
    data object Refused : LlmResult

    /** Instructions, prompt, schema and output did not fit the context. */
    data object ContextExceeded : LlmResult

    /** The system is busy; try again later ([retryAfterMs] if the platform says). */
    data class RateLimited(val retryAfterMs: Long?) : LlmResult

    /** Anything else; [type] is a short code without user data (diagnostics, `hour_summary.last_error`). */
    data class Failed(val type: String) : LlmResult
}

/**
 * The provider of a platform without an on-device LLM (the default of `AppServices`, test fakes):
 * always [LlmAvailability.Unsupported], so the UI hides the summarize button behind the same state
 * as an iPhone without Apple Intelligence or an Android phone without Gemini Nano.
 */
class UnsupportedLlmProvider(private val reason: String = "no_on_device_llm") : LlmProvider {
    override val id: String = "none"

    override suspend fun availability(): LlmAvailability = LlmAvailability.Unsupported(reason)

    override suspend fun contextTokens(): Int = 0

    override suspend fun countTokens(text: String): Int? = null

    override suspend fun generate(request: LlmRequest): LlmResult = LlmResult.Failed("unsupported")
}
