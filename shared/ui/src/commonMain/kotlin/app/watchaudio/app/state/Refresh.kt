package app.watchaudio.app.state

import app.watchaudio.app.AppServices
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Wall clock in epoch ms; injectable for tests. */
fun interface NowProvider {
    fun now(): Long

    companion object {
        val System = NowProvider { Clock.System.now().toEpochMilliseconds() }
    }
}

/** How often screens re-query even without a known change ("now" moves, other writers). */
const val DEFAULT_REFRESH_PERIOD_MS = 20_000L

/**
 * Emits once immediately and then whenever the screen should re-query the database: after a
 * committed write through [AppServices.store], when the platform status changes, and every
 * [periodMs] as a fallback ([periodMs] <= 0 disables the timer).
 */
fun refreshSignal(services: AppServices, periodMs: Long = DEFAULT_REFRESH_PERIOD_MS): Flow<Unit> = merge(
    flow { emit(Unit) },
    services.store.changes.drop(1).map { },
    services.status.map { it.lastChunkReceivedAt to it.transcribing }.distinctUntilChanged().drop(1).map { },
    flow {
        if (periodMs <= 0) return@flow
        while (true) {
            delay(periodMs)
            emit(Unit)
        }
    },
)

/** Scope for a ViewModel created without an explicit one (main thread, cancelled on clear). */
internal fun defaultViewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
