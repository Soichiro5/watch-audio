package app.watchaudio.android.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.watchaudio.core.pipeline.DeviceConditions
import app.watchaudio.core.pipeline.ThermalLevel

/**
 * Reads the phone's power and thermal state for [app.watchaudio.core.pipeline.ProcessingPolicy]
 * (#75): external power and battery level from the sticky `ACTION_BATTERY_CHANGED` broadcast,
 * battery saver and the thermal status from [PowerManager]. None of these needs a permission.
 */
class AndroidDeviceConditions(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)

    fun read(): DeviceConditions {
        // A null receiver only reads the last sticky broadcast; nothing stays registered.
        val battery = ContextCompat.registerReceiver(
            app,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return DeviceConditions(
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            batteryPercent = batteryPercent(
                battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1,
                battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1,
            ),
            powerSaveMode = power?.isPowerSaveMode == true,
            thermal = thermalLevel(power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE),
        )
    }

    companion object {
        /** `EXTRA_LEVEL` / `EXTRA_SCALE` as 0–100, or null if the broadcast did not say. */
        fun batteryPercent(level: Int, scale: Int): Int? =
            if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

        /** `PowerManager.THERMAL_STATUS_*` on the shared scale; EMERGENCY and SHUTDOWN count as CRITICAL. */
        fun thermalLevel(status: Int): ThermalLevel = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            status == PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            status == PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            status == PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            else -> ThermalLevel.NONE
        }
    }
}
