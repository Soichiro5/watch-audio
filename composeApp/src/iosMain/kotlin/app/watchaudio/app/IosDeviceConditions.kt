package app.watchaudio.app

import app.watchaudio.core.pipeline.DeviceConditions
import app.watchaudio.core.pipeline.ThermalLevel
import kotlin.math.roundToInt
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.thermalState
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

/**
 * The iPhone's power and thermal state for [app.watchaudio.core.pipeline.ProcessingPolicy] (#75):
 * battery level and state from [UIDevice] (battery monitoring is switched on here), Low Power Mode
 * and the thermal state from [NSProcessInfo]. No permission is needed. Main thread only (UIDevice).
 */
internal object IosDeviceConditions {
    fun read(): DeviceConditions {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val state = device.batteryState
        val level = device.batteryLevel // 0.0–1.0, or -1 when unknown
        val info = NSProcessInfo.processInfo
        return DeviceConditions(
            charging = state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                state == UIDeviceBatteryState.UIDeviceBatteryStateFull,
            batteryPercent = if (level < 0f) null else (level * 100).roundToInt().coerceIn(0, 100),
            powerSaveMode = info.lowPowerModeEnabled,
            thermal = thermalLevel(info.thermalState),
        )
    }

    /** nominal → NONE, fair → LIGHT, serious → SEVERE, critical → CRITICAL (see [ThermalLevel]). */
    fun thermalLevel(state: NSProcessInfoThermalState): ThermalLevel = when (state) {
        NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> ThermalLevel.LIGHT
        NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> ThermalLevel.SEVERE
        NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> ThermalLevel.CRITICAL
        else -> ThermalLevel.NONE
    }
}
