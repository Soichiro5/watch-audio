package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import kotlinx.coroutines.flow.StateFlow

/**
 * What the shared UI needs from each phone platform. Implemented by androidApp
 * (`AndroidAppServices`) and by the iOS host (`IosAppServices` in composeApp/iosMain).
 */
interface AppServices {
    /** The local database (everything stays on the device). */
    val store: LocalStore

    /** Absolute path of the audio archive root; chunk `audioPath`s are relative to it. */
    val audioRoot: String

    val status: StateFlow<ProcessingStatus>

    /** Ask the platform to run transcription soon (e.g. enqueue background work). */
    fun requestTranscription()

    /** Start playback of an archived chunk at [offsetMs]. */
    fun play(audioPath: String, offsetMs: Long)

    fun stopPlayback()
}

data class ProcessingStatus(
    val asrModel: ModelState = ModelState.Unknown,
    val transcribing: Boolean = false,
    val lastChunkReceivedAt: Long? = null,
    val message: String? = null,
)

sealed interface ModelState {
    data object Unknown : ModelState
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}
