package app.watchaudio.android.agent

import android.annotation.SuppressLint
import android.net.InetAddresses
import app.watchaudio.core.agent.AgentConnection
import app.watchaudio.core.agent.AgentMessage
import app.watchaudio.core.agent.AgentTransport
import app.watchaudio.core.agent.AgentWire
import app.watchaudio.core.agent.BridgeAdvertisement
import app.watchaudio.core.agent.isLanHost
import app.watchaudio.core.agent.keyFingerprint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone's only network connection besides model downloads (#184, tools/privacy-check): mutual
 * TLS 1.3 from the phone to the paired PC's bridge on the local network, while the user has agent
 * access on. The phone presents its Keystore key ([AgentIdentity]) and accepts only the bridge key
 * it paired with (pinned by fingerprint; no certificate authorities, no host names). Addresses
 * must be LAN IP literals ([isLanHost]); they are parsed without DNS. The phone never listens.
 */
class TlsAgentTransport(
    private val identity: AgentIdentity,
    override val phoneName: String,
    private val discovery: () -> Flow<BridgeAdvertisement>,
) : AgentTransport {
    override val phoneFingerprint: String get() = identity.fingerprint

    override fun discover(): Flow<BridgeAdvertisement> = discovery()

    override suspend fun connect(host: String, port: Int, fingerprint: String): AgentConnection = withContext(Dispatchers.IO) {
        if (!isLanHost(host)) throw IOException("not a local network address")
        val address = InetAddresses.parseNumericAddress(host.removePrefix("[").removeSuffix("]"))
        val context = SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(KeystoreKeyManager(identity)), arrayOf(PinningTrustManager(fingerprint)), null)
        }
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = arrayOf(TLS_1_3)
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            if (socket.session.protocol != TLS_1_3) throw IOException("bridge did not negotiate TLS 1.3")
            socket.soTimeout = 0
            TlsConnection(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private companion object {
        const val TLS_1_3 = "TLSv1.3"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}

/** Frames of [AgentWire] over the TLS connection; writes are serialized. */
private class TlsConnection(private val socket: SSLSocket) : AgentConnection {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = BufferedOutputStream(socket.outputStream)
    private val writeLock = Mutex()

    override suspend fun receive(): AgentMessage? = withContext(Dispatchers.IO) {
        try {
            val size = input.readInt()
            if (size !in 0..AgentWire.MAX_FRAME_BYTES) throw IOException("frame of $size bytes")
            val body = ByteArray(size)
            input.readFully(body)
            AgentWire.decode(body.decodeToString())
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun send(message: AgentMessage) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            output.write(AgentWire.frame(message))
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** Presents the Keystore key and its certificate for every client handshake. */
private class KeystoreKeyManager(private val identity: AgentIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = AgentIdentity.KEY_ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = AgentIdentity.KEY_ALIAS

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(AgentIdentity.KEY_ALIAS)

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)

    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}

/**
 * Accepts only a server whose key has [fingerprint]; refuses to act as a server's trust manager.
 * Custom on purpose (lint): the bridge's certificate is self-signed and pinned by key, which is
 * stricter than the system CAs, not a disabled check.
 */
@SuppressLint("CustomX509TrustManager")
private class PinningTrustManager(private val fingerprint: String) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.firstOrNull()?.publicKey?.encoded?.let(::keyFingerprint)
        if (presented != fingerprint) throw CertificateException("the bridge presented a key other than the paired one")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client role only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client role only")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client role only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
