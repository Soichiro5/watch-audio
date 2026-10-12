package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.logic.SnippetPart
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatDateShort
import app.watchaudio.app.logic.parseSnippet
import app.watchaudio.core.timeline.localDateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone

data class SearchResult(
    val segmentId: Long,
    val chunkId: String,
    val startAt: Long,
    /** e.g. `10月10日(土) 10:05` in the device time zone. */
    val whenLabel: String,
    val parts: List<SnippetPart>,
)

data class SearchResults(
    /** The query these results are for (lags [SearchViewModel.query] while typing). */
    val query: String,
    val results: List<SearchResult>,
    /** True when the result count hit the limit; there may be more. */
    val limited: Boolean,
)

/** Full-text search over all transcripts, newest first; re-runs when the database changes. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val services: AppServices,
    private val timeZone: TimeZone,
    private val limit: Int = 100,
    debounceMs: Long = 250,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null while there is no query (show the hint instead of "no results"). */
    val results: StateFlow<SearchResults?> = _query
        .debounce { if (it.isBlank()) 0 else debounceMs }
        .flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(null)
            } else {
                // Only data changes matter here: no periodic timer.
                refreshSignal(services, periodMs = 0).conflate().mapLatest { search(q) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while [results] are for an older query than the one typed. */
    val searching: StateFlow<Boolean> = combine(_query, results) { q, r ->
        q.isNotBlank() && r?.query != q.trim()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setQuery(text: String) {
        _query.value = text
    }

    private suspend fun search(raw: String): SearchResults {
        val q = raw.trim()
        val hits = services.store.read { search(q, limit) }
        return SearchResults(
            query = q,
            results = hits.map { hit ->
                val start = hit.segment.startAt
                SearchResult(
                    segmentId = hit.segment.id,
                    chunkId = hit.segment.chunkId,
                    startAt = start,
                    whenLabel = "${formatDateShort(localDateOf(start, timeZone))} ${formatClock(start, timeZone)}",
                    parts = parseSnippet(hit.snippet),
                )
            },
            limited = hits.size >= limit,
        )
    }
}
