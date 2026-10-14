package app.watchaudio.core.summary

import app.watchaudio.core.db.HOUR_MS
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.embedding.transcribedChunk
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.timeline.dayWindow
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Making a day's hour summaries and storing them (#27 §4.4). */
class SummaryRunTest {
    private val dir = TempDir("summary-run")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val window = dayWindow(LocalDate(2026, 10, 10), tokyo)
    private val log = mutableListOf<Pair<String, String>>()
    private val diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            log += event to detail
        }
    }

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun hour(h: Int) = window.start + h * HOUR_MS

    /** A talking hour: 12 utterances of 30 characters. */
    private suspend fun talk(id: String, h: Int) =
        store.transcribedChunk(id, hour(h) + 60_000, *Array(12) { i -> "第${i}の発話で予定について長めに話しているところです".take(30) })

    private fun run(provider: LlmProvider, now: Long = hour(20)) = SummaryRun(store, provider, tokyo, clock = { now }, diagnostics = diagnostics)

    private suspend fun stored() = store.read { hourSummariesBetween(window.start, window.end) }.associateBy { ((it.hourStart - window.start) / HOUR_MS).toInt() }

    @Test
    fun summarizesEachHourAndCitesItsSegments() = runTest {
        talk("a", 9)
        talk("b", 11)
        store.transcribedChunk("quiet", hour(10) + 60_000, "うん", "そう")
        val provider = FakeLlmProvider()
        val progress = mutableListOf<SummaryProgress>()
        val result = run(provider).run(window) { progress += it }
        assertEquals(SummaryRunResult.Finished(summarized = 2, failed = 0), result)
        assertEquals(
            listOf(SummaryProgress(0, 2, hour(9)), SummaryProgress(1, 2, hour(11)), SummaryProgress(2, 2, null)),
            progress,
        )
        val summaries = stored()
        assertEquals(setOf(9, 10, 11), summaries.keys)
        val nine = summaries.getValue(9)
        assertEquals(HourSummaryState.DONE, nine.state)
        assertEquals("fake", nine.provider)
        assertEquals("fake-1", nine.modelVersion)
        assertEquals(SummaryPrompts.VERSION, nine.promptVersion)
        val segments = store.read { segmentsStartedBetween(hour(9), hour(10)) }
        // The echo cites the first and the last line: the first and the last segments of the hour.
        assertTrue(segments.first().id in nine.highlights[0].segmentIds)
        assertTrue(segments.last().id in nine.highlights[1].segmentIds)
        assertEquals(HourSummaryState.LITTLE_SPEECH, summaries.getValue(10).state)
        assertEquals(2, provider.requests.size, "the quiet hour did not call the LLM")
        assertTrue(log.any { it.first == DiagnosticEvents.SUMMARY_RUN && "summarized=2 failed=0" in it.second })

        // Nothing changed: a second run does nothing.
        assertEquals(SummaryRunResult.Finished(0, 0), run(provider).run(window))
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun aFailureStaysInItsHourAndIsRetriedWhenAsked() = runTest {
        talk("a", 9)
        talk("b", 11)
        val provider = FakeLlmProvider()
        provider.scripted += LlmResult.Failed("assets_unavailable")
        assertEquals(SummaryRunResult.Finished(summarized = 1, failed = 1), run(provider).run(window))
        stored().getValue(9).let {
            assertEquals(HourSummaryState.FAILED, it.state)
            assertEquals("assets_unavailable", it.lastError)
            assertEquals(1, it.attempts)
        }
        assertEquals(HourSummaryState.DONE, stored().getValue(11).state)
        assertTrue(log.any { it == DiagnosticEvents.SUMMARY_FAILED to "hour=${hour(9)} type=assets_unavailable" })

        // 「もう一度」 for that hour only.
        assertEquals(SummaryRunResult.Finished(1, 0), run(provider).run(window, onlyHours = setOf(hour(9))))
        assertEquals(HourSummaryState.DONE, stored().getValue(9).state)
        assertEquals(0, stored().getValue(9).attempts)
    }

    @Test
    fun anExceptionIsAFailureOfThatHourOnly() = runTest {
        talk("a", 9)
        talk("b", 11)
        var calls = 0
        val provider = FakeLlmProvider { request ->
            if (calls++ == 0) throw IllegalStateException("bridge failed with private text") else FakeLlmProvider.echo(request)
        }
        assertEquals(SummaryRunResult.Finished(summarized = 1, failed = 1), run(provider).run(window))
        assertEquals("IllegalStateException", stored().getValue(9).lastError)
    }

    @Test
    fun refusalsAreStoredAndNotRetried() = runTest {
        talk("a", 9)
        val provider = FakeLlmProvider { LlmResult.Refused }
        run(provider).run(window)
        assertEquals(HourSummaryState.REFUSED, stored().getValue(9).state)
        run(provider).run(window)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun aRateLimitStopsTheRunAndLeavesTheRest() = runTest {
        talk("a", 9)
        talk("b", 11)
        val provider = FakeLlmProvider()
        provider.scripted += LlmResult.Ok("""{"summary": "九時。"}""")
        provider.scripted += LlmResult.RateLimited(null)
        assertEquals(SummaryRunResult.RateLimited(summarized = 1), run(provider).run(window))
        assertEquals(setOf(9), stored().keys)
        assertEquals(SummaryRunResult.Finished(1, 0), run(provider).run(window))
        assertEquals(setOf(9, 11), stored().keys)
    }

    @Test
    fun anUnavailableModelDoesNothing() = runTest {
        talk("a", 9)
        val provider = FakeLlmProvider(availability = LlmAvailability.TurnedOff)
        assertEquals(SummaryRunResult.Unavailable(LlmAvailability.TurnedOff), run(provider).run(window))
        val unsupported = UnsupportedLlmProvider()
        assertEquals(SummaryRunResult.Unavailable(LlmAvailability.Unsupported("no_on_device_llm")), run(unsupported).run(window))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun theCurrentHourAndHoursWaitingForTranscriptionAreLeft() = runTest {
        talk("a", 9)
        talk("b", 15)
        val pending = app.watchaudio.testing.testManifest("p", hour(9) + 30 * 60_000)
        store.transaction { insertChunk(pending, pending.audio?.fileName, 0) }
        assertEquals(SummaryRunResult.Finished(0, 0), run(FakeLlmProvider(), now = hour(15) + 40 * 60_000).run(window))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun cancellingKeepsTheFinishedHours() = runTest {
        talk("a", 9)
        talk("b", 11)
        val second = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<LlmResult>()
        val provider = object : LlmProvider by FakeLlmProvider() {
            var calls = 0
            override suspend fun generate(request: LlmRequest): LlmResult {
                if (calls++ == 0) return FakeLlmProvider.echo(request)
                second.complete(Unit)
                return blocked.await()
            }
        }
        val job = launch { run(provider).run(window) }
        second.await()
        job.cancelAndJoin()
        assertEquals(setOf(9), stored().keys)
        assertFalse(stored().getValue(9).stale)
    }

    @Test
    fun changedSegmentsMakeTheHourStaleAndTheNextRunMakesItAgain() = runTest {
        talk("a", 9)
        val provider = FakeLlmProvider()
        run(provider).run(window)
        store.transaction {
            saveTranscript("a", listOf(app.watchaudio.core.db.NewSegment(hour(9) + 60_000, hour(9) + 61_000, "言い直し".repeat(60), "ja")), "fake")
        }
        assertTrue(stored().getValue(9).stale)
        assertEquals(SummaryRunResult.Finished(1, 0), run(provider).run(window))
        assertFalse(stored().getValue(9).stale)
    }
}
