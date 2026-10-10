package app.watchaudio.app

import app.watchaudio.app.logic.ArrivalState
import app.watchaudio.app.logic.GapCause
import app.watchaudio.app.logic.SnippetPart
import app.watchaudio.app.logic.TimelineSpan
import app.watchaudio.app.logic.TranscriptEntry
import app.watchaudio.app.logic.dayWindow
import app.watchaudio.app.state.DayViewModel
import app.watchaudio.app.state.NowProvider
import app.watchaudio.app.state.SearchViewModel
import app.watchaudio.app.state.StatusViewModel
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dbFile = File.createTempFile("watch-audio-ui", ".db").also { it.delete() }
    // Database work runs on the test scheduler, so the tests control time completely.
    private val store = LocalStore.open(dbFile.absolutePath, dispatcher = dispatcher)
    private val services = FakeAppServices(store)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val day = LocalDate(2026, 10, 10)
    private val window = dayWindow(day, tokyo)
    private var now = at(15)
    private val clock = NowProvider { now }

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
    }

    private fun at(hour: Int, minute: Int = 0) = window.start + hour * 3_600_000L + minute * 60_000L

    private suspend fun addChunk(
        id: String,
        start: Long,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        receivedAt: Long = start + 300_000,
        audio: Boolean = true,
    ) = store.transaction {
        insertChunk(
            ChunkManifest(
                chunkId = id,
                sessionId = "s",
                deviceId = "watch",
                source = SourcePlatform.WEAROS,
                startedAtEpochMs = start,
                durationMs = 300_000,
                timeZone = "Asia/Tokyo",
                audio = if (audio) AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 100_000, "00") else null,
                endReason = endReason,
            ),
            audioPath = if (audio) "2026/10/10/$id.m4a" else null,
            receivedAt = receivedAt,
        )
    }

    private suspend fun transcribe(id: String, vararg segments: NewSegment) = store.transaction {
        markTranscribing(id)
        saveTranscript(id, segments.toList(), "test")
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun dayShowsTimelineTranscriptAndPendingChunks() = runVmTest {
        addChunk("a", at(9))
        addChunk("b", at(9, 5), endReason = ChunkEndReason.CHARGING)
        transcribe("a", NewSegment(at(9) + 10_000, at(9) + 13_000, "おはようございます", "ja"))
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val state = vm.state.value
        assertEquals(day, state.date)
        assertTrue(state.isToday)
        val content = assertNotNull(state.content)
        assertEquals(10 * 60_000L, content.timeline.audioMs)
        assertTrue(TimelineSpan.Gap(at(9, 10), now, GapCause.Stopped(ChunkEndReason.CHARGING)) in content.timeline.spans)
        assertEquals(listOf("s1", "cb"), content.entries.map { it.key })

        // A new chunk shows up without waiting for the periodic refresh.
        addChunk("c", at(14))
        runCurrent()
        val entries = vm.state.value.content!!.entries
        assertEquals(listOf("s1", "cb"), entries.map { it.key }) // b and c are separated by nothing transcribed
        assertEquals(listOf("b", "c"), (entries[1] as TranscriptEntry.Untranscribed).chunkIds)

        // Playing an utterance starts at its offset inside the chunk.
        vm.play(entries[0] as TranscriptEntry.Utterance)
        runCurrent()
        assertEquals(listOf("2026/10/10/a.m4a" to 10_000L), services.played)
        assertEquals(at(9) + 10_000, vm.state.value.nowPlaying?.startsAt)
        vm.stopPlayback()
        runCurrent()
        assertNull(vm.state.value.nowPlaying)
        assertEquals(1, services.stopRequests)
    }

    @Test
    fun navigatingDaysNeverGoesPastToday() = runVmTest {
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertTrue(!vm.state.value.canGoForward)
        vm.nextDay()
        runCurrent()
        assertEquals(day, vm.state.value.date)

        vm.previousDay()
        runCurrent()
        assertEquals(LocalDate(2026, 10, 9), vm.state.value.date)
        assertTrue(vm.state.value.canGoForward)
        val content = assertNotNull(vm.state.value.content)
        assertEquals(LocalDate(2026, 10, 9), content.date)
        assertEquals(24 * 3_600_000L, content.timeline.window.lengthMs)

        vm.goToToday()
        runCurrent()
        assertEquals(day, vm.state.value.date)
    }

    @Test
    fun periodicRefreshMovesNow() = runVmTest {
        addChunk("a", at(14, 50))
        val vm = DayViewModel(services, tokyo, clock, refreshPeriodMs = 20_000, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(GapCause.Awaiting, (vm.state.value.content!!.timeline.spans.last { it is TimelineSpan.Gap } as TimelineSpan.Gap).cause)

        now = at(16)
        advanceTimeBy(20_001)
        assertEquals(GapCause.Missing, (vm.state.value.content!!.timeline.spans.last { it is TimelineSpan.Gap } as TimelineSpan.Gap).cause)
    }

    @Test
    fun retryRequeuesFailedChunks() = runVmTest {
        addChunk("f", at(9))
        store.transaction {
            repeat(3) {
                markTranscribing("f")
                markFailed("f", "boom")
            }
        }
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val failed = vm.state.value.content!!.entries.single() as TranscriptEntry.Untranscribed
        assertEquals(TranscriptEntry.Untranscribed.Kind.FAILED, failed.kind)
        assertEquals("boom", failed.error)

        vm.transcribe(failed)
        runCurrent()
        assertEquals(ChunkState.RECEIVED, store.read { chunk("f") }!!.state)
        assertEquals(1, services.transcriptionRequests)
        assertEquals(
            TranscriptEntry.Untranscribed.Kind.PENDING,
            (vm.state.value.content!!.entries.single() as TranscriptEntry.Untranscribed).kind,
        )
    }

    @Test
    fun searchHighlightsAndOpensTheDayOfAHit() = runVmTest {
        val yesterday = at(-14) // 10:00 on Oct 9
        addChunk("y", yesterday)
        transcribe("y", NewSegment(yesterday + 60_000, yesterday + 64_000, "明日の会議は十時からです", "ja"))

        val search = SearchViewModel(services, tokyo, scope = backgroundScope)
        backgroundScope.launch { search.results.collect {} }
        backgroundScope.launch { search.searching.collect {} }
        runCurrent()
        assertNull(search.results.value)

        search.setQuery("会議は")
        runCurrent()
        assertTrue(search.searching.value)
        advanceTimeBy(300)
        val results = assertNotNull(search.results.value)
        assertEquals("会議は", results.query)
        val hit = results.results.single()
        assertEquals("10月9日(金) 10:01", hit.whenLabel)
        assertEquals(SnippetPart("会議は", true), hit.parts[1])
        assertTrue(!search.searching.value)

        // New transcripts are found without retyping.
        addChunk("t", at(11))
        transcribe("t", NewSegment(at(11) + 1_000, at(11) + 2_000, "会議は延期", "ja"))
        runCurrent()
        assertEquals(2, search.results.value!!.results.size)

        // Opening the old hit jumps to its day, focuses it and plays from its offset.
        val day = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { day.state.collect {} }
        day.openSegment(hit.chunkId, hit.segmentId, hit.startAt)
        runCurrent()
        assertEquals(LocalDate(2026, 10, 9), day.state.value.date)
        assertEquals(hit.segmentId, day.state.value.focusSegmentId)
        assertEquals(listOf("2026/10/10/y.m4a" to 60_000L), services.played)
        day.focusHandled()
        runCurrent()
        assertNull(day.state.value.focusSegmentId)

        search.setQuery("")
        advanceTimeBy(1)
        assertNull(search.results.value)
    }

    @Test
    fun statusWarnsWhenChunksStopArrivingWhileRecording() = runVmTest {
        val vm = StatusViewModel(services, clock, refreshPeriodMs = 20_000, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(ArrivalState.NoData, vm.state.value.arrival)
        assertEquals(0, vm.state.value.stats!!.chunks)

        addChunk("a", at(14, 50), receivedAt = at(14, 56))
        transcribe("a", NewSegment(at(14, 51), at(14, 52), "テスト", "ja"))
        addChunk("b", at(14, 55), receivedAt = at(14, 58), audio = false)
        runCurrent()
        val state = vm.state.value
        assertEquals(ArrivalState.Receiving(at(14, 58)), state.arrival)
        val stats = assertNotNull(state.stats)
        assertEquals(2, stats.chunks)
        assertEquals(1, stats.segments)
        assertEquals(300_000, stats.recordedMs)

        // Nothing for 12 minutes while the last chunk said "keep going".
        now = at(15, 10)
        advanceTimeBy(20_001)
        val late = assertIs<ArrivalState.Late>(vm.state.value.arrival)
        assertEquals(12 * 60_000L, late.silentForMs)

        // The platform heard from the watch more recently than the database.
        services.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Ready, lastChunkReceivedAt = at(15, 9))
        runCurrent()
        assertIs<ArrivalState.Receiving>(vm.state.value.arrival)
        assertEquals(ModelState.Ready, vm.state.value.status.asrModel)

        vm.downloadModels(allowMetered = true)
        vm.requestTranscription()
        assertEquals(listOf(true), services.downloadRequests)
        assertEquals(1, services.transcriptionRequests)
    }

    @Test
    fun statusShowsWhyRecordingStopped() = runVmTest {
        addChunk("a", at(9), endReason = ChunkEndReason.OFF_BODY, receivedAt = at(9, 6))
        val vm = StatusViewModel(services, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(ArrivalState.Stopped(at(9, 6), ChunkEndReason.OFF_BODY), vm.state.value.arrival)
    }
}
