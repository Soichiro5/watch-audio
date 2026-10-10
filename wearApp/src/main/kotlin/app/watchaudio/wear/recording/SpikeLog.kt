package app.watchaudio.wear.recording

import java.io.File
import java.time.Instant

/**
 * Append-only CSV used by the M0-4 battery spike (#10).
 *
 * Pull it with `adb pull /sdcard/Android/data/app.watchaudio/files/spike`.
 */
class SpikeLog(dir: File, sessionId: String) {
    val file: File = File(dir, "log-$sessionId.csv").also {
        if (!it.exists()) it.writeText("timestamp,elapsed_min,battery_pct,charging,chunks_closed,chunks_sent,bytes,event,detail\n")
    }

    fun append(state: RecorderState, charging: Boolean, event: String, detail: String = "") {
        val elapsedMin = if (state.startedAtMillis == 0L) 0.0 else (System.currentTimeMillis() - state.startedAtMillis) / 60_000.0
        val line = listOf(
            Instant.now().toString(),
            "%.1f".format(elapsedMin),
            state.batteryNow?.toString().orEmpty(),
            charging.toString(),
            state.chunksClosed.toString(),
            state.chunksSent.toString(),
            state.bytesRecorded.toString(),
            event,
            detail.replace(',', ';'),
        ).joinToString(",")
        file.appendText(line + "\n")
    }
}
