package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.logic.TranscriptEntry
import app.watchaudio.app.logic.buildTranscriptEntries
import app.watchaudio.core.db.HighlightRecord
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.summary.HourAction
import app.watchaudio.core.summary.HourPlan
import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.SummaryProgress
import app.watchaudio.core.summary.SummaryRun
import app.watchaudio.core.summary.SummaryRunResult
import app.watchaudio.core.summary.planDay
import app.watchaudio.core.timeline.DayTimeline
import app.watchaudio.core.timeline.buildDayTimeline
import app.watchaudio.core.timeline.dayWindow
import app.watchaudio.core.timeline.localDateOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** What the day screen shows. [content] is null until the selected day has loaded once. */
data class DayUiState(
    val date: LocalDate,
    val today: LocalDate,
    val content: DayContent? = null,
    /** Segment to scroll to (set when opening a search hit). */
    val focusSegmentId: Long? = null,
    val nowPlaying: NowPlaying? = null,
    /** Progress of 「この日のデータを削除」, which needs an explicit confirmation. */
    val deletion: DayDeletion = DayDeletion.NONE,
    /** 「この日を要約」 (#27) while it runs on this day, and what the last run had to say. */
    val summaryRun: SummaryRunState? = null,
    val summaryNotice: SummaryNotice? = null,
) {
    val isToday: Boolean get() = date == today
    val canGoForward: Boolean get() = date < today
}

data class DayContent(
    val date: LocalDate,
    val timeline: DayTimeline,
    val entries: List<TranscriptEntry>,
    /** Chunks that started on this day: what 「この日のデータを削除」 removes. */
    val chunksStartedThisDay: Int = 0,
    /** Chunks shown on this day whose audio was deleted by the retention setting (and is in no backup). */
    val audioPurgedChunks: Int = 0,
    /** Chunks shown on this day whose audio is only in a backup (#156). */
    val audioInBackupChunks: Int = 0,
    /** Creation time of the newest backup holding that audio, or null. */
    val audioBackupAt: Long? = null,
    /** Hour summaries (#27); null when nothing was said on the day. */
    val summaries: DaySummaries? = null,
)

/** The day's hours with speech or a summary, and whether the on-device LLM can make summaries. */
data class DaySummaries(
    val availability: LlmAvailability,
    /** Hours with anything to show ([HourAction.NOTHING] left out), oldest first. */
    val hours: List<HourPlan>,
) {
    /** Hours 「この日を要約」 would send to the LLM. */
    val toSummarize: Int get() = hours.count { it.action == HourAction.SUMMARIZE }
}

/** A run of hour summaries on [date]: [progress] counts the hours that need the LLM. */
data class SummaryRunState(val date: LocalDate, val progress: SummaryProgress)

/** What the last summary run could not do (the hours keep their own states). */
enum class SummaryNotice {
    /** The system asked to slow down; the rest of the day is left. */
    RATE_LIMITED,

    /** Something unexpected stopped the run (the error type is in the diagnostics log). */
    FAILED,
}

enum class DayDeletion {
    NONE,

    /** The user asked to delete the day; waiting for them to confirm or cancel. */
    CONFIRMING,
    DELETING,
}

/** What was last sent to [AppServices.play]; the platform does not report when it ends. */
data class NowPlaying(val audioPath: String, val offsetMs: Long, val startsAt: Long)

