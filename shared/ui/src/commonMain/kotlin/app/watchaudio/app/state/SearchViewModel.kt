package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.logic.SnippetPart
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatDateShort
import app.watchaudio.app.logic.parseSnippet
import app.watchaudio.core.db.SearchHit
import app.watchaudio.core.search.HybridHit
import app.watchaudio.core.search.HybridSearch
import app.watchaudio.core.search.SemanticStatus
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.datetime.TimeZone

data class SearchResult(
    val segmentId: Long,
    val chunkId: String,
    val startAt: Long,
    /** e.g. `10月10日(土) 10:05` in the device time zone. */
    val whenLabel: String,
    val parts: List<SnippetPart>,
    /** The text contains the query (keyword search). */
    val byKeyword: Boolean = true,
    /** Found by meaning (#24); a result with only this has no highlighted match. */
    val byMeaning: Boolean = false,
)

data class SearchResults(
    /** The query these results are for (lags [SearchViewModel.query] while typing). */
    val query: String,
    val results: List<SearchResult>,
    /** True when the keyword results hit the limit; there may be more. */
    val limited: Boolean,
    /** The keyword results are shown; the search by meaning is still running (#24). */
    val meaningPending: Boolean = false,
)

/**
 * Search over all transcripts; re-runs when the database changes. Keyword hits (newest first) come
 * at once; where the platform has [AppServices.semanticSearch] (#24), the results by meaning follow
 * and both are fused ([HybridSearch]). Without it, this is the keyword search alone.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val services: AppServices,
    private val timeZone: TimeZone,
    private val limit: Int = 100,
    debounceMs: Long = 250,
    scope: CoroutineScope = defaultViewModelScope(),
    /** How often the semantic search's status is re-read (download and indexing progress). */
    statusPeriodMs: Long = 5_000,
) : ViewModel(scope) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null while there is no query (show the hint instead of "no results"). */
    val results: StateFlow<SearchResults?> = _query
        .debounce { if (it.isBlank()) 0 else debounceMs }
        .flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf<SearchResults?>(null)
            } else {
                // Only data changes matter here: no periodic timer.
                refreshSignal(services, periodMs = 0).conflate().transformLatest<Unit, SearchResults?> {
                    val text = q.trim()
                    val keyword = services.store.read { search(text, limit) }
                    val semantic = services.semanticSearch
                    emit(SearchResults(text, keyword.map { it.toResult() }, keyword.size >= limit, meaningPending = semantic != null))
                    if (semantic != null) {
                        val hybrid = HybridSearch.search(services.store, semantic, text, keywordLimit = limit, keywordHits = keyword)
                        emit(SearchResults(text, hybrid.hits.map { it.toResult() }, hybrid.keywordLimited))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while [results] are for an older query than the one typed. */
    val searching: StateFlow<Boolean> = combine(_query, results) { q, r ->
        q.isNotBlank() && r?.query != q.trim()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether searching by meaning works yet (#24); null where the platform has no semantic search. */
    val semanticStatus: StateFlow<SemanticStatus?> = (
        services.semanticSearch?.let { semantic ->
            refreshSignal(services, periodMs = statusPeriodMs).conflate().mapLatest { semantic.status() }
        } ?: flowOf(null)
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        _query.value = text
    }

    private fun SearchHit.toResult() = result(segment.id, segment.chunkId, segment.startAt, snippet, byKeyword = true, byMeaning = false)

    private fun HybridHit.toResult() = result(segment.id, segment.chunkId, segment.startAt, snippet, byKeyword, byMeaning)

    private fun result(segmentId: Long, chunkId: String, start: Long, snippet: String, byKeyword: Boolean, byMeaning: Boolean) = SearchResult(
        segmentId = segmentId,
        chunkId = chunkId,
        startAt = start,
        whenLabel = "${formatDateShort(localDateOf(start, timeZone))} ${formatClock(start, timeZone)}",
        parts = parseSnippet(snippet),
        byKeyword = byKeyword,
        byMeaning = byMeaning,
    )
}
