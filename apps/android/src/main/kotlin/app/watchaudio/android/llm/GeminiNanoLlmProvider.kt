package app.watchaudio.android.llm

import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmProvider
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.toSingleText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What hour summaries need from Gemini Nano (ML Kit GenAI's Prompt API, #205), so that
 * [GeminiNanoLlmProvider] can be tested without the platform. Implemented only by [MlKitGeminiNano],
 * the one file of the app that may use ML Kit (`tools/privacy-check`, `SINGLE_FILE_FRAMEWORKS`).
 * ML Kit's errors arrive as [GeminiNanoException].
 */
interface GeminiNano {
    /** AICore's feature status (`checkStatus`). */
    suspend fun status(): FeatureState

    /** The Gemini Nano version on the device (`getBaseModelName`, e.g. `nano-v3`). */
    suspend fun baseModelName(): String

    /** How many tokens a request may have (`getTokenLimit`). */
    suspend fun tokenLimit(): Int

    /** Tokens of [text] as one request (`countTokens`). */
    suspend fun countTokens(text: String): Int

    /** Asks AICore to download the model (`download`); ends after [DownloadEvent.Completed] or [DownloadEvent.Failed]. */
    fun download(): Flow<DownloadEvent>

    /** One stateless generation from [text]: the first candidate's text, or null if there is none. */
    suspend fun generate(text: String, maxOutputTokens: Int): String?
}

/** ML Kit's `FeatureStatus`. */
enum class FeatureState { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** ML Kit's `DownloadStatus`. */
sealed interface DownloadEvent {
    data class Started(val bytesToDownload: Long) : DownloadEvent

    data class Progress(val bytesDownloaded: Long) : DownloadEvent

    data object Completed : DownloadEvent

    data class Failed(val error: GeminiError) : DownloadEvent
}

/** The kinds of ML Kit's `GenAiException.ErrorCode` that hour summaries tell apart. */
enum class GeminiError {
    /** Too many requests in a short time (or a disallowed background use): retry later. */
    BUSY,

    /** A long-duration (e.g. daily) quota of the app is used up (`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`). */
    QUOTA_EXCEEDED,

    /** The app is not the top foreground app; ML Kit generates only then. */
    BACKGROUND_USE_BLOCKED,

    /** The request does not fit the model (`REQUEST_TOO_LARGE`). */
    REQUEST_TOO_LARGE,

    /** The request or the answer did not pass Google's safety policy checks (a refusal). */
    BLOCKED_BY_POLICY,
    CANCELLED,
    NOT_AVAILABLE,
    NOT_SUPPORTED,
    NOT_ENOUGH_DISK_SPACE,

    /** AICore is missing or too old (`AICORE_INCOMPATIBLE`), or Android is too old (`NEEDS_SYSTEM_UPDATE`). */
    AICORE_INCOMPATIBLE,
    REQUEST_TOO_SMALL,
    OTHER,
    ;

    /** The short code of the shared results and the diagnostics log (no user data). */
    val code: String get() = "mlkit_" + name.lowercase()
}

/** An ML Kit GenAI error: its kind, ML Kit's numeric code and the retry delay it suggested. Never carries ML Kit's message. */
class GeminiNanoException(val error: GeminiError, val mlKitCode: Int, val retryAfterMs: Long? = null) :
    Exception("ML Kit GenAI error $mlKitCode") {
    /** [GeminiError.code], with ML Kit's number for an error without a kind of its own. */
    val code: String get() = if (error == GeminiError.OTHER) "mlkit_$mlKitCode" else error.code
}

/**
 * Generation was refused because the app left the foreground. Stops the summary run the way
 * closing the screen does (the hours done so far stay; nothing is stored as failed): the day
 * screen already stops on `ON_STOP`, this covers the moment in between.
 */
class LeftForegroundException : CancellationException("ML Kit GenAI generates only while the app is in the foreground")

/**
 * Hour summaries on Android with Gemini Nano (#205, docs/research/on-device-llm.md §8 decision 3
 * (A)): ML Kit's Prompt API runs the model in AICore on the device, so transcripts and summaries
 * never leave it. ML Kit itself sends content-free usage logs to Google (approved by the owner on
 * 2026-10-17; docs/platforms/android-phone.md).
 *
 * - Availability is AICore's status: no Gemini Nano (most phones, every emulator) is
 *   [LlmAvailability.Unsupported] like an iPhone without Apple Intelligence; a model AICore can
 *   download is [LlmAvailability.NeedsDownload] and is downloaded by [prepare] only after the
 *   user's 「この日を要約」. Statuses are cached ([STATUS_TTL_MS]; for the process when unsupported),
 *   since the day screen asks on every refresh and each call is a request to AICore.
 * - The Prompt API has no guided generation outside its alpha, compile-time structured output, so
 *   the request goes as one text with the shape written out ([toSingleText]) and the shared code
 *   checks the JSON (and asks once more when it is not of the shape). No system instruction either:
 *   it needs Gemini Nano v3 and the one text works on every version.
 * - Japanese is not in the Prompt API's documented languages (2026-10-17): it is used as is, and its
 *   quality is measured on a device (#61) and in the evaluation (#197).
 */
class GeminiNanoLlmProvider(
    private val model: GeminiNano,
    private val diagnostics: Diagnostics = Diagnostics.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmProvider {
    override val id: String = ID

    private class Cached(val availability: LlmAvailability, val at: Long)

    @Volatile private var cached: Cached? = null

    @Volatile private var downloaded: Float? = null

    @Volatile private var limit: Int? = null

    override suspend fun availability(): LlmAvailability {
        cached?.let { c ->
            val ttl = when (c.availability) {
                is LlmAvailability.Unsupported -> Long.MAX_VALUE
                is LlmAvailability.Downloading -> DOWNLOADING_TTL_MS
                else -> STATUS_TTL_MS
            }
            if (clock() - c.at in 0..<ttl) return c.availability
        }
        return check().also { cached = Cached(it, clock()) }
    }

    private suspend fun check(): LlmAvailability {
        val status = try {
            withTimeoutOrNull(STATUS_TIMEOUT_MS) { model.status() } ?: return LlmAvailability.Unsupported("status_timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiNanoException) {
            return LlmAvailability.Unsupported(e.code)
        } catch (e: Exception) {
            // Without AICore the binding itself can fail in other ways; never the message.
            return LlmAvailability.Unsupported("status_" + (e::class.simpleName ?: "exception"))
        }
        return when (status) {
            FeatureState.AVAILABLE -> LlmAvailability.Available(modelVersion())
            FeatureState.DOWNLOADABLE -> LlmAvailability.NeedsDownload(null)
            FeatureState.DOWNLOADING -> LlmAvailability.Downloading(downloaded)
            FeatureState.UNAVAILABLE -> LlmAvailability.Unsupported(UNAVAILABLE)
        }
    }

    private suspend fun modelVersion(): String? = try {
        model.baseModelName().takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * The request limit AICore reports, or the documented one ([DOCUMENTED_TOKEN_LIMIT]). The shared
     * budget also subtracts the output from it, which is on the safe side if the limit counts the
     * input only.
     */
    override suspend fun contextTokens(): Int = limit ?: try {
        model.tokenLimit().takeIf { it > 0 } ?: DOCUMENTED_TOKEN_LIMIT
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DOCUMENTED_TOKEN_LIMIT
    }.also { limit = it }

    override suspend fun countTokens(text: String): Int? = try {
        model.countTokens(text).takeIf { it >= 0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun generate(request: LlmRequest): LlmResult = try {
        val answer = model.generate(request.toSingleText(), request.maxOutputTokens)
        if (answer.isNullOrBlank()) LlmResult.Failed("no_output") else LlmResult.Ok(answer)
    } catch (e: CancellationException) {
        throw e
    } catch (e: GeminiNanoException) {
        if (e.error == GeminiError.BACKGROUND_USE_BLOCKED) throw LeftForegroundException()
        resultOf(e)
    } catch (e: Exception) {
        LlmResult.Failed("mlkit_" + (e::class.simpleName ?: "exception"))
    }

    /** Downloads the model through AICore (the OS's connection, not the app's), after the user's tap only. */
    override suspend fun prepare(onProgress: (Float?) -> Unit): LlmAvailability {
        val before = availability()
        if (before !is LlmAvailability.NeedsDownload) return before
        var total: Long? = null
        fun report(fraction: Float?) {
            downloaded = fraction
            onProgress(fraction)
        }
        try {
            report(null)
            model.download()
                .transformWhile { event ->
                    emit(event)
                    event !is DownloadEvent.Completed && event !is DownloadEvent.Failed
                }
                .collect { event ->
                    when (event) {
                        is DownloadEvent.Started -> total = event.bytesToDownload.takeIf { it > 0 }
                        is DownloadEvent.Progress -> report(total?.let { (event.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) })
                        DownloadEvent.Completed -> report(1f)
                        is DownloadEvent.Failed -> diagnostics.log(DiagnosticEvents.SUMMARY_FAILED, "type=download error=${event.error.code}")
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiNanoException) {
            diagnostics.log(DiagnosticEvents.SUMMARY_FAILED, "type=download error=${e.code}")
        } catch (e: Exception) {
            diagnostics.log(DiagnosticEvents.SUMMARY_FAILED, "type=download error=${e::class.simpleName}")
        } finally {
            downloaded = null
            cached = null
        }
        return availability()
    }

    companion object {
        /** Stored with every summary (`hour_summary.provider`). */
        const val ID = "gemini-nano"

        /** The reason when AICore says the device cannot run Gemini Nano. */
        const val UNAVAILABLE = "gemini_nano_unavailable"

        /** "Input must be under 4000 tokens" (Prompt API, 2026-09-08). */
        const val DOCUMENTED_TOKEN_LIMIT = 4_000

        const val STATUS_TTL_MS = 60_000L
        const val DOWNLOADING_TTL_MS = 10_000L

        /** A status call that does not come back (AICore not answering the binding) counts as unsupported. */
        const val STATUS_TIMEOUT_MS = 10_000L

        /** An ML Kit error as the shared result (not [GeminiError.BACKGROUND_USE_BLOCKED], which stops the run). */
        fun resultOf(e: GeminiNanoException): LlmResult = when (e.error) {
            GeminiError.BUSY, GeminiError.QUOTA_EXCEEDED -> LlmResult.RateLimited(e.retryAfterMs)
            GeminiError.REQUEST_TOO_LARGE -> LlmResult.ContextExceeded
            GeminiError.BLOCKED_BY_POLICY -> LlmResult.Refused
            else -> LlmResult.Failed(e.code)
        }
    }
}
