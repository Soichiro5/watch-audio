package app.watchaudio.wear.recording

import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.RecorderRules
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class RecorderPolicyTest {
    private val now = 1_000_000L
    private val armed = RecorderConditions(armed = true, batteryPercent = 80, freeStorageBytes = 1L shl 30)

    private fun decide(c: RecorderConditions, policy: RecorderPolicy = RecorderPolicy(), at: Long = now) = policy.decide(c, at)

    @Test
    fun `not armed is stopped and armed records`() {
        assertEquals(RecorderMode.Stopped, decide(armed.copy(armed = false)).mode)
        assertEquals(RecorderMode.Recording, decide(armed).mode)
    }

    @Test
    fun `off body pauses only after the grace period`() {
        val c = armed.copy(offBodySinceMs = now)
        val early = decide(c)
        assertEquals(RecorderMode.Recording, early.mode)
        assertEquals(now + RecorderPolicy.OFF_BODY_GRACE_MS, early.reevaluateAtMs)

        val late = decide(c, at = now + RecorderPolicy.OFF_BODY_GRACE_MS)
        assertEquals(RecorderMode.Paused(PauseReason.OFF_BODY), late.mode)
        assertNull(late.alert, "off-body is expected, no alert")
        assertEquals(ChunkEndReason.OFF_BODY, PauseReason.OFF_BODY.endReason)
    }

    @Test
    fun `charging wins over off body and does not alert`() {
        val d = decide(armed.copy(charging = true, offBodySinceMs = 0), at = now)
        assertEquals(RecorderMode.Paused(PauseReason.CHARGING), d.mode)
        assertNull(d.alert)
    }

    @Test
    fun `unplugging resumes when worn and stays paused when off body`() {
        val policy = RecorderPolicy()
        assertEquals(RecorderMode.Paused(PauseReason.CHARGING), decide(armed.copy(charging = true), policy).mode)
        assertEquals(RecorderMode.Recording, decide(armed.copy(charging = false), policy).mode)
        assertEquals(
            RecorderMode.Paused(PauseReason.OFF_BODY),
            decide(armed.copy(charging = false, offBodySinceMs = 0), policy).mode,
        )
    }

    @Test
    fun `low battery pauses below 10 percent with hysteresis and alerts`() {
        val policy = RecorderPolicy()
        // At the stop threshold itself recording continues; it stops only below it.
        assertEquals(RecorderMode.Recording, decide(armed.copy(batteryPercent = 10), policy).mode)
        val low = decide(armed.copy(batteryPercent = 9), policy)
        assertEquals(RecorderMode.Paused(PauseReason.LOW_BATTERY), low.mode)
        assertEquals(RecorderAlert.LOW_BATTERY, low.alert)
        // Hovering above the stop threshold but below the resume threshold stays paused.
        assertEquals(RecorderMode.Paused(PauseReason.LOW_BATTERY), decide(armed.copy(batteryPercent = 10), policy).mode)
        assertEquals(RecorderMode.Paused(PauseReason.LOW_BATTERY), decide(armed.copy(batteryPercent = 14), policy).mode)
        assertEquals(RecorderMode.Recording, decide(armed.copy(batteryPercent = 15), policy).mode)
        assertEquals(RecorderMode.Recording, decide(armed.copy(batteryPercent = 12), policy).mode)
    }

    @Test
    fun `battery thresholds are the shared recorder rules`() {
        // Same values as the watchOS RecorderPolicy (shared/protocol RecorderRules, #137).
        assertEquals(10, RecorderPolicy.LOW_BATTERY_PERCENT)
        assertEquals(15, RecorderPolicy.LOW_BATTERY_RESUME_PERCENT)
        assertEquals(RecorderRules.CURRENT.lowBatteryStopPercent, RecorderPolicy.LOW_BATTERY_PERCENT)
        assertEquals(RecorderRules.CURRENT.lowBatteryResumePercent, RecorderPolicy.LOW_BATTERY_RESUME_PERCENT)
    }

    @Test
    fun `charging clears low battery`() {
        val policy = RecorderPolicy()
        decide(armed.copy(batteryPercent = 3), policy)
        assertEquals(RecorderMode.Paused(PauseReason.CHARGING), decide(armed.copy(batteryPercent = 3, charging = true), policy).mode)
        // Unplugged at 12%: below the resume threshold but above the stop threshold.
        assertEquals(RecorderMode.Recording, decide(armed.copy(batteryPercent = 12), policy).mode)
    }

    @Test
    fun `low storage pauses with hysteresis`() {
        val policy = RecorderPolicy()
        val mb = 1024L * 1024
        assertEquals(RecorderMode.Paused(PauseReason.LOW_STORAGE), decide(armed.copy(freeStorageBytes = 50 * mb), policy).mode)
        assertEquals(RecorderMode.Paused(PauseReason.LOW_STORAGE), decide(armed.copy(freeStorageBytes = 120 * mb), policy).mode)
        assertEquals(RecorderMode.Recording, decide(armed.copy(freeStorageBytes = 200 * mb), policy).mode)
    }

    @Test
    fun `interruption pauses until the retry time`() {
        val backoff = InterruptionBackoff()
        backoff.onInterrupted(now, hardFailure = false)
        val c = backoff.applyTo(armed)
        val d = decide(c)
        assertEquals(RecorderMode.Paused(PauseReason.INTERRUPTED), d.mode)
        assertEquals(now + InterruptionBackoff.delayFor(0), d.reevaluateAtMs)
        assertNull(d.alert, "short interruptions do not alert")

        assertEquals(RecorderMode.Recording, decide(c, at = now + InterruptionBackoff.delayFor(0)).mode)
    }

    @Test
    fun `long interruption alerts`() {
        val backoff = InterruptionBackoff()
        backoff.onInterrupted(now, hardFailure = false)
        val c = backoff.applyTo(armed)
        assertEquals(now + InterruptionBackoff.delayFor(0), decide(c).reevaluateAtMs)
        val later = decide(c, at = now + RecorderPolicy.INTERRUPTION_ALERT_AFTER_MS)
        assertEquals(RecorderAlert.INTERRUPTED, later.alert)
    }

    @Test
    fun `repeated hard failures alert right away`() {
        val backoff = InterruptionBackoff()
        repeat(RecorderPolicy.FAILURES_BEFORE_ALERT) { backoff.onInterrupted(now, hardFailure = true) }
        assertEquals(RecorderAlert.INTERRUPTED, decide(backoff.applyTo(armed)).alert)
    }

    @Test
    fun `backoff grows, caps and resets when healthy`() {
        val backoff = InterruptionBackoff()
        val delays = (0 until 8).map {
            backoff.onInterrupted(now, hardFailure = false)
            backoff.retryAtMs!! - now
        }
        assertEquals(listOf(5_000L, 15_000, 30_000, 60_000, 120_000, 300_000, 300_000, 300_000), delays)
        assertEquals(now, backoff.sinceMs)
        backoff.onHealthy()
        assertNull(backoff.sinceMs)
        assertNull(backoff.retryAtMs)
        backoff.onInterrupted(now, hardFailure = false)
        assertEquals(5_000L, backoff.retryAtMs!! - now)
    }

    @Test
    fun `pause reasons map to chunk end reasons that start a gap`() {
        PauseReason.entries.forEach { assert(it.endReason.startsGap) { "$it" } }
        assertEquals(ChunkEndReason.INTERRUPTION, PauseReason.INTERRUPTED.endReason)
    }
}
