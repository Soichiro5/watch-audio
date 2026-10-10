package app.watchaudio.wear.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-facing snapshot of the recorder. Owned by [RecordingService]. */
data class RecorderState(
    val isRecording: Boolean = false,
    val startedAtMillis: Long = 0L,
    val batteryAtStart: Int? = null,
    val batteryNow: Int? = null,
    val chunksClosed: Int = 0,
    val chunksSent: Int = 0,
    val bytesRecorded: Long = 0L,
    val marks: Int = 0,
    val lastEvent: String = "",
) {
    /** Battery drain in %/hour since start, once at least 15 minutes have passed. */
    fun drainPerHour(nowMillis: Long): Double? {
        val start = batteryAtStart ?: return null
        val now = batteryNow ?: return null
        val hours = (nowMillis - startedAtMillis) / 3_600_000.0
        return if (hours < 0.25) null else (start - now) / hours
    }
}

object RecorderStore {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    fun update(transform: (RecorderState) -> RecorderState) = _state.update(transform)
}
