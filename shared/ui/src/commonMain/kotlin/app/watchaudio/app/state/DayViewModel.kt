package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.logic.DayTimeline
import app.watchaudio.app.logic.TranscriptEntry
import app.watchaudio.app.logic.buildDayTimeline
import app.watchaudio.app.logic.buildTranscriptEntries
import app.watchaudio.app.logic.dayWindow
import app.watchaudio.app.logic.localDateOf
import kotlinx.coroutines.CoroutineScope
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
    /** Chunks shown on this day whose audio was deleted by the retention setting. */
    val audioPurgedChunks: Int = 0,
)

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

    private val content: StateFlow<DayContent?> = selected
        .flatMapLatest { date -> refreshSignal(services, refreshPeriodMs).conflate().mapLatest { load(date) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<DayUiState> = combine(selected, content, focus, playing, deletion) { date, content, focus, playing, deletion ->
        DayUiState(
            date = date,
            today = today(),
            // Keep showing the previous day while the next one loads, but never label it wrongly.
            content = content?.takeIf { it.date == date },
            focusSegmentId = focus,
            nowPlaying = playing,
            deletion = deletion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(selected.value, today()))

    fun selectDate(date: LocalDate) {
        if (deletion.value == DayDeletion.DELETING) return
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
        return DayContent(
            date = date,
            timeline = buildDayTimeline(window, chunks, previous, now),
            entries = buildTranscriptEntries(chunks, segments),
            chunksStartedThisDay = chunks.count { it.startedAt >= window.start && it.startedAt < window.end },
            audioPurgedChunks = chunks.count { it.audioPurgedAt != null },
        )
    }

    private fun today(): LocalDate = localDateOf(clock.now(), timeZone)
}
