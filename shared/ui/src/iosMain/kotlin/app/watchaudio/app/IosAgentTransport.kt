package app.watchaudio.app

import app.watchaudio.core.agent.AgentConnection
import app.watchaudio.core.agent.AgentMessage
import app.watchaudio.core.agent.AgentTransport
import app.watchaudio.core.agent.AgentWire
import app.watchaudio.core.agent.BridgeAdvertisement
import app.watchaudio.core.agent.SelfSignedCertificate
import app.watchaudio.core.agent.isLanHost
import app.watchaudio.core.agent.keyFingerprint
import app.watchaudio.core.platform.IoDispatcher
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/** Thrown when the system refused the local network (iOS's Local Network privacy setting). */
class LocalNetworkDeniedException(message: String) : Exception(message)

/**
 * The phone's only network connection on iOS (#185, tools/privacy-check): mutual TLS 1.3 from the
 * phone to the paired PC's bridge on the local network, while the user has agent access on and the
 * app is on screen. Network.framework and the Keychain are reached through [platform] (Swift); this
 * class keeps the rules: LAN IP literals only ([isLanHost], no DNS), the bridge's key pinned by
 * fingerprint, the phone's key never leaving the Keychain. The phone never listens.
 *
 * Create it with [IosAgentTransport.load] (off the main thread: Keychain).
 */
class IosAgentTransport private constructor(
    private val platform: IosAgentPlatform,
    override val phoneFingerprint: String,
    override val phoneName: String,
) : AgentTransport {
    /** Set when a connection failed because the system refused the local network. */
    var onLocalNetworkDenied: () -> Unit = {}

    override fun discover(): Flow<BridgeAdvertisement> = callbackFlow {
        val handle = platform.browse(
            onFound = { found -> trySend(BridgeAdvertisement(found.nonce, found.keyHash, found.host, found.port)) },
            onFailed = { reason -> close(IllegalStateException("mDNS browsing stopped: $reason")) },
        )
        awaitClose { handle.cancel() }
    }

    override suspend fun connect(host: String, port: Int, fingerprint: String): AgentConnection {
        if (!isLanHost(host)) throw IllegalArgumentException("not a local network address")
        val literal = host.removePrefix("[").removeSuffix("]")
        val result = suspendCancellableCoroutine { continuation ->
            platform.connect(literal, port, fingerprint, CONNECT_TIMEOUT_MS) { result ->
                if (continuation.isActive) continuation.resume(result) else result.connection?.close()
            }
        }
        val connection = result.connection
        if (connection == null) {
            if (result.localNetworkDenied) {
                onLocalNetworkDenied()
                throw LocalNetworkDeniedException(result.error ?: "local network refused")
            }
            throw IllegalStateException(result.error ?: "connection failed")
        }
        return IosAgentConnection(connection)
    }

    companion object {
        /** Network.framework's TCP connect and the TLS handshake together. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        private const val COMMON_NAME = "watch-audio phone"

        /**
         * Loads the Keychain key (creating it on first use) and makes sure its self-signed
         * certificate ([SelfSignedCertificate], the shape the bridge expects) is stored next to it.
         * @throws IllegalStateException if the Keychain refused.
         */
        suspend fun load(platform: IosAgentPlatform, phoneName: String): IosAgentTransport = withContext(IoDispatcher) {
            val key = platform.loadKey()
            val spki = key.subjectPublicKeyInfo?.toByteArray() ?: error("Keychain key: ${key.error}")
            val stored = platform.storedCertificate()?.toByteArray()
            if (stored == null || !stored.contains(spki)) {
                val serial = Random.nextBytes(16)
                val der = SelfSignedCertificate.build(spki, COMMON_NAME, serial) { tbs ->
                    platform.sign(tbs.toNSData())?.toByteArray() ?: error("Keychain signing failed")
                }
                platform.installCertificate(der.toNSData())?.let { error("Keychain certificate: $it") }
            }
            IosAgentTransport(platform, keyFingerprint(spki), phoneName)
        }

        /** Whether [part] occurs in this array (the certificate carries the key's SubjectPublicKeyInfo verbatim). */
        private fun ByteArray.contains(part: ByteArray): Boolean {
            if (part.isEmpty() || part.size > size) return false
            outer@ for (start in 0..size - part.size) {
                for (i in part.indices) if (this[start + i] != part[i]) continue@outer
                return true
            }
            return false
        }
    }
}

/** Frames of [AgentWire] over one [IosAgentLink]; writes are serialized. */
private class IosAgentConnection(private val link: IosAgentLink) : AgentConnection {
    private val writeLock = Mutex()

    override suspend fun receive(): AgentMessage? {
        val header = read(4) ?: return null
        val size = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (size !in 0..AgentWire.MAX_FRAME_BYTES) return null
        val body = if (size == 0) ByteArray(0) else read(size) ?: return null
        return try {
            AgentWire.decode(body.decodeToString())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun read(length: Int): ByteArray? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { link.close() }
        link.receive(length) { data -> if (continuation.isActive) continuation.resume(data?.toByteArray()?.takeIf { it.size == length }) }
    }

    override suspend fun send(message: AgentMessage) = writeLock.withLock {
        val frame = AgentWire.frame(message).toNSData()
        val error = suspendCancellableCoroutine { continuation ->
            link.send(frame) { error -> if (continuation.isActive) continuation.resume(error) }
        }
        if (error != null) throw IllegalStateException("send failed: $error")
    }

    override fun close() = link.close()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}
