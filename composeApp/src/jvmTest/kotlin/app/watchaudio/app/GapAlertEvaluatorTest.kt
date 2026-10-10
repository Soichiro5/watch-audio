package app.watchaudio.app

import app.watchaudio.app.alert.GapAlertEvaluator
import app.watchaudio.app.logic.GapAlertDecision
import app.watchaudio.app.logic.QuietHours
import app.watchaudio.app.logic.dayWindow
import app.watchaudio.app.settings.updateSettings
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class GapAlertEvaluatorTest {
    private val dbFile = File.createTempFile("watch-audio-alert", ".db").also { it.delete() }
    private val store = LocalStore.open(dbFile.absolutePath)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val start = dayWindow(LocalDate(2026, 10, 10), tokyo).start
    private val evaluator = GapAlertEvaluator(store) { tokyo }

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
    }

    private fun at(hour: Int, minute: Int = 0) = start + hour * 3_600_000L + minute * 60_000L

    private suspend fun receive(id: String, startAt: Long, receivedAt: Long, endReason: ChunkEndReason = ChunkEndReason.ROTATION) =
        store.transaction {
            insertChunk(
                ChunkManifest(
                    chunkId = id,
                    sessionId = "s",
                    deviceId = "watch",
                    source = SourcePlatform.WATCHOS,
                    startedAtEpochMs = startAt,
                    durationMs = 300_000,
                    timeZone = "Asia/Tokyo",
                    audio = null,
                    endReason = endReason,
                ),
                audioPath = null,
                receivedAt = receivedAt,
            )
        }

    @Test
    fun alertsOncePerGap() = runTest {
        assertEquals(GapAlertDecision.None, evaluator.decide(at(12)))
        assertNull(evaluator.alertAt())

        receive("a", at(14, 50), receivedAt = at(14, 56))
        assertEquals(at(15, 6), evaluator.alertAt())
        assertEquals(GapAlertDecision.Notify(at(14, 56)), evaluator.decide(at(15, 30)))
        assertEquals("録音が届いていません（最後の受信: 14:56）。Watch を確認してください", evaluator.message(at(14, 56)))

        evaluator.markShown(at(14, 56))
        assertEquals(GapAlertDecision.None, evaluator.decide(at(15, 45)))

        // Recording resumes, then stops on purpose: nothing to alert.
        receive("b", at(15, 40), receivedAt = at(15, 46), endReason = ChunkEndReason.CHARGING)
        assertNull(evaluator.alertAt())
        assertEquals(GapAlertDecision.None, evaluator.decide(at(17)))
    }

    @Test
    fun followsTheSettings() = runTest {
        receive("a", at(22, 50), receivedAt = at(22, 56))
        assertEquals(at(7) + 24 * 3_600_000L, evaluator.alertAt())
        store.updateSettings { it.copy(gapAlert = it.gapAlert.copy(quietHours = QuietHours(enabled = false))) }
        assertEquals(at(23, 6), evaluator.alertAt())
        store.updateSettings { it.copy(gapAlert = it.gapAlert.copy(enabled = false)) }
        assertNull(evaluator.alertAt())
    }
}
