package app.watchaudio.app.logic

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.protocol.ChunkEndReason

/** Whether chunks from the watch are arriving as expected (phone-side indicator, #43). */
sealed interface ArrivalState {
    data object NoData : ArrivalState

    /** The last chunk ended with a regular rotation and arrived recently. */
    data class Receiving(val lastReceivedAt: Long) : ArrivalState

    /** The recorder stopped on purpose or for a known reason; no chunks are expected. */
    data class Stopped(val lastReceivedAt: Long, val reason: ChunkEndReason) : ArrivalState

    /** The recorder should still be running, but nothing arrived for [silentForMs]. */
    data class Late(val lastReceivedAt: Long, val silentForMs: Long) : ArrivalState
}

/**
 * @param lastChunk the newest chunk (by start time).
 * @param lastReceivedAt when the phone last stored a chunk.
 */
fun arrivalState(
    lastChunk: ChunkRecord?,
    lastReceivedAt: Long?,
    now: Long,
    warnAfterMs: Long = Thresholds.ARRIVAL_WARNING_MS,
): ArrivalState {
    if (lastChunk == null || lastReceivedAt == null) return ArrivalState.NoData
    if (lastChunk.endReason.startsGap) return ArrivalState.Stopped(lastReceivedAt, lastChunk.endReason)
    val silentFor = now - lastReceivedAt
    return if (silentFor > warnAfterMs) {
        ArrivalState.Late(lastReceivedAt, silentFor)
    } else {
        ArrivalState.Receiving(lastReceivedAt)
    }
}
