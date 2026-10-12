package app.watchaudio.wear.recording

import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.RecorderRules

/** What the recorder is doing (or should be doing) right now. */
sealed interface RecorderMode {
    /** The user has not started recording (or tapped Stop). The foreground service is not running. */
    data object Stopped : RecorderMode

    /** Capturing audio into chunks. */
    data object Recording : RecorderMode

    /**
     * Armed but not capturing. The foreground service keeps running so recording can resume on
     * its own: Android 14+ only lets a `microphone` foreground service start from the foreground.
     */
    data class Paused(val reason: PauseReason) : RecorderMode
}

enum class PauseReason(val endReason: ChunkEndReason) {
    CHARGING(ChunkEndReason.CHARGING),
    OFF_BODY(ChunkEndReason.OFF_BODY),
    LOW_BATTERY(ChunkEndReason.LOW_BATTERY),
    LOW_STORAGE(ChunkEndReason.LOW_STORAGE),

    /** The microphone was taken by another client or the recorder failed; retrying with backoff. */
    INTERRUPTED(ChunkEndReason.INTERRUPTION),
}

/** Things worth a notification + haptic: recording stopped for a reason the wearer may not expect. */
enum class RecorderAlert { LOW_BATTERY, LOW_STORAGE, INTERRUPTED }

/**
 * Inputs to [RecorderPolicy]. All times are `SystemClock.elapsedRealtime()` milliseconds so wall
 * clock changes cannot confuse the timers.
 */
data class RecorderConditions(
    /** The user tapped Start and has not tapped Stop. */
    val armed: Boolean = false,
    val charging: Boolean = false,
    val batteryPercent: Int? = null,
    /** When the off-body sensor last reported "off body"; null while worn or when unknown. */
    val offBodySinceMs: Long? = null,
    val freeStorageBytes: Long? = null,
    /** Start of the current interruption; null when the microphone is healthy. */
    val interruptedSinceMs: Long? = null,
    /** Do not try to record again before this time (retry backoff after an interruption). */
    val retryAtMs: Long? = null,
    /** Recorder failures in a row (start/prepare errors), reset once recording is healthy. */
    val consecutiveFailures: Int = 0,
)

data class RecorderDecision(
    val mode: RecorderMode,
    /** Re-run [RecorderPolicy.decide] at this time even if no input changes (timers). */
    val reevaluateAtMs: Long? = null,
    val alert: RecorderAlert? = null,
)

/**
 * Pure state machine deciding between recording / paused / stopped. The service feeds it sensor,
 * battery and recorder events and applies the resulting mode (closing the current chunk with the
 * pause reason's [ChunkEndReason] when it leaves [RecorderMode.Recording]).
 *
 * Priorities when several pause reasons apply: charging > off-body > low battery > low storage >
 * interrupted. Battery and storage use hysteresis so a reading that hovers around the threshold
 * does not create a stream of tiny chunks.
 */
class RecorderPolicy {
    private var lowBattery = false
    private var lowStorage = false

    fun decide(conditions: RecorderConditions, nowMs: Long): RecorderDecision {
        updateLatches(conditions)
        if (!conditions.armed) return RecorderDecision(RecorderMode.Stopped)

        val offBodySince = conditions.offBodySinceMs
        val offBodyConfirmedAt = offBodySince?.plus(OFF_BODY_GRACE_MS)
        val offBody = offBodyConfirmedAt != null && nowMs >= offBodyConfirmedAt

        val reason = when {
            conditions.charging -> PauseReason.CHARGING
            offBody -> PauseReason.OFF_BODY
            lowBattery -> PauseReason.LOW_BATTERY
            lowStorage -> PauseReason.LOW_STORAGE
            conditions.retryAtMs != null && nowMs < conditions.retryAtMs -> PauseReason.INTERRUPTED
            else -> null
        }
        val mode = if (reason == null) RecorderMode.Recording else RecorderMode.Paused(reason)

        val interruptedSince = conditions.interruptedSinceMs
        val interruptionAlertAt = interruptedSince?.plus(INTERRUPTION_ALERT_AFTER_MS)
        val alert = when (reason) {
            PauseReason.LOW_BATTERY -> RecorderAlert.LOW_BATTERY
            PauseReason.LOW_STORAGE -> RecorderAlert.LOW_STORAGE
            PauseReason.CHARGING, PauseReason.OFF_BODY -> null
            // Covers both "paused, waiting for a retry" and "recording again, but failing".
            PauseReason.INTERRUPTED, null -> when {
                interruptedSince == null -> null
                conditions.consecutiveFailures >= FAILURES_BEFORE_ALERT -> RecorderAlert.INTERRUPTED
                interruptionAlertAt != null && nowMs >= interruptionAlertAt -> RecorderAlert.INTERRUPTED
                else -> null
            }
        }

        val timers = listOfNotNull(
            offBodyConfirmedAt?.takeIf { it > nowMs },
            conditions.retryAtMs?.takeIf { it > nowMs },
            interruptionAlertAt?.takeIf { it > nowMs && alert == null },
        )
        return RecorderDecision(mode, reevaluateAtMs = timers.minOrNull(), alert = alert)
    }

