package app.watchaudio.core.agent

import app.watchaudio.core.db.AgentPairing
import app.watchaudio.core.db.LocalStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A bridge seen on the local network: the TXT values `n` and `h` of its mDNS record and where it listens. */
data class BridgeAdvertisement(val nonce: String, val hash: String, val host: String, val port: Int)

/**
 * One mutual TLS connection the platform opened to a bridge. [send] may be called while another
 * coroutine waits in [receive]; implementations serialize their writes.
 */
interface AgentConnection : AgentChannel {
    /** Closes the connection; a pending [receive] returns null. Safe to call more than once. */
    fun close()
}

/**
 * How the platform reaches the PC's bridge (#184, #185): mDNS discovery of
 * `_watchaudio-mcp._tcp` and mutual TLS 1.3 with the phone's key, pinning the bridge's key. The
 * phone only ever connects out, and only to LAN addresses ([isLanHost]); it never listens.
 */
interface AgentTransport {
    /** [keyFingerprint] of the key the phone presents. */
    val phoneFingerprint: String

    /** The name the phone gives itself while pairing (shown on the PC). */
    val phoneName: String

    /** Bridges advertised on the local network while collected (repeats are fine). */
    fun discover(): Flow<BridgeAdvertisement>

    /**
     * Opens mutual TLS 1.3 to [host]:[port] and completes the handshake, accepting only a bridge
     * whose key is [fingerprint].
     * @throws Exception if it is unreachable or presents another key.
     */
    suspend fun connect(host: String, port: Int, fingerprint: String): AgentConnection
}

/** Where a pairing's connection stands while agent access is on. */
enum class LinkState {
    /** Looking for the PC (mDNS, then the last known address) or waiting to retry. */
    SEARCHING,

    /** Connected: the PC's agent can call tools. */
    CONNECTED,
}

/**
 * The phone's side of agent access while the user has it on (#184): keeps one connection per
 * active pairing, found by mDNS ([advertisementMatches]) or at its last known address, serves it
 * with [AgentLink.serve] and reconnects when the bridge goes away (the agent quit, the PC slept).
 * A pairing that is revoked is dropped at once. Cancelling [run] closes every connection (with
 * [Bye] [ByeReason.ACCESS_OFF]).
 *
 * @param allowed checked before every message from the bridge; once false the connection ends
 *   (the auto-off deadline, even if the platform's timer is late).
 */
