package app.watchaudio.core.alert

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.settings.updateSettings
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class GapAlertEvaluatorTest {
    private val dir = TempDir("gap-alert")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val start = LocalDate(2026, 10, 10).atStartOfDayIn(tokyo).toEpochMilliseconds()
    private val evaluator = GapAlertEvaluator(store) { tokyo }

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun at(hour: Int, minute: Int = 0) = start + hour * 3_600_000L + minute * 60_000L

    private suspend fun receive(id: String, startAt: Long, receivedAt: Long, endReason: ChunkEndReason = ChunkEndReason.ROTATION) =
        store.transaction {
            insertChunk(
                testManifest(id, startAt, audio = null, source = SourcePlatform.WATCHOS, timeZone = "Asia/Tokyo", endReason = endReason),
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
