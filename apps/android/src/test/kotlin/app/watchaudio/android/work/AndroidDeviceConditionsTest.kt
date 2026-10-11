package app.watchaudio.android.work

import android.os.PowerManager
import app.watchaudio.core.pipeline.ThermalLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidDeviceConditionsTest {
    @Test
    fun thermalStatusMapsOntoTheSharedScale() {
        val expected = mapOf(
            PowerManager.THERMAL_STATUS_NONE to ThermalLevel.NONE,
            PowerManager.THERMAL_STATUS_LIGHT to ThermalLevel.LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE to ThermalLevel.MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE to ThermalLevel.SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL to ThermalLevel.CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY to ThermalLevel.CRITICAL,
            PowerManager.THERMAL_STATUS_SHUTDOWN to ThermalLevel.CRITICAL,
        )
        for ((status, level) in expected) assertEquals(level, AndroidDeviceConditions.thermalLevel(status), "status $status")
    }

    @Test
    fun batteryPercentFromLevelAndScale() {
        assertEquals(50, AndroidDeviceConditions.batteryPercent(50, 100))
        assertEquals(49, AndroidDeviceConditions.batteryPercent(99, 200))
        assertEquals(100, AndroidDeviceConditions.batteryPercent(100, 100))
        assertNull(AndroidDeviceConditions.batteryPercent(-1, 100))
        assertNull(AndroidDeviceConditions.batteryPercent(50, 0))
    }
}
