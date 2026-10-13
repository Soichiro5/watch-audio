package app.watchaudio.app

import app.watchaudio.core.agent.Bye
import app.watchaudio.core.agent.ByeReason
import app.watchaudio.core.agent.keyFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData

/**
 * The Kotlin side of agent access on iOS (#185) over a fake Swift platform: the certificate is made
 * once for the Keychain key, only LAN IP literals reach the platform, a refused local network is
 * reported, and frames survive a round trip through the byte pipe. The real Network.framework and
 * Keychain are checked in the app-hosted `AgentAccessTests` against the bridge CLI.
 */
class IosAgentTransportTest {
    /** A P-256 SubjectPublicKeyInfo shape (the bytes need not be a real point here). */
    private val spki = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ) + ByteArray(65) { if (it == 0) 0x04 else it.toByte() }

    @Test
    fun certificateIsMadeOnceForTheKeychainKey() = runTest {
        val platform = FakePlatform(spki)
        val transport = IosAgentTransport.load(platform, "iPhone")
        assertEquals(keyFingerprint(spki), transport.phoneFingerprint)
        assertEquals("iPhone", transport.phoneName)
        assertEquals(1, platform.installed.size)
        assertEquals(1, platform.signed)
        assertTrue(platform.installed.single().toByteArray().containsAll(spki))
        // The stored certificate carries the key: nothing is made again.
        IosAgentTransport.load(platform, "iPhone")
        assertEquals(1, platform.installed.size)
    }

    @Test
    fun onlyLanLiteralsReachThePlatform() = runTest {
        val platform = FakePlatform(spki)
        val transport = IosAgentTransport.load(platform, "iPhone")
        assertFailsWith<IllegalArgumentException> { transport.connect("8.8.8.8", 47823, "fp") }
        assertFailsWith<IllegalArgumentException> { transport.connect("pc.local", 47823, "fp") }
        assertTrue(platform.connects.isEmpty())
        platform.answer = { IosAgentConnectResult(null, "refused", localNetworkDenied = false) }
        assertFailsWith<IllegalStateException> { transport.connect("[fe80::1]", 47823, "fp") }
        assertEquals(listOf("fe80::1:47823:fp"), platform.connects)
    }

    @Test
    fun aRefusedLocalNetworkIsReported() = runTest {
        val platform = FakePlatform(spki)
        val transport = IosAgentTransport.load(platform, "iPhone")
        var reported = 0
        transport.onLocalNetworkDenied = { reported++ }
        platform.answer = { IosAgentConnectResult(null, "PolicyDenied", localNetworkDenied = true) }
        assertFailsWith<LocalNetworkDeniedException> { transport.connect("192.168.1.20", 47823, "fp") }
        assertEquals(1, reported)
    }

    @Test
    fun framesRoundTripThroughTheLink() = runTest {
        val platform = FakePlatform(spki)
        val link = LoopbackLink()
        platform.answer = { IosAgentConnectResult(link, null, localNetworkDenied = false) }
        val connection = IosAgentTransport.load(platform, "iPhone").connect("127.0.0.1", 47823, "fp")
        connection.send(Bye(ByeReason.ACCESS_OFF))
        assertEquals(Bye(ByeReason.ACCESS_OFF), connection.receive())
        // The pipe ended: no message, no exception.
        assertNull(connection.receive())
        connection.close()
        assertTrue(link.closed)
    }

    private fun ByteArray.containsAll(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private class FakePlatform(private val spki: ByteArray) : IosAgentPlatform {
        val installed = mutableListOf<NSData>()
        var signed = 0
        val connects = mutableListOf<String>()
        var answer: () -> IosAgentConnectResult = { IosAgentConnectResult(null, "no answer set", false) }

        override fun loadKey() = IosAgentKey(spki.toNSData(), null)

        override fun sign(data: NSData): NSData? {
            signed++
            return byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01).toNSData()
        }

        override fun installCertificate(certificate: NSData): String? {
            installed += certificate
            return null
        }

        override fun storedCertificate(): NSData? = installed.lastOrNull()

        override fun connect(host: String, port: Int, fingerprint: String, timeoutMs: Long, callback: (IosAgentConnectResult) -> Unit) {
            connects += "$host:$port:$fingerprint"
            callback(answer())
        }

        override fun browse(onFound: (IosBridgeFound) -> Unit, onFailed: (String) -> Unit): IosAgentCancellable =
            object : IosAgentCancellable {
                override fun cancel() {}
            }

        override fun scanQr(callback: (IosQrScanResult) -> Unit) = callback(IosQrScanResult(null, unavailable = true))

        override fun readAudio(path: String, offsetMs: Long, durationMs: Long, callback: (IosAudioClipResult) -> Unit) =
            callback(IosAudioClipResult(null, "no audio"))
    }

    /** Hands back what was sent; then reports the end. */
    private class LoopbackLink : IosAgentLink {
        private var buffer = ByteArray(0)
        var closed = false

        override fun send(data: NSData, callback: (String?) -> Unit) {
            buffer += data.toByteArray()
            callback(null)
        }

        override fun receive(length: Int, callback: (NSData?) -> Unit) {
            if (buffer.size < length) return callback(null)
            val part = buffer.copyOfRange(0, length)
            buffer = buffer.copyOfRange(length, buffer.size)
            callback(part.toNSData())
        }

        override fun close() {
            closed = true
        }
    }
}