    private fun updateLatches(conditions: RecorderConditions) {
        val battery = conditions.batteryPercent
        lowBattery = when {
            conditions.charging || battery == null -> false
            lowBattery -> battery < LOW_BATTERY_RESUME_PERCENT
            else -> battery < LOW_BATTERY_PERCENT
        }
        val free = conditions.freeStorageBytes
        lowStorage = when {
            free == null -> false
            lowStorage -> free < LOW_STORAGE_RESUME_BYTES
            else -> free < LOW_STORAGE_BYTES
        }
    }

    companion object {
        /**
         * Stop below this (not charging) so the last chunk can still be finalized and sent before
         * the watch shuts down. Shared with watchOS through [RecorderRules] (#137).
         */
        val LOW_BATTERY_PERCENT = RecorderRules.CURRENT.lowBatteryStopPercent

        /** A low-battery pause ends at or above this level (or when charging). */
        val LOW_BATTERY_RESUME_PERCENT = RecorderRules.CURRENT.lowBatteryResumePercent
        const val LOW_STORAGE_BYTES = 100L * 1024 * 1024
        const val LOW_STORAGE_RESUME_BYTES = 150L * 1024 * 1024

        /** Taking the watch off briefly (adjusting the strap) should not split the recording. */
        const val OFF_BODY_GRACE_MS = 10_000L

        /** Short interruptions (a notification sound, Assistant) are not worth buzzing the wearer. */
        const val INTERRUPTION_ALERT_AFTER_MS = 2 * 60_000L
        const val FAILURES_BEFORE_ALERT = 3
    }
}

/**
 * Retry schedule after the microphone was lost. Never gives up while the user wants to record:
 * stopping the foreground service would make it impossible to restart without the wearer.
 */
class InterruptionBackoff {
    var sinceMs: Long? = null
        private set
    var retryAtMs: Long? = null
        private set
    var consecutiveFailures: Int = 0
        private set
    private var attempts = 0

    /**
     * @param hardFailure the recorder itself failed (prepare/start/error callback), as opposed to
     *   being silenced by a higher-priority client.
     */
    fun onInterrupted(nowMs: Long, hardFailure: Boolean) {
        if (sinceMs == null) sinceMs = nowMs
        if (hardFailure) consecutiveFailures++
        retryAtMs = nowMs + delayFor(attempts)
        attempts++
    }

    /** Recording has been running without being silenced for a while. */
    fun onHealthy() {
        sinceMs = null
        retryAtMs = null
        consecutiveFailures = 0
        attempts = 0
    }

    fun applyTo(conditions: RecorderConditions) = conditions.copy(
        interruptedSinceMs = sinceMs,
        retryAtMs = retryAtMs,
        consecutiveFailures = consecutiveFailures,
    )

    companion object {
        private val DELAYS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000)

        fun delayFor(attempt: Int): Long = DELAYS_MS[attempt.coerceIn(0, DELAYS_MS.lastIndex)]

        /** Recording counts as healthy again after running unsilenced this long. */
        const val HEALTHY_AFTER_MS = 30_000L

        /**
         * A retry that gets silenced again within this time is discarded instead of being kept as
         * a tiny chunk: the gap that started with the previous INTERRUPTION chunk simply continues.
         */
        const val PROBE_DISCARD_MS = 10_000L
    }
}
