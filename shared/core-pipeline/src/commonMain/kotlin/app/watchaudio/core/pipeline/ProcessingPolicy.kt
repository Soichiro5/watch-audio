package app.watchaudio.core.pipeline

/**
 * How warm the phone is, with the platform levels mapped onto one scale:
 * Android `PowerManager.THERMAL_STATUS_*` (NONE, LIGHT, MODERATE, SEVERE, CRITICAL and above) and
 * iOS `ProcessInfo.ThermalState` (nominal → [NONE], fair → [LIGHT], serious → [SEVERE],
 * critical → [CRITICAL]).
 */
enum class ThermalLevel(val code: String) {
    NONE("none"),
    LIGHT("light"),
    MODERATE("moderate"),
    SEVERE("severe"),
    CRITICAL("critical"),
}

/** The phone's power and thermal state at one moment, read by the platform. */
data class DeviceConditions(
    /** On external power (plugged in or on a wireless charger), whether or not the battery is full. */
    val charging: Boolean,
    /** Battery level 0–100, or null if the platform cannot tell. */
    val batteryPercent: Int?,
    /** Android battery saver / iOS Low Power Mode. */
    val powerSaveMode: Boolean,
    val thermal: ThermalLevel,
) {
    /** `key=value` pairs for the diagnostics log (no user data). */
    fun describe(): String =
        "charging=$charging battery=${batteryPercent ?: "unknown"} power_save=$powerSaveMode thermal=${thermal.code}"
}

/**
 * Audio transcribed on battery within the current window ([ProcessingPolicy.Limits.windowMs],
 * starting at [windowStartMs]); the hourly cap is checked against [usedAudioMs].
 */
data class BatteryBudget(val windowStartMs: Long = 0, val usedAudioMs: Long = 0)

/** Why transcription waits; [code] is what the diagnostics log records. */
enum class PauseReason(val code: String) {
    /** The phone is warm (thermal level at or above [ProcessingPolicy.Limits.pauseAtThermal]). */
    THERMAL("thermal"),

    /** The user chose to transcribe only while charging. */
    CHARGING_ONLY("charging_only"),

    /** On battery below [ProcessingPolicy.Limits.minBatteryPercent] (or the level is unknown). */
    LOW_BATTERY("low_battery"),

    /** On battery with battery saver / Low Power Mode on. */
    POWER_SAVE("power_save"),

    /** On battery and this hour's budget ([ProcessingPolicy.Limits.batteryAudioMsPerWindow]) is used up. */
    HOURLY_CAP("hourly_cap"),
}

sealed interface ProcessingDecision {
    /** Transcribe the next chunk. [onBattery]: its audio counts against the hourly budget. */
    data class Run(val onBattery: Boolean) : ProcessingDecision

    /**
     * Stop before the next chunk. [retryAfterMs]: check again after this delay; null means wait
     * for the charger (or the next received chunk, which asks again).
     */
    data class Pause(val reason: PauseReason, val retryAfterMs: Long?) : ProcessingDecision
}

/**
 * When the phone may transcribe (#75). Charging: everything, as fast as possible. On battery: only
 * while the battery is at least [Limits.minBatteryPercent] and battery saver is off, and at most
 * [Limits.batteryAudioMsPerWindow] of audio per [Limits.windowMs], so most of a day's recording
 * waits for the next charge. A warm phone ([Limits.pauseAtThermal] or above) never transcribes,
 * charging or not, and checks again after [Limits.thermalRetryMs].
 *
 * The decision is taken before every chunk, so a run only ever stops between chunks.
 * Pure: the platform reads [DeviceConditions], keeps the [BatteryBudget] and the clock.
 */
class ProcessingPolicy(val limits: Limits = Limits()) {
    data class Limits(
        val minBatteryPercent: Int = 50,
        val batteryAudioMsPerWindow: Long = 10 * 60_000L,
        val windowMs: Long = 60 * 60_000L,
        val pauseAtThermal: ThermalLevel = ThermalLevel.MODERATE,
        val thermalRetryMs: Long = 15 * 60_000L,
    )

    /**
     * @param chargingOnly the user's 「充電中のみ文字起こしする」 setting.
     * @param userRequested the user tapped 「今すぐ文字起こし」: the battery rules are waived for this
     *   run (the thermal rule is not).
     */
    fun decide(
        conditions: DeviceConditions,
        chargingOnly: Boolean,
        budget: BatteryBudget,
        nowMs: Long,
        userRequested: Boolean = false,
    ): ProcessingDecision {
        if (conditions.thermal >= limits.pauseAtThermal) {
            return ProcessingDecision.Pause(PauseReason.THERMAL, limits.thermalRetryMs)
        }
        if (conditions.charging) return ProcessingDecision.Run(onBattery = false)
        if (userRequested) return ProcessingDecision.Run(onBattery = true)
        if (chargingOnly) return ProcessingDecision.Pause(PauseReason.CHARGING_ONLY, null)
        val battery = conditions.batteryPercent
        if (battery == null || battery < limits.minBatteryPercent) return ProcessingDecision.Pause(PauseReason.LOW_BATTERY, null)
        if (conditions.powerSaveMode) return ProcessingDecision.Pause(PauseReason.POWER_SAVE, null)
        val current = current(budget, nowMs)
        if (current.usedAudioMs >= limits.batteryAudioMsPerWindow) {
            return ProcessingDecision.Pause(PauseReason.HOURLY_CAP, current.windowStartMs + limits.windowMs - nowMs)
        }
        return ProcessingDecision.Run(onBattery = true)
    }

    /** [budget] as of [nowMs]: a new, empty window once the old one is over (or the clock went back). */
    fun current(budget: BatteryBudget, nowMs: Long): BatteryBudget =
        if (nowMs < budget.windowStartMs || nowMs - budget.windowStartMs >= limits.windowMs) BatteryBudget(nowMs, 0) else budget

    /** Adds [audioMs] transcribed on battery at [nowMs] to the budget. */
    fun spend(budget: BatteryBudget, audioMs: Long, nowMs: Long): BatteryBudget =
        current(budget, nowMs).let { it.copy(usedAudioMs = it.usedAudioMs + audioMs.coerceAtLeast(0)) }
}
