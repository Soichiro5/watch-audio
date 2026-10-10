package app.watchaudio.app

import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One utterance from the Swift transcriber, times relative to the start of the concatenated
 * parts.
 */
class IosTimedText(val startMs: Long, val endMs: Long, val text: String)

/**
 * The range `[fromMs, toMs)` (ms from the start of the file) of the audio file at [path]; see
 * [IosSpeechTranscriber.transcribe].
 */
class IosAudioPart(val path: String, val fromMs: Long, val toMs: Long)

/**
 * Implemented in Swift (`SpeechAnalyzerTranscriber`, Speech framework / SpeechTranscriber) and
 * handed to [IosAppGraph.start]. Callback-based because Kotlin cannot call Swift `async` APIs.
 *
 * Every method may be called from any thread; each callback must be invoked exactly once.
 */
interface IosSpeechTranscriber {
    /** Stored with each segment, e.g. `apple-speechtranscriber-ja`. */
    val engineId: String

    /** BCP 47 language of the produced text, e.g. `ja`. */
    val language: String

    /**
     * Makes sure the on-device model for the language is installed (downloading it through the
     * OS if needed). Calls back with null when ready, or an error message.
     */
    fun prepareModel(callback: (String?) -> Unit)

    /**
     * Transcribes the concatenation of [parts] as one recording: segments on success, or
     * (null, error message). Usually one whole chunk; at a chunk boundary, the chunk joined with
     * the adjoining audio of its neighbours. Each part must contribute exactly `toMs - fromMs`
     * of audio (padded with silence if its file is shorter), so times map back to the parts.
     */
    fun transcribe(parts: List<IosAudioPart>, callback: (List<IosTimedText>?, String?) -> Unit)

    /** Cancels the running [transcribe]; its callback is still invoked (with an error). */
    fun cancel()
}

class IosTranscriptionException(message: String) : Exception(message)

/** Adapts the Swift transcriber to the shared [Transcriber] used by the pipeline. */
internal class IosTranscriberAdapter(private val delegate: IosSpeechTranscriber) : Transcriber {
    override val engineId: String get() = delegate.engineId

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { delegate.cancel() }
        delegate.transcribe(parts.map { IosAudioPart(it.file.toString(), it.fromMs, it.toMs) }) { segments, error ->
            // Resuming an already-cancelled continuation is a no-op.
            if (segments != null) {
                val language = delegate.language
                cont.resume(segments.map { TimedText(it.startMs, it.endMs, it.text, language) })
            } else {
                cont.resumeWithException(IosTranscriptionException(error ?: "transcription failed"))
            }
        }
    }
}
