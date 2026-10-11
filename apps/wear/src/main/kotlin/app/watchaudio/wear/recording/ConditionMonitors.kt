package app.watchaudio.wear.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

data class BatteryStatus(val percent: Int?, val charging: Boolean)

/** Reads the sticky battery broadcast once, without registering anything. */
fun Context.currentBattery(): BatteryStatus =
    parseBattery(ContextCompat.registerReceiver(this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))

private fun parseBattery(intent: Intent?): BatteryStatus {
    if (intent == null) return BatteryStatus(null, false)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
    // "Charging" = on external power: a full battery on the charger still means the watch is off the wrist.
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    return BatteryStatus(percent, plugged)
}

/**
 * Battery level and charger state. `ACTION_BATTERY_CHANGED` is sticky, so the current state is
 * delivered right after [start]; it also fires on plug/unplug, which covers
 * `ACTION_POWER_CONNECTED`/`DISCONNECTED`.
 */
class BatteryMonitor(private val context: Context, private val onChange: (BatteryStatus) -> Unit) {
    private var last: BatteryStatus? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = parseBattery(intent)
            if (status != last) {
                last = status
                onChange(status)
            }
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }
}

/**
 * Wear detection via `TYPE_LOW_LATENCY_OFFBODY_DETECT` (a wake-up sensor, so it is delivered while
 * the watch sleeps). Reports `true` = on body. Devices without the sensor never report; the
 * recorder then treats the watch as worn.
 */
class OffBodyMonitor(context: Context, private val onChange: (onBody: Boolean, atElapsedMs: Long) -> Unit) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
    private var registered = false

    val isAvailable: Boolean get() = sensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 1.0 = on body, 0.0 = off body.
            onChange(event.values.firstOrNull() != 0f, SystemClock.elapsedRealtime())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** @return false when the sensor is missing or could not be registered (e.g. permission). */
    fun start(): Boolean {
        val s = sensor ?: return false
        if (registered) return true
        registered = runCatching { sensorManager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
        return registered
    }

    fun stop() {
        if (!registered) return
        registered = false
        sensorManager.unregisterListener(listener)
    }
}
