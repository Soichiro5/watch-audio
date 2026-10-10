package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.ProcessingStatus
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.ArrivalState
import app.watchaudio.app.logic.arrivalState
import app.watchaudio.app.logic.formatBytes
import app.watchaudio.app.logic.isDeleteAllConfirmed
import app.watchaudio.app.logic.retentionChangeNeedsConfirmation
import app.watchaudio.core.db.AudioUsage
import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.StoreStats
import app.watchaudio.core.pipeline.AudioRetention
import app.watchaudio.core.pipeline.StorageBreakdown
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val status: ProcessingStatus,
    /** null until loaded. */
    val stats: StoreStats? = null,
    val arrival: ArrivalState = ArrivalState.NoData,
    val now: Long = 0,
    /** null until loaded. */
    val storage: StorageUiState? = null,
    val storageActions: StorageActions = StorageActions(),
)

data class StorageUiState(
    val breakdown: StorageBreakdown,
    val retention: AudioRetention,
)

/** A shorter retention the user picked, waiting for confirmation, and what it deletes now. */
data class PendingRetention(val target: AudioRetention, val usage: AudioUsage)

/** Destructive storage actions on the status screen; each needs an explicit in-app confirmation. */
data class StorageActions(
    val pendingRetention: PendingRetention? = null,
    /** What the user typed so far while 「すべてのデータを削除」 is open; null while it is closed. */
    val deleteAllInput: String? = null,
    val busy: Boolean = false,
    /** Outcome of the last action. */
    val message: String? = null,
) {
    val canConfirmDeleteAll: Boolean get() = !busy && isDeleteAllConfirmed(deleteAllInput)
}

private data class StoreSnapshot(
    val stats: StoreStats,
    val lastChunk: ChunkRecord?,
    val now: Long,
    val storage: StorageUiState,
)

/** ASR model state, storage statistics and whether chunks keep arriving (#43); storage management (#33). */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModel(
    private val services: AppServices,
    private val clock: NowProvider = NowProvider.System,
    refreshPeriodMs: Long = DEFAULT_REFRESH_PERIOD_MS,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val actions = MutableStateFlow(StorageActions())

    private val snapshot = refreshSignal(services, refreshPeriodMs).conflate().mapLatest {
        val (stats, lastChunk) = services.store.read { stats() to lastChunkBefore() }
        val storage = StorageUiState(services.storage.breakdown(), services.storage.retention())
        StoreSnapshot(stats, lastChunk, clock.now(), storage)
    }

    val state: StateFlow<StatusUiState> = combine(services.status, snapshot, actions) { status, snapshot, actions ->
        // The platform may know about a chunk before (or without) the database: take the newer.
        val lastReceived = listOfNotNull(status.lastChunkReceivedAt, snapshot.stats.lastReceivedAt).maxOrNull()
        StatusUiState(
            status = status,
            stats = snapshot.stats,
            arrival = arrivalState(snapshot.lastChunk, lastReceived, snapshot.now),
            now = snapshot.now,
            storage = snapshot.storage,
            storageActions = actions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState(services.status.value))

    fun downloadModels(allowMetered: Boolean) = services.downloadModels(allowMetered)

    fun requestTranscription() = services.requestTranscription()

    /**
     * A longer retention (or the current one) applies at once. A shorter one deletes audio, so
     * it only opens a confirmation showing what would be deleted ([confirmRetention]).
     */
    fun selectRetention(target: AudioRetention) {
        if (actions.value.busy) return
        viewModelScope.launch {
            val current = services.storage.retention()
            if (retentionChangeNeedsConfirmation(current, target)) {
                val usage = services.storage.previewRetention(target)
                actions.update { it.copy(pendingRetention = PendingRetention(target, usage), deleteAllInput = null, message = null) }
            } else {
                if (target != current) services.storage.setRetention(target)
                actions.update { it.copy(pendingRetention = null, message = null) }
            }
        }
    }

    fun cancelRetention() = actions.update { it.copy(pendingRetention = null) }

    /** Stores the confirmed shorter retention and deletes the audio it no longer keeps. */
    fun confirmRetention() {
        val pending = actions.value.pendingRetention ?: return
        runDestructive {
            services.storage.setRetention(pending.target)
            val result = services.storage.applyRetention()
            Strings.audioPurged(result.purgedChunks, formatBytes(result.freedBytes))
        }
    }

    fun openDeleteAll() {
        if (actions.value.busy) return
        actions.update { it.copy(deleteAllInput = "", pendingRetention = null, message = null) }
    }

    fun updateDeleteAllInput(input: String) = actions.update { if (it.deleteAllInput == null) it else it.copy(deleteAllInput = input) }

    fun cancelDeleteAll() = actions.update { it.copy(deleteAllInput = null) }

    /**
     * Deletes every recording and transcript, but only once the typed confirmation matches.
     * @return true if the deletion started (the caller should forget anything it is playing).
     */
    fun confirmDeleteAll(): Boolean {
        if (!actions.value.canConfirmDeleteAll) return false
        services.stopPlayback()
        runDestructive {
            services.storage.deleteAll()
            Strings.DELETED_ALL
        }
        return true
    }

    private fun runDestructive(block: suspend () -> String) {
        if (actions.value.busy) return
        actions.value = StorageActions(busy = true)
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Strings.storageActionFailed(e.message ?: e::class.simpleName.orEmpty())
            }
            actions.value = StorageActions(message = message)
        }
    }
}
