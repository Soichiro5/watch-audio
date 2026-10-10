package app.watchaudio.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the app after a reboot. The work happens in [WearApp.onCreate]: finalize chunks left by
 * the previous boot, schedule uploads, and — if the wearer had recording on — post the "tap to
 * resume" reminder (a microphone foreground service cannot be started from BOOT_COMPLETED on
 * Android 14+).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppGraph.of(context).log("boot_completed")
    }
}
