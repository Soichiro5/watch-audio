package app.watchaudio.core.settings

import app.watchaudio.core.alert.GapAlertSettings
import app.watchaudio.core.alert.QuietHours
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test
    fun defaultsWhenNothingIsStored() {
        val settings = AppSettings.fromEntries(emptyMap())
        assertEquals(false, settings.onboardingCompleted)
        assertEquals(GapAlertSettings(enabled = true, quietHours = QuietHours(true, 23 * 60, 7 * 60)), settings.gapAlert)
        assertEquals(null, settings.gapAlertShownFor)
        assertEquals(false, settings.transcribeOnlyWhileCharging)
    }

    @Test
    fun roundTrip() {
        val settings = AppSettings(
            onboardingCompleted = true,
            gapAlert = GapAlertSettings(enabled = false, quietHours = QuietHours(false, 22 * 60, 6 * 60 + 30)),
            gapAlertShownFor = 1_700_000_000_000,
            transcribeOnlyWhileCharging = true,
        )
        val stored = settings.toEntries().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        assertEquals(settings, AppSettings.fromEntries(stored))
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val settings = AppSettings.fromEntries(
            mapOf(
                AppSettings.Keys.ONBOARDING_COMPLETED to "yes",
                AppSettings.Keys.QUIET_HOURS_START to "1440",
                AppSettings.Keys.QUIET_HOURS_END to "x",
                AppSettings.Keys.GAP_ALERT_SHOWN_FOR to "",
                AppSettings.Keys.TRANSCRIBE_ONLY_WHILE_CHARGING to "1",
            ),
        )
        assertEquals(AppSettings(), settings)
    }
}
