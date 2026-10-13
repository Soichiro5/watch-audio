package app.watchaudio.fakephone

import app.watchaudio.core.agent.AgentChannel
import app.watchaudio.core.agent.AgentMessage
import app.watchaudio.core.agent.AgentWire
import app.watchaudio.core.agent.SelfSignedCertificate
import app.watchaudio.core.agent.keyFingerprint
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's key and self-signed certificate, kept in a directory (Android keeps them in its Keystore). */
class PhoneIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String get() = keyFingerprint(certificate.publicKey.encoded)

    companion object {
        fun loadOrCreate(dir: File): PhoneIdentity {
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            if (keyFile.exists() && certFile.exists()) {
                val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream()) as X509Certificate
                return PhoneIdentity(key, cert)
            }
            dir.mkdirs()
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val der = SelfSignedCertificate.build(keys.public.encoded, "watch-audio phone", serial) { tbs ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keys.private)
                    update(tbs)
                    sign()
                }
            }
            keyFile.writeBytes(keys.private.encoded)
            certFile.writeBytes(der)
            return loadOrCreate(dir)
        }
    }
}

/** The bridge presented a key other than the pinned one. */
class PinMismatchException(message: String) : CertificateException(message)

/**
 * Mutual TLS 1.3 to the bridge at [host]:[port]: presents [identity] and accepts only a server
 * key whose fingerprint is [bridgeFingerprint] (no certificate authorities involved).
 */
fun connectToBridge(host: String, port: Int, identity: PhoneIdentity, bridgeFingerprint: String, timeoutMs: Int = 10_000): SSLSocket {
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry("phone", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, CharArray(0)) }.keyManagers
    val pinning = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("client role only")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val presented = chain.firstOrNull()?.publicKey?.encoded?.let(::keyFingerprint)
            if (presented != bridgeFingerprint) throw PinMismatchException("bridge key $presented is not the paired $bridgeFingerprint")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLSv1.3").apply { init(keyManagers, arrayOf(pinning), SecureRandom()) }
    val socket = context.socketFactory.createSocket() as SSLSocket
    socket.enabledProtocols = arrayOf("TLSv1.3")
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    socket.soTimeout = 0
    socket.startHandshake()
    return socket
}

/** [AgentChannel] over a connected socket: length-prefixed JSON frames. */
class SocketChannel(private val socket: SSLSocket) : AgentChannel {
    private val input = DataInputStream(socket.inputStream.buffered())
    private val output = socket.outputStream

    override suspend fun receive(): AgentMessage? = withContext(Dispatchers.IO) {
        try {
            val size = input.readInt()
            require(size in 0..AgentWire.MAX_FRAME_BYTES) { "frame of $size bytes" }
            val body = ByteArray(size)
            input.readFully(body)
            AgentWire.decode(body.decodeToString())
        } catch (e: EOFException) {
            null
        } catch (e: java.net.SocketException) {
            null
        }
    }

    override suspend fun send(message: AgentMessage) = withContext(Dispatchers.IO) {
        output.write(AgentWire.frame(message))
        output.flush()
    }

    fun close() = socket.close()
}
