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
    /** Something the user should know about the last background work; null when there is nothing. */
    val notice: StatusNotice? = null,
    /** Why transcription is waiting for the charger or a cooler phone (#75); null while it may run. */
    val paused: PauseReason? = null,
    /**
     * The extra English model (#81) while 「英語も文字起こしする」 is on; null when it is off or the
     * platform does not offer English.
     */
    val englishModel: ModelState? = null,
    /** The English model's download waits for an unmetered network (Android). */
    val englishModelWaitingForWifi: Boolean = false,
)

/**
 * A note for the status tab about the phone's background work. Typed so the wording lives in one
 * place (the shared UI's `Strings`, Japanese) instead of in each platform (#135). Failures carry no
 * exception text: the user sees what happened and what comes next, the diagnostics log has the
 * error type.
 */
sealed interface StatusNotice {
    /** The speech model is missing and its download waits for an unmetered network (Android). */
    data object ModelWaitingForWifi : StatusNotice

    /** A chunk from the watch could not be stored; the watch keeps it and sends it again. */
    data object IngestFailed : StatusNotice

    /** The gap alert could not be scheduled; it is scheduled again when the next chunk arrives. */
    data object GapAlertScheduleFailed : StatusNotice

    /** [count] chunks failed to transcribe in the last run; the day view offers a retry. */
    data class TranscriptionFailed(val count: Int) : StatusNotice

    /** Deleting audio past its retention failed; it is tried again after the next run. */
    data object RetentionFailed : StatusNotice
}

/** Whether the on-device speech model can be used. */
sealed interface ModelState {
    /** Not checked yet (or to be checked again, e.g. after chunks failed). */
    data object Unknown : ModelState

    /** Not installed. */
    data object Missing : ModelState

    /** Being installed; [progress] in 0..1. */
    data class Downloading(val progress: Float) : ModelState

    data object Ready : ModelState

    /**
     * Installing failed. [reason] is the platform's description, for developers (tests, debugging);
     * the UI shows a fixed Japanese text instead because it may be English (#135), and the
     * diagnostics log has `model_download_failed`.
     */
    data class Failed(val reason: String) : ModelState
}
