package app.watchaudio.app.settings

import app.watchaudio.app.logic.GapAlertSettings
import app.watchaudio.app.logic.QuietHours
import app.watchaudio.core.db.LocalStore

/**
 * Phone app preferences, stored in the local database's key-value `app_setting` table so they stay
 * on the device like everything else (no platform backup).
 */
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val gapAlert: GapAlertSettings = GapAlertSettings(),
    /** `lastReceivedAt` of the gap the alert was last shown for, so each gap is alerted once. */
    val gapAlertShownFor: Long? = null,
) {
    /** Key-value form; null values remove the key. */
    fun toEntries(): Map<String, String?> = mapOf(
        Keys.ONBOARDING_COMPLETED to onboardingCompleted.toString(),
        Keys.GAP_ALERT_ENABLED to gapAlert.enabled.toString(),
        Keys.QUIET_HOURS_ENABLED to gapAlert.quietHours.enabled.toString(),
        Keys.QUIET_HOURS_START to gapAlert.quietHours.startMinute.toString(),
        Keys.QUIET_HOURS_END to gapAlert.quietHours.endMinute.toString(),
        Keys.GAP_ALERT_SHOWN_FOR to gapAlertShownFor?.toString(),
    )

    object Keys {
        const val ONBOARDING_COMPLETED = "onboarding.completed"
        const val GAP_ALERT_ENABLED = "gap_alert.enabled"
        const val QUIET_HOURS_ENABLED = "gap_alert.quiet_hours.enabled"
        const val QUIET_HOURS_START = "gap_alert.quiet_hours.start_minute"
        const val QUIET_HOURS_END = "gap_alert.quiet_hours.end_minute"
        const val GAP_ALERT_SHOWN_FOR = "gap_alert.shown_for"
    }

    companion object {
        /** Missing or unreadable values fall back to the defaults. */
        fun fromEntries(entries: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            val quiet = defaults.gapAlert.quietHours
            fun bool(key: String, default: Boolean) = entries[key]?.toBooleanStrictOrNull() ?: default
            fun minute(key: String, default: Int) =
                entries[key]?.toIntOrNull()?.takeIf { it in 0 until QuietHours.MINUTES_PER_DAY } ?: default
            return AppSettings(
                onboardingCompleted = bool(Keys.ONBOARDING_COMPLETED, defaults.onboardingCompleted),
                gapAlert = GapAlertSettings(
                    enabled = bool(Keys.GAP_ALERT_ENABLED, defaults.gapAlert.enabled),
                    quietHours = QuietHours(
                        enabled = bool(Keys.QUIET_HOURS_ENABLED, quiet.enabled),
                        startMinute = minute(Keys.QUIET_HOURS_START, quiet.startMinute),
                        endMinute = minute(Keys.QUIET_HOURS_END, quiet.endMinute),
                    ),
                ),
                gapAlertShownFor = entries[Keys.GAP_ALERT_SHOWN_FOR]?.toLongOrNull(),
            )
        }
    }
}

suspend fun LocalStore.loadSettings(): AppSettings = read { AppSettings.fromEntries(settings()) }

/** Atomically reads, transforms and stores the settings; returns the new value. */
suspend fun LocalStore.updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = transaction {
    val current = AppSettings.fromEntries(settings())
    val updated = transform(current)
    if (updated != current) {
        val before = current.toEntries()
        updated.toEntries().forEach { (key, value) -> if (before[key] != value) putSetting(key, value) }
    }
    updated
}
