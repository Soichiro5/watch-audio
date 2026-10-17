package app.watchaudio.android.llm

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Gemini Nano through ML Kit GenAI's Prompt API (#205): the only file of the app that may use ML
 * Kit (`tools/privacy-check`, `SINGLE_FILE_FRAMEWORKS`). Inference runs in AICore on the device; the
 * text it is given never leaves it. ML Kit sends content-free usage logs to Google (device and app
 * information, latency, API settings, identifiers, language; approved by the owner on 2026-10-17).
 *
 * Only the stable release of the model (never `ModelReleaseStage.PREVIEW`: the ML Kit GenAI terms
 * keep "Preview" services out of production), only text, and none of the alpha or experimental
 * parts (structured output, prefix caching). Every ML Kit error becomes a [GeminiNanoException].
 */
class MlKitGeminiNano : GeminiNano {
    // Created on first use: on a phone without AICore only the calls fail, not the app.
    private val client: GenerativeModel by lazy {
        Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = ModelPreference.FULL
                }
            },
        )
    }

    override suspend fun status(): FeatureState = mlKit { featureStateOf(client.checkStatus()) }

    override suspend fun baseModelName(): String = mlKit { client.getBaseModelName() }

    override suspend fun tokenLimit(): Int = mlKit { client.getTokenLimit() }

    override suspend fun countTokens(text: String): Int = mlKit { client.countTokens(generateContentRequest(TextPart(text)) {}).totalTokens }

    override fun download(): Flow<DownloadEvent> = flow { emitAll(client.download()) }
        .map { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> DownloadEvent.Started(status.bytesToDownload)
                is DownloadStatus.DownloadProgress -> DownloadEvent.Progress(status.totalBytesDownloaded)
                is DownloadStatus.DownloadFailed -> DownloadEvent.Failed(errorOf(status.e.errorCode))
                else -> DownloadEvent.Completed
            }
        }
        .catch { e -> if (e is GenAiException) emit(DownloadEvent.Failed(errorOf(e.errorCode))) else throw e }

    override suspend fun generate(text: String, maxOutputTokens: Int): String? = mlKit {
        val request = generateContentRequest(TextPart(text)) {
            // The most likely answer, as on the iPhone (greedy): a summary, not a creative text.
            temperature = 0f
            topK = 1
            candidateCount = 1
            this.maxOutputTokens = maxOutputTokens
        }
        client.generateContent(request).candidates.firstOrNull()?.text
    }

    private inline fun <T> mlKit(block: () -> T): T = try {
        block()
    } catch (e: GenAiException) {
        throw GeminiNanoException(errorOf(e.errorCode), e.errorCode, e.retryDelay?.toMillis())
    }

    companion object {
        /** ML Kit's `FeatureStatus` as [FeatureState]; an unknown value counts as unavailable. */
        fun featureStateOf(status: Int): FeatureState = when (status) {
            FeatureStatus.AVAILABLE -> FeatureState.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> FeatureState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> FeatureState.DOWNLOADING
            else -> FeatureState.UNAVAILABLE
        }

        /** ML Kit's `GenAiException.ErrorCode` as [GeminiError]. */
        fun errorOf(code: Int): GeminiError = when (code) {
            GenAiException.ErrorCode.BUSY -> GeminiError.BUSY
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> GeminiError.QUOTA_EXCEEDED
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> GeminiError.BACKGROUND_USE_BLOCKED
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> GeminiError.REQUEST_TOO_LARGE
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
            -> GeminiError.BLOCKED_BY_POLICY
            GenAiException.ErrorCode.CANCELLED -> GeminiError.CANCELLED
            GenAiException.ErrorCode.NOT_AVAILABLE -> GeminiError.NOT_AVAILABLE
            GenAiException.ErrorCode.NOT_SUPPORTED -> GeminiError.NOT_SUPPORTED
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> GeminiError.NOT_ENOUGH_DISK_SPACE
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> GeminiError.AICORE_INCOMPATIBLE
            GenAiException.ErrorCode.REQUEST_TOO_SMALL -> GeminiError.REQUEST_TOO_SMALL
            else -> GeminiError.OTHER
        }
    }
}
