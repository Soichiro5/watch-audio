package app.watchaudio.wear.ui

import android.content.Context
import app.watchaudio.wear.R

/**
 * Human-readable status for a [app.watchaudio.wear.recording.statusKey], shared by the app, the
 * ongoing notification, the tile and the complication.
 */
fun statusText(context: Context, statusKey: String, armed: Boolean): String = context.getString(
    when (statusKey) {
        "recording" -> R.string.status_recording
        "paused_off_body" -> R.string.status_paused_off_body
        "paused_charging" -> R.string.status_paused_charging
        "paused_low_battery" -> R.string.status_paused_low_battery
        "paused_low_storage" -> R.string.status_paused_low_storage
        "paused_interrupted" -> R.string.status_paused_interrupted
        else -> if (armed) R.string.status_stopped_tap_to_resume else R.string.status_stopped
    },
)

/** Very short form for complications (≤ 3-4 characters). */
fun statusShort(statusKey: String, armed: Boolean): String = when (statusKey) {
    "recording" -> "REC"
    "paused_off_body" -> "OFF"
    "paused_charging" -> "CHG"
    "paused_low_battery" -> "BAT"
    "paused_low_storage" -> "FULL"
    "paused_interrupted" -> "MIC"
    else -> if (armed) "!" else "—"
}
