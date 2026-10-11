package app.watchaudio.core.pipeline

/**
 * What the phone's background processing is doing, as the status tab shows it. Produced by the
 * platforms' background work (WorkManager, BGTask) and read by the shared UI through
 * `AppServices.status`.
 */
data class ProcessingStatus(
    val asrModel: ModelState = ModelState.Unknown,
    val transcribing: Boolean = false,
    val lastChunkReceivedAt: Long? = null,
    val message: String? = null,
    /** Why transcription is waiting for the charger or a cooler phone (#75); null while it may run. */
    val paused: PauseReason? = null,
)

/** Whether the on-device speech model can be used. */
sealed interface ModelState {
    /** Not checked yet (or to be checked again, e.g. after chunks failed). */
    data object Unknown : ModelState

    /** Not installed. */
    data object Missing : ModelState

    /** Being installed; [progress] in 0..1. */
    data class Downloading(val progress: Float) : ModelState

    data object Ready : ModelState

    /** Installing failed; [reason] is the platform's description, for the status tab. */
    data class Failed(val reason: String) : ModelState
}