/**
 * State of the day (timeline) screen: which day is selected, its coverage/gaps and its
 * transcript, re-queried by [refreshSignal].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(
    private val services: AppServices,
    private val timeZone: TimeZone,
    private val clock: NowProvider = NowProvider.System,
    refreshPeriodMs: Long = DEFAULT_REFRESH_PERIOD_MS,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val selected = MutableStateFlow(today())
    private val focus = MutableStateFlow<Long?>(null)
    private val playing = MutableStateFlow<NowPlaying?>(null)
    private val deletion = MutableStateFlow(DayDeletion.NONE)
    private val summaryRun = MutableStateFlow<SummaryRunState?>(null)
    private val summaryNotice = MutableStateFlow<SummaryNotice?>(null)
    private var summaryJob: Job? = null

    private val content: StateFlow<DayContent?> = selected
        .flatMapLatest { date -> refreshSignal(services, refreshPeriodMs).conflate().mapLatest { load(date) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val summaryState = combine(summaryRun, summaryNotice) { run, notice -> run to notice }

    val state: StateFlow<DayUiState> = combine(
        combine(selected, content, focus) { date, content, focus -> Triple(date, content, focus) },
        playing,
        deletion,
        summaryState,
    ) { (date, content, focus), playing, deletion, (run, notice) ->
        DayUiState(
            date = date,
            today = today(),
            // Keep showing the previous day while the next one loads, but never label it wrongly.
            content = content?.takeIf { it.date == date },
            focusSegmentId = focus,
            nowPlaying = playing,
            deletion = deletion,
            summaryRun = run?.takeIf { it.date == date },
            summaryNotice = notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(selected.value, today()))

    fun selectDate(date: LocalDate) {
        if (deletion.value == DayDeletion.DELETING) return
        if (minOf(date, today()) != selected.value) stopSummaries()
        selected.value = minOf(date, today())
        focus.value = null
        deletion.value = DayDeletion.NONE
    }

    fun previousDay() = selectDate(selected.value.minus(1, DateTimeUnit.DAY))

    fun nextDay() = selectDate(selected.value.plus(1, DateTimeUnit.DAY))

    fun goToToday() = selectDate(today())

    fun play(entry: TranscriptEntry.Utterance) {
        val path = entry.audioPath ?: return
        startPlayback(NowPlaying(path, entry.offsetMs, entry.segment.startAt))
    }

    /** Jumps to the day of a segment (e.g. a search hit), scrolls to it and plays it. */
    fun openSegment(chunkId: String, segmentId: Long, startAt: Long) {
        // Focus first, so no intermediate state shows the new day without it.
        focus.value = segmentId
        selected.value = localDateOf(startAt, timeZone)
        viewModelScope.launch {
            val chunk = services.store.read { chunk(chunkId) }?.takeIf { it.hasAudioFile } ?: return@launch
            val path = chunk.audioPath ?: return@launch
            startPlayback(NowPlaying(path, (startAt - chunk.startedAt).coerceAtLeast(0), startAt))
        }
    }

    /** Call once the list has scrolled to [DayUiState.focusSegmentId]. */
    fun focusHandled() {
        focus.value = null
    }

    fun stopPlayback() {
        services.stopPlayback()
        playing.value = null
    }

    /** Re-queues the group's failed chunks (fresh attempt budget) and asks for a run. */
    fun transcribe(entry: TranscriptEntry.Untranscribed) {
        viewModelScope.launch {
            if (entry.kind == TranscriptEntry.Untranscribed.Kind.FAILED) {
                services.store.transaction { requeueFailed(entry.chunkIds) }
            }
            services.requestTranscription()
        }
    }

    /** First step of 「この日のデータを削除」: shows the confirmation. */
    fun requestDeleteDay() {
        if (deletion.value == DayDeletion.NONE) deletion.value = DayDeletion.CONFIRMING
    }

    fun cancelDeleteDay() {
        if (deletion.value == DayDeletion.CONFIRMING) deletion.value = DayDeletion.NONE
    }

    /**
     * Deletes the chunks that started on the selected day (audio, transcripts, marks). Does
     * nothing unless [requestDeleteDay] was confirmed first.
     */
    fun confirmDeleteDay() {
        if (deletion.value != DayDeletion.CONFIRMING) return
        deletion.value = DayDeletion.DELETING
        val window = dayWindow(selected.value, timeZone)
        playing.value?.takeIf { it.startsAt >= window.start && it.startsAt < window.end }?.let { stopPlayback() }
        viewModelScope.launch {
            try {
                services.storage.deleteRange(window.start, window.end)
            } finally {
                deletion.value = DayDeletion.NONE
            }
        }
    }

    /**
     * 「この日を要約」 (#27): makes the summaries of the selected day's hours that need one, one hour
     * at a time, while the screen is open ([stopSummaries] when it closes or the day changes;
     * finished hours are kept). Only from the user's tap.
     */
    fun summarizeDay() = startSummaries(null)

    /** 「もう一度」 for one hour whose summary failed. */
    fun retrySummary(hourStart: Long) = startSummaries(setOf(hourStart))

    /** Stops a running summary run (the screen closed, the day changed, or 「中止」). */
    fun stopSummaries() {
        summaryJob?.cancel()
        summaryJob = null
        summaryRun.value = null
    }

    /** A highlight's citation: scrolls to its first segment and plays it. */
    fun openHighlight(highlight: HighlightRecord) {
        val first = highlight.segmentIds.firstOrNull() ?: return
        viewModelScope.launch {
            val segment = services.store.read { segment(first) } ?: return@launch
            openSegment(segment.chunkId, segment.id, segment.startAt)
        }
    }

    private fun startSummaries(onlyHours: Set<Long>?) {
        if (summaryJob?.isActive == true) return
        val date = selected.value
        val window = dayWindow(date, timeZone)
        summaryNotice.value = null
        summaryRun.value = SummaryRunState(date, SummaryProgress(0, 0, null))
        summaryJob = viewModelScope.launch {
            val self = coroutineContext.job
            try {
                val run = SummaryRun(services.store, services.llm, timeZone, clock::now, services.diagnostics)
                val result = run.run(window, onlyHours) { progress -> summaryRun.value = SummaryRunState(date, progress) }
                if (result is SummaryRunResult.RateLimited) summaryNotice.value = SummaryNotice.RATE_LIMITED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never the message: it may hold transcript text.
                services.diagnostics.log(DiagnosticEvents.SUMMARY_FAILED, "type=${e::class.simpleName}")
                summaryNotice.value = SummaryNotice.FAILED
            } finally {
                // A newer run (after 「中止」 and a new tap) owns the state now.
                if (summaryJob === self) {
                    summaryJob = null
                    summaryRun.value = null
                }
            }
        }
    }

    private fun startPlayback(request: NowPlaying) {
        services.play(request.audioPath, request.offsetMs)
        playing.value = request
    }

    private suspend fun load(date: LocalDate): DayContent {
        val window = dayWindow(date, timeZone)
        val now = clock.now()
        val (chunks, previous, segments) = services.store.read {
            Triple(
                chunksBetween(window.start, window.end),
                lastChunkBefore(window.start),
                segmentsBetween(window.start, window.end),
            )
        }
        val summaries = services.store.read { hourSummariesBetween(window.start, window.end) }
        val hours = planDay(window, chunks, segments, summaries, now).filter { it.action != HourAction.NOTHING }
        return DayContent(
            date = date,
            timeline = buildDayTimeline(window, chunks, previous, now),
            entries = buildTranscriptEntries(chunks, segments),
            chunksStartedThisDay = chunks.count { it.startedAt >= window.start && it.startedAt < window.end },
            audioPurgedChunks = chunks.count { it.audioPurgedAt != null && !it.isAudioOnlyInBackup },
            audioInBackupChunks = chunks.count { it.isAudioOnlyInBackup },
            audioBackupAt = chunks.filter { it.isAudioOnlyInBackup }.mapNotNull { it.audioBackedUpAt }.maxOrNull(),
            summaries = hours.takeIf { it.isNotEmpty() }?.let { DaySummaries(llmAvailability(), it) },
        )
    }

    /** The LLM's availability; a platform error counts as unsupported rather than breaking the day. */
    private suspend fun llmAvailability(): LlmAvailability = try {
        services.llm.availability()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LlmAvailability.Unsupported("availability_error")
    }

    private fun today(): LocalDate = localDateOf(clock.now(), timeZone)
}
