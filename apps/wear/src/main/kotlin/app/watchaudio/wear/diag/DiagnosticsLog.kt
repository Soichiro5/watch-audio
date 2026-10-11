package app.watchaudio.wear.diag

import java.io.File
import java.time.Instant

/**
 * Small append-only CSV of recorder events (start/stop/pause reasons, chunk closes, uploads, ACKs,
 * battery every few minutes). Stays on the watch; used for the battery measurements in
 * docs/research/m0-3-m0-4-battery-protocol.md and for debugging gaps.
 *
 * Size is capped: when [file] grows past [maxBytes] it is renamed to `<name>.1` (replacing the
 * previous one), so at most ~2 × [maxBytes] are kept.
 */
class DiagnosticsLog(
    val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Snapshot(val batteryPercent: Int?, val charging: Boolean?, val mode: String, val pendingUploads: Int?)

    @Synchronized
    fun append(snapshot: Snapshot, event: String, detail: String = "") {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() >= maxBytes) {
                val previous = File(file.parentFile, "${file.name}.1")
                previous.delete()
                file.renameTo(previous)
            }
            if (!file.exists()) file.writeText(HEADER)
            val line = listOf(
                Instant.ofEpochMilli(clock()).toString(),
                snapshot.batteryPercent?.toString().orEmpty(),
                snapshot.charging?.toString().orEmpty(),
                snapshot.mode,
                snapshot.pendingUploads?.toString().orEmpty(),
                event,
                detail.replace(',', ';').replace('\n', ' '),
            ).joinToString(",")
            file.appendText(line + "\n")
        }
    }

    companion object {
        const val HEADER = "timestamp,battery_pct,charging,mode,pending_uploads,event,detail\n"
        const val DEFAULT_MAX_BYTES = 512L * 1024
    }
}
