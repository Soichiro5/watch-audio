package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.ProcessingStatus
import app.watchaudio.app.logic.ArrivalState
import app.watchaudio.app.logic.arrivalState
import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.StoreStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class StatusUiState(
    val status: ProcessingStatus,
    /** null until loaded. */
    val stats: StoreStats? = null,
    val arrival: ArrivalState = ArrivalState.NoData,
    val now: Long = 0,
)

private data class StoreSnapshot(val stats: StoreStats, val lastChunk: ChunkRecord?, val now: Long)

/** ASR model state, storage statistics and whether chunks keep arriving (#43). */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModel(
    private val services: AppServices,
    private val clock: NowProvider = NowProvider.System,
    refreshPeriodMs: Long = DEFAULT_REFRESH_PERIOD_MS,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val snapshot = refreshSignal(services, refreshPeriodMs).conflate().mapLatest {
        val (stats, lastChunk) = services.store.read { stats() to lastChunkBefore() }
        StoreSnapshot(stats, lastChunk, clock.now())
    }

    val state: StateFlow<StatusUiState> = combine(services.status, snapshot) { status, snapshot ->
        // The platform may know about a chunk before (or without) the database: take the newer.
        val lastReceived = listOfNotNull(status.lastChunkReceivedAt, snapshot.stats.lastReceivedAt).maxOrNull()
        StatusUiState(
            status = status,
            stats = snapshot.stats,
            arrival = arrivalState(snapshot.lastChunk, lastReceived, snapshot.now),
            now = snapshot.now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState(services.status.value))

    fun downloadModel(allowMetered: Boolean) = services.downloadModel(allowMetered)

    fun requestTranscription() = services.requestTranscription()
}
