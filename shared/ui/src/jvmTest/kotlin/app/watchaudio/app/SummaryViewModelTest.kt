package app.watchaudio.app

import app.watchaudio.app.state.DayUiState
import app.watchaudio.app.state.DayViewModel
import app.watchaudio.app.state.NowProvider
import app.watchaudio.app.state.SummaryNotice
import app.watchaudio.core.db.HOUR_MS
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.summary.HourAction
import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmProvider
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.SummaryProgress
import app.watchaudio.core.summary.UnsupportedLlmProvider
import app.watchaudio.core.timeline.dayWindow
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Hour summaries on the day screen (#27): availability, 「この日を要約」, stopping, notices, highlights. */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dbFile = File.createTempFile("watch-audio-summary", ".db").also { it.delete() }
    private val store = LocalStore.open(dbFile.absolutePath, dispatcher = dispatcher)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val day = LocalDate(2026, 10, 10)
    private val window = dayWindow(day, tokyo)
    private val clock = NowProvider { at(15, 40) }

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
    }

    private fun at(hour: Int, minute: Int = 0) = window.start + hour * HOUR_MS + minute * 60_000L

    /** A chunk at [start] with 10 utterances of about 25 characters (enough to summarize an hour). */
    private suspend fun talk(id: String, start: Long, state: Boolean = true) {
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = start,
            durationMs = 300_000,
            timeZone = tokyo.id,
            audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 100_000, "00"),
            endReason = ChunkEndReason.ROTATION,
        )
        store.transaction {
            insertChunk(manifest, "$id.m4a", start + 300_000)
            if (state) {
                markTranscribing(id)
                saveTranscript(id, (0 until 10).map { i -> NewSegment(start + i * 20_000L, start + i * 20_000L + 3_000, "${id}の${i}番目の発話で、明日の予定を確認しました", "ja") }, "test")
            }
        }
    }

    private fun services(llm: LlmProvider) = FakeAppServices(store, llm = llm)

    private fun TestScope.viewModel(services: FakeAppServices): Pair<DayViewModel, () -> DayUiState> {
        val vm = DayViewModel(services, tokyo, clock, refreshPeriodMs = 0, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to { vm.state.value }
    }

    @Test
    fun withoutAnOnDeviceLlmTheHoursAreThereButUnsupported() = runTest(dispatcher) {
        talk("a", at(9))
        val (_, state) = viewModel(services(UnsupportedLlmProvider()))
        val summaries = assertNotNull(state().content?.summaries)
        assertEquals(LlmAvailability.Unsupported("no_on_device_llm"), summaries.availability)
        assertEquals(listOf(at(9)), summaries.hours.map { it.hourStart })
        assertEquals(1, summaries.toSummarize)
    }

    @Test
    fun aDayWithoutSpeechHasNoSummaries() = runTest(dispatcher) {
        val (_, state) = viewModel(services(FakeSummaryLlm()))
        assertNotNull(state().content)
        assertNull(state().content?.summaries)
    }

    @Test
    fun summarizeTheDayThenOpenAHighlight() = runTest(dispatcher) {
        talk("a", at(9))
        talk("b", at(11))
        talk("c", at(15)) // the hour is not over
        val llm = FakeSummaryLlm().apply { gate = CompletableDeferred() }
        val services = services(llm)
        val (vm, state) = viewModel(services)
        assertEquals(2, state().content?.summaries?.toSummarize)

        vm.summarizeDay()
        runCurrent()
        assertEquals(SummaryProgress(0, 2, at(9)), state().summaryRun?.progress)
        llm.gate!!.complete(Unit)
        runCurrent()
        assertNull(state().summaryRun)
        val hours = state().content!!.summaries!!.hours.associateBy { it.hourStart }
        assertEquals(HourSummaryState.DONE, hours.getValue(at(9)).summary?.state)
        assertEquals(HourSummaryState.DONE, hours.getValue(at(11)).summary?.state)
        assertEquals(HourAction.HOUR_NOT_OVER, hours.getValue(at(15)).action)
        assertEquals(0, state().content!!.summaries!!.toSummarize)

        // The highlight cites the first line of the hour: the chunk's first utterance.
        val highlight = hours.getValue(at(11)).summary!!.highlights.single()
        vm.openHighlight(highlight)
        runCurrent()
        assertEquals("b.m4a" to 0L, services.played.last())
        assertEquals(highlight.segmentIds.first(), state().focusSegmentId)
    }

    /** Android's AICore (#205): the model is downloaded only after 「この日を要約」, then the run goes on. */
    @Test
    fun aModelToDownloadIsPreparedOnlyAfterTheTap() = runTest(dispatcher) {
        talk("a", at(9))
        val llm = FakeSummaryLlm(availability = LlmAvailability.NeedsDownload(null)).apply {
            afterPrepare = LlmAvailability.Available("nano")
            prepareGate = CompletableDeferred()
        }
        val (vm, state) = viewModel(services(llm))
        assertEquals(LlmAvailability.NeedsDownload(null), state().content?.summaries?.availability)
        assertEquals(0, llm.prepareCalls)

        vm.summarizeDay()
        runCurrent()
        val preparing = assertNotNull(state().summaryRun)
        assertTrue(preparing.preparing)
        assertEquals(0.5f, preparing.downloaded)
        assertTrue(llm.requests.isEmpty())

        llm.prepareGate!!.complete(Unit)
        runCurrent()
        assertNull(state().summaryRun)
        assertNull(state().summaryNotice)
        assertEquals(1, llm.prepareCalls)
        val hour = state().content!!.summaries!!.hours.single()
        assertEquals(HourSummaryState.DONE, hour.summary?.state)
        assertEquals(LlmAvailability.Available("nano"), state().content!!.summaries!!.availability)
    }

    @Test
    fun aModelThatCannotBePreparedIsANoticeAndNothingElse() = runTest(dispatcher) {
        talk("a", at(9))
        val llm = FakeSummaryLlm(availability = LlmAvailability.NeedsDownload(null))
        val (vm, state) = viewModel(services(llm))
        vm.summarizeDay()
        runCurrent()
        assertEquals(1, llm.prepareCalls)
        assertEquals(SummaryNotice.MODEL_FAILED, state().summaryNotice)
        assertNull(state().summaryRun)
        assertTrue(llm.requests.isEmpty())
        assertNull(state().content!!.summaries!!.hours.single().summary)
    }

    /** A provider that stops the run (Android refuses generation in the background) leaves no failed hour. */
    @Test
    fun aCancellingProviderStopsTheRunWithoutANotice() = runTest(dispatcher) {
        talk("a", at(9))
        talk("b", at(11))
        val llm = FakeSummaryLlm()
        llm.answer = { throw CancellationException("background") }
        val (vm, state) = viewModel(services(llm))
        vm.summarizeDay()
        runCurrent()
        assertNull(state().summaryRun)
        assertNull(state().summaryNotice)
        assertEquals(1, llm.requests.size)
        assertTrue(state().content!!.summaries!!.hours.all { it.summary == null })
        assertEquals(2, state().content!!.summaries!!.toSummarize)
    }

    @Test
    fun changingTheDayStopsTheRunAndKeepsFinishedHours() = runTest(dispatcher) {
        talk("a", at(9))
        talk("b", at(11))
        var calls = 0
        val blocked = CompletableDeferred<LlmResult>()
        val llm = FakeSummaryLlm()
        llm.answer = { request -> if (calls++ == 0) FakeSummaryLlm.defaultAnswer(request) else throw IllegalStateException("not reached") }
        val (vm, state) = viewModel(services(object : LlmProvider by llm {
            override suspend fun generate(request: app.watchaudio.core.summary.LlmRequest): LlmResult =
                if (calls == 0) llm.generate(request) else blocked.await()
        }))
        vm.summarizeDay()
        runCurrent()
        assertEquals(SummaryProgress(1, 2, at(11)), state().summaryRun?.progress)
        vm.previousDay()
        runCurrent()
        assertNull(state().summaryRun)
        vm.nextDay()
        runCurrent()
        val hours = state().content!!.summaries!!.hours.associateBy { it.hourStart }
        assertEquals(HourSummaryState.DONE, hours.getValue(at(9)).summary?.state)
        assertNull(hours.getValue(at(11)).summary)
        assertEquals(1, state().content!!.summaries!!.toSummarize)
    }

    @Test
    fun aRateLimitAndAFailureAreShown() = runTest(dispatcher) {
        talk("a", at(9))
        talk("b", at(11))
        val llm = FakeSummaryLlm()
        var calls = 0
        llm.answer = { request ->
            when (calls++) {
                0 -> LlmResult.Failed("assets_unavailable")
                1 -> LlmResult.RateLimited(null)
                else -> FakeSummaryLlm.defaultAnswer(request)
            }
        }
        val (vm, state) = viewModel(services(llm))
        vm.summarizeDay()
        runCurrent()
        assertEquals(SummaryNotice.RATE_LIMITED, state().summaryNotice)
        val failed = state().content!!.summaries!!.hours.first { it.hourStart == at(9) }
        assertEquals(HourSummaryState.FAILED, failed.summary?.state)

        // 「もう一度」 makes that hour only; the notice goes.
        vm.retrySummary(at(9))
        runCurrent()
        assertNull(state().summaryNotice)
        val hours = state().content!!.summaries!!.hours.associateBy { it.hourStart }
        assertEquals(HourSummaryState.DONE, hours.getValue(at(9)).summary?.state)
        assertNull(hours.getValue(at(11)).summary)
    }

    @Test
    fun anUnexpectedErrorStopsTheRunWithANotice() = runTest(dispatcher) {
        talk("a", at(9))
        val broken = object : LlmProvider by FakeSummaryLlm() {
            override suspend fun contextTokens(): Int = 4_096
            override suspend fun availability(): LlmAvailability {
                if (failNext) throw IllegalStateException("bridge")
                return LlmAvailability.Available("1")
            }
            var failNext = false
        }
        val (vm, state) = viewModel(services(broken))
        broken.failNext = true
        vm.summarizeDay()
        runCurrent()
        assertEquals(SummaryNotice.FAILED, state().summaryNotice)
        assertNull(state().summaryRun)
        // The day still loads; the platform's error reads as unsupported.
        store.transaction { }
        runCurrent()
        assertIs<LlmAvailability.Unsupported>(state().content?.summaries?.availability)
    }

    @Test
    fun hoursWaitingForTranscriptionAreNotSummarized() = runTest(dispatcher) {
        talk("a", at(9))
        talk("p", at(9, 30), state = false)
        val (_, state) = viewModel(services(FakeSummaryLlm()))
        val hour = state().content!!.summaries!!.hours.single()
        assertEquals(HourAction.WAIT_TRANSCRIPTION, hour.action)
        assertTrue(state().content!!.summaries!!.toSummarize == 0)
    }
}
