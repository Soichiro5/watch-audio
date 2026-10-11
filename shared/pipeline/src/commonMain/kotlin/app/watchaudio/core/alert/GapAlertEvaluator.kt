package app.watchaudio.core.alert

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.settings.AppSettings
import app.watchaudio.core.settings.updateSettings
import kotlinx.datetime.TimeZone

/**
 * Applies the gap alert rules (#43) to what is in the [store]: the newest chunk's end reason,
 * when a chunk last arrived and the user's settings. Platforms only deliver the notification:
 * Android checks [decide] from WorkManager, iOS schedules a local notification at [alertAt].
 * The notification's texts are UI strings (`GapAlertText` in `shared/ui`).
 */
class GapAlertEvaluator(
    private val store: LocalStore,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /** What a check at [now] should do. */
    suspend fun decide(now: Long): GapAlertDecision {
        val snapshot = snapshot()
        return gapAlertDecision(
            snapshot.lastEndReason,
            snapshot.lastReceivedAt,
            now,
            snapshot.settings.gapAlert,
            timeZone(),
            alertedFor = snapshot.settings.gapAlertShownFor,
        )
    }

    /** When the alert for the current state is due (epoch ms), or null if none is expected. */
    suspend fun alertAt(): Long? {
        val snapshot = snapshot()
        return gapAlertAt(snapshot.lastEndReason, snapshot.lastReceivedAt, snapshot.settings.gapAlert, timeZone())
    }

    /** The newest arrival time, for the notification text of a scheduled alert. */
    suspend fun lastReceivedAt(): Long? = store.read { lastReceivedAt() }

    /** Remembers that the alert for the gap after [lastReceivedAt] was shown. */
    suspend fun markShown(lastReceivedAt: Long) {
        store.updateSettings { it.copy(gapAlertShownFor = lastReceivedAt) }
    }

    private suspend fun snapshot(): Snapshot = store.read {
        Snapshot(lastChunkBefore()?.endReason, lastReceivedAt(), AppSettings.fromEntries(settings()))
    }

    private data class Snapshot(
        val lastEndReason: ChunkEndReason?,
        val lastReceivedAt: Long?,
        val settings: AppSettings,
    )
}
