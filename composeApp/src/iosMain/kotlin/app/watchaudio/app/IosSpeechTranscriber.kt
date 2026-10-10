package app.watchaudio.app

import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path

/** One utterance from the Swift transcriber, times relative to the start of the file. */
class IosTimedText(val startMs: Long, val endMs: Long, val text: String)

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

    /** Transcribes the audio file at [path]: segments on success, or (null, error message). */
    fun transcribe(path: String, callback: (List<IosTimedText>?, String?) -> Unit)

    /** Cancels the running [transcribe]; its callback is still invoked (with an error). */
    fun cancel()
}

class IosTranscriptionException(message: String) : Exception(message)

/** Adapts the Swift transcriber to the shared [Transcriber] used by the pipeline. */
internal class IosTranscriberAdapter(private val delegate: IosSpeechTranscriber) : Transcriber {
    override val engineId: String get() = delegate.engineId

    override suspend fun transcribe(audioFile: Path): List<TimedText> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { delegate.cancel() }
        delegate.transcribe(audioFile.toString()) { segments, error ->
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