class AgentConnector(
    private val store: LocalStore,
    private val service: AgentService,
    private val transport: AgentTransport,
    private val software: String,
    private val allowed: () -> Boolean = { true },
    private val minRetryMs: Long = 2_000,
    private val maxRetryMs: Long = 30_000,
) {
    private val _links = MutableStateFlow<Map<Long, LinkState>>(emptyMap())

    /** Active pairings by id and their connection state; empty while not running. */
    val links: StateFlow<Map<Long, LinkState>> = _links.asStateFlow()

    private val advertisements = MutableStateFlow<List<BridgeAdvertisement>>(emptyList())

    /** Runs until cancelled. */
    suspend fun run() {
        try {
            coroutineScope {
                launch {
                    try {
                        transport.discover().collect { ad ->
                            advertisements.update { seen -> (listOf(ad) + seen.filterNot { it.host == ad.host && it.port == ad.port }).take(MAX_ADVERTISEMENTS) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // No mDNS on this network: the last known addresses still work.
                    }
                }
                val jobs = HashMap<Long, Job>()
                store.changes.collect {
                    val active = store.read { agentPairings() }.filter { it.isActive }.associateBy { it.id }
                    (jobs.keys - active.keys).forEach { id -> jobs.remove(id)?.cancel(RevokedCancellation()) }
                    _links.update { links -> links.filterKeys { it in active } }
                    active.values.filter { it.id !in jobs }.forEach { pairing -> jobs[pairing.id] = launch { keepConnected(pairing) } }
                }
            }
        } finally {
            _links.value = emptyMap()
            advertisements.value = emptyList()
        }
    }

    private suspend fun keepConnected(pairing: AgentPairing) {
        var retry = minRetryMs
        setLink(pairing.id, LinkState.SEARCHING)
        try {
            while (allowed()) {
                val served = tryCandidates(pairing)
                if (served) retry = minRetryMs
                // Retry after a pause, or at once when a matching advertisement appears.
                withTimeoutOrNull(retry) { advertisements.drop(1).first { ads -> ads.any { it.belongsTo(pairing) } } }
                retry = (retry * 2).coerceAtMost(maxRetryMs)
            }
        } finally {
            _links.update { it - pairing.id }
        }
    }

    /** @return true if a connection was made (and has ended since). */
    private suspend fun tryCandidates(pairing: AgentPairing): Boolean {
        for ((host, port) in candidates(pairing)) {
            if (!allowed()) return false
            val connection = try {
                transport.connect(host, port, pairing.keyFingerprint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val address = if (':' in host) "[$host]:$port" else "$host:$port"
            if (address != pairing.address) store.transaction { setAgentAddress(pairing.id, address) }
            serve(pairing, connection)
            return true
        }
        return false
    }

    /** Addresses to try: advertisements of this pairing's bridge (newest first), then the last known one. */
    internal fun candidates(pairing: AgentPairing): List<Pair<String, Int>> {
        val advertised = advertisements.value.filter { it.belongsTo(pairing) }.map { it.host to it.port }
        val known = pairing.address?.let(::splitHostPort)
        return (advertised + listOfNotNull(known)).distinct().filter { isLanHost(it.first) }
    }

    private fun BridgeAdvertisement.belongsTo(pairing: AgentPairing) = advertisementMatches(nonce, hash, pairing.keyFingerprint)

    private suspend fun serve(pairing: AgentPairing, connection: AgentConnection) {
        setLink(pairing.id, LinkState.CONNECTED)
        try {
            coroutineScope {
                // Closing the connection is what ends a receive() blocked in the platform's read.
                val closer = launch {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        val reason = if (e is RevokedCancellation || e.cause is RevokedCancellation) ByeReason.REVOKED else ByeReason.ACCESS_OFF
                        withContext(NonCancellable) {
                            withTimeoutOrNull(BYE_TIMEOUT_MS) { runCatching { connection.send(Bye(reason)) } }
                            connection.close()
                        }
                        throw e
                    }
                }
                try {
                    AgentLink.serve(connection.whileAllowed(), service, pairing, transport.phoneName, software)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A broken connection or frame: reconnect.
                } finally {
                    closer.cancel()
                }
            }
        } finally {
            connection.close()
            if (currentCoroutineContext().isActive) setLink(pairing.id, LinkState.SEARCHING)
        }
    }

    /** Messages stop (the connection ends) once [allowed] turns false. */
    private fun AgentConnection.whileAllowed(): AgentChannel {
        val inner = this
        return object : AgentChannel {
            override suspend fun receive(): AgentMessage? {
                if (!allowed()) return null
                val message = inner.receive()
                return if (allowed()) message else null
            }

            override suspend fun send(message: AgentMessage) = inner.send(message)
        }
    }

    private fun setLink(id: Long, state: LinkState) {
        _links.update { it + (id to state) }
    }

    /** Cancels a pairing's job because the user revoked it. */
    private class RevokedCancellation : CancellationException("pairing revoked")

    private companion object {
        const val MAX_ADVERTISEMENTS = 16
        const val BYE_TIMEOUT_MS = 1_000L
    }
}

/** Why pairing could not start: none of the QR's addresses answered. */
class BridgeUnreachableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pairs with the bridge of [uri] over [transport] (#184): tries the QR's addresses in order (LAN
 * addresses only, [isLanHost]), pinning the bridge's key, and runs [AgentLink.pair] on the first
 * that answers.
 * @throws BridgeUnreachableException if no address answered.
 */
suspend fun pairOver(
    transport: AgentTransport,
    store: LocalStore,
    uri: PairingUri,
    now: () -> Long,
    confirm: suspend (code: String) -> Boolean,
): PairOutcome {
    val addresses = uri.addresses.mapNotNull(::splitHostPort).filter { isLanHost(it.first) }
    if (addresses.isEmpty()) throw BridgeUnreachableException("no LAN address in the pairing URI")
    var failure: Exception? = null
    for ((host, port) in addresses) {
        val connection = try {
            transport.connect(host, port, uri.fingerprint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
            continue
        }
        try {
            return AgentLink.pair(connection, store, uri, host, transport.phoneName, transport.phoneFingerprint, now, confirm)
        } finally {
            connection.close()
        }
    }
    throw BridgeUnreachableException("no address of the pairing URI answered", failure)
}

/** Deletes access log entries older than [AgentLimits.ACCESS_LOG_RETENTION_DAYS] (#184). @return how many. */
suspend fun LocalStore.pruneAgentAccessLog(now: Long): Int =
    transaction { deleteAgentAccessBefore(now - AgentLimits.ACCESS_LOG_RETENTION_DAYS * DAY_MS) }

private const val DAY_MS = 86_400_000L

/** Starts [AgentConnector.run] in [scope]; a convenience for platforms. */
fun AgentConnector.start(scope: CoroutineScope): Job = scope.launch { run() }
