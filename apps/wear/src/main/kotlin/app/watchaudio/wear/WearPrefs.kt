package app.watchaudio.wear

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** Small persistent settings and state that must survive process death. */
class WearPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("watch_audio", Context.MODE_PRIVATE)

    /** Stable per install; identifies this watch in every manifest. */
    val deviceId: String
        @Synchronized get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit(commit = true) { putString(KEY_DEVICE_ID, id) }
        }

    /** The wearer wants recording on (tapped Start, not Stop). Survives process death and reboots. */
    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_ARMED, value) }

    /** Diagnostics switch (battery measurements without transfer); on by default. */
    var uploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPLOAD_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_UPLOAD_ENABLED, value) }

    var lastAckAtEpochMs: Long
        get() = prefs.getLong(KEY_LAST_ACK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_ACK, value) }

    /** Last mode shown to the user, for the tile/complication when the process is not running. */
    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_STATUS, value) }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ARMED = "armed"
        const val KEY_UPLOAD_ENABLED = "upload_enabled"
        const val KEY_LAST_ACK = "last_ack_at"
        const val KEY_LAST_STATUS = "last_status"
    }
}
