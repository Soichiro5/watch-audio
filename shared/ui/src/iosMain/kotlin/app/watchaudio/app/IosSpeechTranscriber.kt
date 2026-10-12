package app.watchaudio.app

import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.LanguageRouting
import app.watchaudio.core.pipeline.SegmentRouting
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One utterance from the Swift transcriber, times relative to the start of the concatenated
 * parts, with the model's [confidence] in it (0..1, negative when not reported).
 */
class IosTimedText(val startMs: Long, val endMs: Long, val text: String, val confidence: Double)

/**
 * The range `[fromMs, toMs)` (ms from the start of the file) of the audio file at [path]; see
 * [IosSpeechTranscriber.transcribe].
 */
class IosAudioPart(val path: String, val fromMs: Long, val toMs: Long)

/**
 * Implemented in Swift (`SpeechAnalyzerTranscriber`, Speech framework / SpeechTranscriber) and
 * handed to [IosAppGraph.start]. Callback-based because Kotlin cannot call Swift `async` APIs.
 * One SpeechTranscriber recognizes one language: [language] (Japanese) always, English
 * ([IosTranscriberAdapter.ENGLISH]) for the stretches [SegmentRouting] picks while
 * 「英語も文字起こしする」 is on (#170).
 *
 * Every method may be called from any thread; each callback must be invoked exactly once.
 */
interface IosSpeechTranscriber {
    /** BCP 47 language of the main model, e.g. `ja`. */
    val language: String

    /** Stored with each segment the model of [language] wrote, e.g. `apple-speechtranscriber-ja`. */
    fun engineId(language: String): String

    /**
     * Makes sure the on-device model for [language] is installed and reserved for this app
     * (downloading it through the OS if needed). Calls back with null when ready, or an error message.
     */
    fun prepareModel(language: String, callback: (String?) -> Unit)

    /**
     * Gives up this app's reservation of [language]'s model, so the OS may remove it (English
     * turned off). Calls back when done; nothing to release is not an error.
     */
    fun releaseModel(language: String, callback: () -> Unit)

    /**
     * Transcribes the concatenation of [parts] in [language] as one recording: segments on
     * success, or (null, error message). Usually one whole chunk; at a chunk boundary, the chunk
     * joined with the adjoining audio of its neighbours; for English, the stretches
     * [SegmentRouting] picked, with silence between them. Each part must contribute exactly
     * `toMs - fromMs` of audio (padded with silence if its file is shorter), so times map back to
     * the parts.
     */
    fun transcribe(parts: List<IosAudioPart>, language: String, callback: (List<IosTimedText>?, String?) -> Unit)

    /** Cancels the running [transcribe]; its callback is still invoked (with an error). */
    fun cancel()
}

class IosTranscriptionException(message: String) : Exception(message)

/**
 * Adapts the Swift transcriber to the shared [Transcriber] used by the pipeline. While [english]
 * (「英語も文字起こしする」 is on and the English model is ready, #170), the Japanese pass is followed
 * by an English pass over the stretches [routing] picks, and [engineId] names both models; each
 * segment states the language of the model that wrote it. If the English pass fails, the chunk
 * keeps the Japanese text ([onEnglishFailed] is told) instead of failing.
 */
internal class IosTranscriberAdapter(
    private val delegate: IosSpeechTranscriber,
    private val english: suspend () -> Boolean = { false },
    private val routing: SegmentRouting = ROUTING,
    private val onEnglishFailed: (Exception) -> Unit = {},
) : Transcriber {
    override var engineId: String = delegate.engineId(delegate.language)
        private set

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
        val primary = delegate.language
        val japaneseOnly = delegate.engineId(primary)
        if (!english()) {
            engineId = japaneseOnly
            return pass(parts, primary)
        }
        engineId = LanguageRouting.engineId(japaneseOnly, delegate.engineId(ENGLISH))
        return routing.transcribe(parts, { pass(it, primary) }) { stretches ->
            try {
                pass(stretches, ENGLISH)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                engineId = japaneseOnly
                onEnglishFailed(e)
                emptyList()
            }
        }
    }

    private suspend fun pass(parts: List<AudioPart>, language: String): List<TimedText> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { delegate.cancel() }
        delegate.transcribe(parts.map { IosAudioPart(it.file.toString(), it.fromMs, it.toMs) }, language) { segments, error ->
            // Resuming an already-cancelled continuation is a no-op.
            if (segments != null) {
                cont.resume(segments.map { it.toTimedText(language) })
            } else {
                cont.resumeWithException(IosTranscriptionException(error ?: "transcription failed"))
            }
        }
    }

    companion object {
        /** The second language (#170). */
        const val ENGLISH = "en"

        /**
         * The routing on iOS: [LanguageRouting.DEFAULT]'s rules, with the English model's
         * confidence standing in for SenseVoice's language tag (an English SpeechTranscriber
         * writes English words for any speech).
         */
        val ROUTING = SegmentRouting(LanguageRouting(minConfidence = 0.5f))
    }
}

private fun IosTimedText.toTimedText(language: String) =
    TimedText(startMs, endMs, text, language, confidence = confidence.takeIf { it >= 0 }?.toFloat())
