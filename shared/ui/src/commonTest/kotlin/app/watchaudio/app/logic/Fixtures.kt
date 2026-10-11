package app.watchaudio.app.logic

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal val TOKYO = TimeZone.of("Asia/Tokyo")
internal val DAY = LocalDate(2026, 10, 10)
internal val WINDOW = dayWindow(DAY, TOKYO)

internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN

/** Epoch ms of [hour]:[minute] on [DAY] in Tokyo. */
internal fun at(hour: Int, minute: Int = 0): Long = WINDOW.start + hour * HOUR + minute * MIN

internal fun chunk(
    id: String,
    start: Long,
    end: Long = start + 5 * MIN,
    audio: Boolean = true,
    endReason: ChunkEndReason = ChunkEndReason.ROTATION,
    state: ChunkState = if (audio) ChunkState.TRANSCRIBED else ChunkState.NO_AUDIO,
    lastError: String? = null,
) = ChunkRecord(
    id = id,
    sessionId = "s",
    deviceId = "watch",
    source = SourcePlatform.WEAROS,
    startedAt = start,
    endedAt = end,
    timeZone = "Asia/Tokyo",
    audioPath = if (audio) "$id.m4a" else null,
    endReason = endReason,
    state = state,
    attempts = 0,
    lastError = lastError,
)

internal fun segment(id: Long, chunkId: String, start: Long, text: String = "text$id") =
    SegmentRecord(id, chunkId, start, start + 3_000, text, "ja", "test")

/** Consecutive 5-minute chunks covering `[from, to)`; the last one ends with [lastReason]. */
internal fun run(prefix: String, from: Long, to: Long, lastReason: ChunkEndReason = ChunkEndReason.ROTATION): List<ChunkRecord> {
    val starts = (from until to step 5 * MIN).toList()
    return starts.mapIndexed { i, start ->
        chunk(
            id = "$prefix$i",
            start = start,
            end = minOf(start + 5 * MIN, to),
            endReason = if (i == starts.lastIndex) lastReason else ChunkEndReason.ROTATION,
        )
    }
}
