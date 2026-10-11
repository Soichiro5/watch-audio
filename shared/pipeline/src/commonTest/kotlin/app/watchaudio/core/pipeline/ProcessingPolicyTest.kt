package app.watchaudio.core.pipeline

import app.watchaudio.core.pipeline.ProcessingDecision.Pause
import app.watchaudio.core.pipeline.ProcessingDecision.Run
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingPolicyTest {
    private val policy = ProcessingPolicy()
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 10 * hour

    private fun conditions(
        charging: Boolean = false,
        battery: Int? = 80,
        powerSave: Boolean = false,
        thermal: ThermalLevel = ThermalLevel.NONE,
    ) = DeviceConditions(charging, battery, powerSave, thermal)

    private fun decide(
        conditions: DeviceConditions,
        chargingOnly: Boolean = false,
        budget: BatteryBudget = BatteryBudget(),
        userRequested: Boolean = false,
    ) = policy.decide(conditions, chargingOnly, budget, now, userRequested)

    @Test
    fun defaultsAreTheDocumentedTargets() {
        val limits = ProcessingPolicy.Limits()
        assertEquals(50, limits.minBatteryPercent)
        assertEquals(10 * minute, limits.batteryAudioMsPerWindow)
        assertEquals(hour, limits.windowMs)
        assertEquals(ThermalLevel.MODERATE, limits.pauseAtThermal)
        assertEquals(15 * minute, limits.thermalRetryMs)
    }

    @Test
    fun chargingRunsWithoutLimitsWhateverTheBatteryAndSettings() {
        val exhausted = BatteryBudget(now - minute, 60 * minute)
        assertEquals(Run(onBattery = false), decide(conditions(charging = true, battery = 5, powerSave = true), budget = exhausted))
        assertEquals(Run(onBattery = false), decide(conditions(charging = true, battery = null), chargingOnly = true))
        assertEquals(Run(onBattery = false), decide(conditions(charging = true, thermal = ThermalLevel.LIGHT)))
    }

    @Test
    fun onBatteryRunsOnlyFromTheMinimumLevel() {
        assertEquals(Run(onBattery = true), decide(conditions(battery = 50)))
        assertEquals(Run(onBattery = true), decide(conditions(battery = 100)))
        assertEquals(Pause(PauseReason.LOW_BATTERY, null), decide(conditions(battery = 49)))
        assertEquals(Pause(PauseReason.LOW_BATTERY, null), decide(conditions(battery = 15)))
        // Unknown level: assume the worst.
        assertEquals(Pause(PauseReason.LOW_BATTERY, null), decide(conditions(battery = null)))
    }

    @Test
    fun batterySaverAndTheChargingOnlySettingWaitForTheCharger() {
        assertEquals(Pause(PauseReason.POWER_SAVE, null), decide(conditions(powerSave = true)))
        assertEquals(Pause(PauseReason.CHARGING_ONLY, null), decide(conditions(battery = 100), chargingOnly = true))
    }

    @Test
    fun thermalPausesFromModerateEvenWhileCharging() {
        val retry = 15 * minute
        for (level in listOf(ThermalLevel.MODERATE, ThermalLevel.SEVERE, ThermalLevel.CRITICAL)) {
            assertEquals(Pause(PauseReason.THERMAL, retry), decide(conditions(thermal = level)))
            assertEquals(Pause(PauseReason.THERMAL, retry), decide(conditions(charging = true, thermal = level)))
            assertEquals(Pause(PauseReason.THERMAL, retry), decide(conditions(thermal = level), userRequested = true))
        }
        assertEquals(Run(onBattery = true), decide(conditions(thermal = ThermalLevel.LIGHT)))
    }

    @Test
    fun hourlyCapOnBattery() {
        val windowStart = now - 20 * minute
        assertEquals(Run(onBattery = true), decide(conditions(), budget = BatteryBudget(windowStart, 9 * minute)))
        // Used up: wait until the window is over (40 minutes from now).
        assertEquals(
            Pause(PauseReason.HOURLY_CAP, 40 * minute),
            decide(conditions(), budget = BatteryBudget(windowStart, 10 * minute)),
        )
        // A window that is over no longer counts.
        assertEquals(Run(onBattery = true), decide(conditions(), budget = BatteryBudget(now - hour, 60 * minute)))
        // Nor does one "in the future" (the clock was set back).
        assertEquals(Run(onBattery = true), decide(conditions(), budget = BatteryBudget(now + minute, 60 * minute)))
    }

    @Test
    fun userRequestWaivesTheBatteryRulesButNotTheHeat() {
        val exhausted = BatteryBudget(now - minute, 60 * minute)
        assertEquals(Run(onBattery = true), decide(conditions(battery = 20, powerSave = true), true, exhausted, userRequested = true))
    }

    @Test
    fun spendingOpensAWindowAndAddsUp() {
        val first = policy.spend(BatteryBudget(), 5 * minute, now)
        assertEquals(BatteryBudget(now, 5 * minute), first)
        val second = policy.spend(first, 5 * minute, now + 30 * minute)
        assertEquals(BatteryBudget(now, 10 * minute), second)
        assertEquals(Pause(PauseReason.HOURLY_CAP, 30 * minute), policy.decide(conditions(), false, second, now + 30 * minute))
        // The next window starts empty.
        assertEquals(BatteryBudget(now + hour, 2 * minute), policy.spend(second, 2 * minute, now + hour))
        assertEquals(Run(onBattery = true), policy.decide(conditions(), false, second, now + hour))
    }

    @Test
    fun configurableLimits() {
        val strict = ProcessingPolicy(ProcessingPolicy.Limits(minBatteryPercent = 80, pauseAtThermal = ThermalLevel.LIGHT))
        assertEquals(Pause(PauseReason.LOW_BATTERY, null), strict.decide(conditions(battery = 79), false, BatteryBudget(), now))
        assertEquals(Pause(PauseReason.THERMAL, 15 * minute), strict.decide(conditions(thermal = ThermalLevel.LIGHT), false, BatteryBudget(), now))
    }
}
