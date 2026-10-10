package app.watchaudio.wear.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-facing snapshot of the recorder, owned by [RecordingService] and the uploader. */
data class RecorderUiState(
    val mode: RecorderMode = RecorderMode.Stopped,
    /** Whether the recording service is alive in this process. */
    val serviceRunning: Boolean = false,
    /** Wall-clock start of the current recording session (for the elapsed timer). */
    val sessionStartedAtEpochMs: Long? = null,
    val marksInSession: Int = 0,
    val pendingUploads: Int = 0,
    val lastAckAtEpochMs: Long? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** null when the device has no off-body sensor or it has not reported yet. */
    val onBody: Boolean? = null,
    val alert: RecorderAlert? = null,
)

object RecorderStore {
    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    fun update(transform: (RecorderUiState) -> RecorderUiState) = _state.update(transform)
}

/** Short machine-readable status, persisted for the tile/complication and written to diagnostics. */
fun RecorderMode.statusKey(): String = when (this) {
    RecorderMode.Recording -> "recording"
    RecorderMode.Stopped -> "stopped"
    is RecorderMode.Paused -> "paused_${reason.name.lowercase()}"
}
