package app.watchaudio.android

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.watchaudio.android.alert.GapAlertMonitor
import app.watchaudio.app.Strings
import app.watchaudio.core.alert.GapAlertSettings
import app.watchaudio.core.alert.QuietHours
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.settings.loadSettings
import app.watchaudio.core.settings.updateSettings
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #43: when chunks stop arriving, the production [GapAlertMonitor] posts the local notification
 * and it shows in the notification shade. The monitor runs with a clock 15 minutes ahead instead of
 * waiting for the 10-minute gap (WorkManager's timer is replaced by a direct [GapAlertMonitor.check]).
 */
@RunWith(AndroidJUnit4::class)
class GapAlertNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @After
    fun tearDown() {
        Evidence.shell("cmd statusbar collapse")
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    @Test
    fun gapAlertAppearsInTheNotificationShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val graph = PlaybackFixtures.graph
        runBlocking {
            graph.startup.join()
            graph.store.updateSettings {
                it.copy(gapAlert = GapAlertSettings(enabled = true, quietHours = QuietHours(enabled = false)), gapAlertShownFor = null)
            }
        }
        // The newest chunk: recording continues (rotation), then nothing more arrives.
        val now = System.currentTimeMillis()
        val manifest = ChunkManifest(
            chunkId = "gap-" + UUID.randomUUID(),
            sessionId = "gap-alert",
            deviceId = "emulator-test",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = now - 60_000,
            durationMs = 30_000,
            timeZone = ZoneId.systemDefault().id,
            audio = null,
            endReason = ChunkEndReason.ROTATION,
        )
        assertEquals(AckStatus.STORED, runBlocking { graph.ingestor.ingest(manifest, null) }.status)

        val monitor = GapAlertMonitor(context, graph.store, graph.scope, clock = { System.currentTimeMillis() + 15 * 60_000L })
        runBlocking { monitor.check() }

        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }
        assertNotNull(posted, "the gap alert notification was not posted")
        device.openNotification()
        val shown = device.wait(Until.hasObject(By.textContains(Strings.GAP_ALERT_TITLE)), 15_000) == true
        Evidence.screenshot("gap-alert-notification")
        val text = posted.notification.extras.getCharSequence("android.text")
        val shownFor = runBlocking { graph.store.loadSettings() }.gapAlertShownFor
        Evidence.log(
            "GAP_ALERT_CHECK posted=true in_shade=$shown channel=${posted.notification.channelId} " +
                "title=${Strings.GAP_ALERT_TITLE} text=$text shown_for_recorded=${shownFor != null}",
        )
        assertTrue(shown, "the gap alert is not visible in the notification shade")
        assertNotNull(shownFor)
    }

    private companion object {
        /** `GapAlertMonitor.NOTIFICATION_ID`. */
        const val NOTIFICATION_ID = 43
    }
}
