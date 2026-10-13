package app.watchaudio.core.agent

import app.watchaudio.core.agent.AgentToolsTest.Companion.DAY
import app.watchaudio.core.agent.AgentToolsTest.Companion.NOW
import app.watchaudio.core.db.AgentAccessEntry
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The phone's connection loop while agent access is on (#184), over an in-memory transport. */
class AgentConnectorTest {
    private val dir = TempDir("agent-connector")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val clock = FixedClock(NOW)
    private val service = AgentService(store, AgentTools(store, TimeZone.UTC, clock = clock), clock)
    private val transport = FakeTransport()

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun findsTheBridgeByMdnsServesItAndRemembersTheAddress() = runTest {
        seed()
        val id = pair(address = null)
        transport.bridges["192.168.1.20" to 47823] = BRIDGE_FP
        val connector = AgentConnector(store, service, transport, "test")
        val running = launch { connector.run() }

        // An advertisement of another PC is ignored; ours is found and connected to.
        transport.advertise(BridgeAdvertisement(NONCE, hashFor(NONCE, OTHER_FP), "192.168.1.30", 47823))
        transport.advertise(BridgeAdvertisement(NONCE, hashFor(NONCE, BRIDGE_FP), "192.168.1.20", 47823))
        val bridge = transport.accepted.receive()
        assertEquals("192.168.1.20" to 47823, bridge.address)
        assertIs<Hello>(bridge.fromPhone.receive())
        bridge.toPhone.send(Hello(1, AgentRole.BRIDGE, "laptop", "watch-audio-mcp/test"))
        bridge.toPhone.send(Rpc(call(1, "search_transcripts", "予算")))
        val answer = assertIs<Rpc>(bridge.fromPhone.receive())
        assertTrue("来期の予算" in answer.message.toString())
        assertEquals(LinkState.CONNECTED, connector.links.value[id])
        assertEquals("192.168.1.20:47823", store.read { agentPairing(id) }!!.address)
        assertEquals("search_transcripts", store.read { agentAccessLog(10) }.single().tool)
        assertEquals(listOf("192.168.1.20" to 47823), transport.attempts)

        // Turning access off says goodbye and closes.
        running.cancel()
        running.join()
        assertEquals(Bye(ByeReason.ACCESS_OFF), bridge.fromPhone.receive())
        assertTrue(bridge.closed)
        assertTrue(connector.links.value.isEmpty())
    }

    @Test
    fun usesTheLastKnownAddressAndReconnectsWhenTheBridgeLeaves() = runTest {
        val id = pair(address = "192.168.1.20:47823")
        transport.bridges["192.168.1.20" to 47823] = BRIDGE_FP
        val connector = AgentConnector(store, service, transport, "test")
        val running = launch { connector.run() }

        val first = transport.accepted.receive()
        assertIs<Hello>(first.fromPhone.receive())
        first.toPhone.send(Bye(ByeReason.SHUTDOWN)) // the agent quit
        connector.links.first { it[id] == LinkState.SEARCHING }
        val second = transport.accepted.receive() // after the retry pause
        assertIs<Hello>(second.fromPhone.receive())
        assertEquals(LinkState.CONNECTED, connector.links.first { it[id] == LinkState.CONNECTED }[id])
        running.cancel()
    }

    @Test
    fun neverConnectsOutsideTheLocalNetwork() = runTest {
        pair(address = "203.0.113.5:47823")
        transport.bridges["203.0.113.5" to 47823] = BRIDGE_FP
        val connector = AgentConnector(store, service, transport, "test")
        val running = launch { connector.run() }
        transport.advertise(BridgeAdvertisement(NONCE, hashFor(NONCE, BRIDGE_FP), "8.8.8.8", 47823))
        testScheduler.advanceTimeBy(120_000)
        assertTrue(transport.attempts.isEmpty())
        running.cancel()
    }

    @Test
    fun revokingDropsTheConnectionAtOnce() = runTest {
        val id = pair(address = "10.0.0.5:47823")
        transport.bridges["10.0.0.5" to 47823] = BRIDGE_FP
        val connector = AgentConnector(store, service, transport, "test")
        val running = launch { connector.run() }
        val bridge = transport.accepted.receive()
        assertIs<Hello>(bridge.fromPhone.receive())
        bridge.toPhone.send(Hello(1, AgentRole.BRIDGE, "laptop", "watch-audio-mcp/test"))
        connector.links.first { it[id] == LinkState.CONNECTED }

        store.transaction { revokeAgentPairing(id, NOW) }
        // (No withTimeout here: the store's real I/O would let virtual time run out.)
        assertEquals(Bye(ByeReason.REVOKED), bridge.fromPhone.receive())
        connector.links.first { id !in it }
        assertTrue(bridge.closed)
        testScheduler.advanceTimeBy(120_000)
        assertEquals(1, transport.attempts.size)
        running.cancel()
    }

    @Test
    fun theDeadlineEndsServingEvenIfTheTimerIsLate() = runTest {
        seed()
        pair(address = "10.0.0.5:47823")
        transport.bridges["10.0.0.5" to 47823] = BRIDGE_FP
        var allowed = true
        val connector = AgentConnector(store, service, transport, "test", allowed = { allowed })
        val running = launch { connector.run() }
        val bridge = transport.accepted.receive()
        assertIs<Hello>(bridge.fromPhone.receive())
        bridge.toPhone.send(Hello(1, AgentRole.BRIDGE, "laptop", "watch-audio-mcp/test"))
        allowed = false
        bridge.toPhone.send(Rpc(call(1, "search_transcripts", "予算")))
        testScheduler.advanceUntilIdle()
        assertTrue(store.read { agentAccessLog(10) }.isEmpty())
        assertTrue(bridge.closed)
        running.cancel()
    }

    @Test
    fun pairOverTriesTheLanAddressesInOrder() = runTest {
        transport.bridges["192.168.1.21" to 50000] = BRIDGE_FP
        val uri = PairingUri(1, "laptop", BRIDGE_FP, listOf("1.2.3.4:50000", "192.168.1.20:50000", "192.168.1.21:50000"), TOKEN, NOW + 300_000)
        val job = launch {
            val bridge = transport.accepted.receive()
            assertIs<PairRequest>(bridge.fromPhone.receive())
            bridge.toPhone.send(PairPending)
            assertEquals(PairConfirm, bridge.fromPhone.receive())
            bridge.toPhone.send(PairResult("laptop", 47823))
        }
        val outcome = pairOver(transport, store, uri, { NOW }) { true }
        job.join()
        assertEquals("192.168.1.21:47823", assertIs<PairOutcome.Paired>(outcome).pairing.address)
        // The public address is never tried.
        assertEquals(listOf("192.168.1.20" to 50000, "192.168.1.21" to 50000), transport.attempts)

        val unreachable = uri.copy(addresses = listOf("192.168.1.99:50000"))
        assertFailsWith<BridgeUnreachableException> { pairOver(transport, store, unreachable, { NOW }) { true } }
        assertFailsWith<BridgeUnreachableException> { pairOver(transport, store, uri.copy(addresses = listOf("1.2.3.4:5")), { NOW }) { true } }
    }

    @Test
    fun accessLogKeepsNinetyDays() = runTest {
        val id = pair(address = null)
        store.transaction {
            logAgentAccess(AgentAccessEntry(NOW - 91 * DAY, id, "search_transcripts", AgentAccessOutcome.OK, 1, "old"))
            logAgentAccess(AgentAccessEntry(NOW - 89 * DAY, id, "search_transcripts", AgentAccessOutcome.OK, 1, "recent"))
        }
        assertEquals(1, store.pruneAgentAccessLog(NOW))
        assertEquals(listOf("recent"), store.read { agentAccessLog(10) }.map { it.query })
    }

    private suspend fun seed() {
        store.transaction {
            insertChunk(testManifest("a", NOW - DAY), "a.m4a", 0)
            saveTranscript("a", listOf(NewSegment(NOW - DAY + 1_000, NOW - DAY + 5_000, "来期の予算を確認する", "ja")), "test")
        }
    }

    private suspend fun pair(address: String?): Long =
        store.transaction { addAgentPairing("laptop", BRIDGE_FP, address, 30, false, NOW) }

    private fun call(id: Int, tool: String, query: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "tools/call")
        put(
            "params",
            buildJsonObject {
                put("name", tool)
                put("arguments", buildJsonObject { put("query", query) })
            },
        )
    }

    /** The bridge's end of one in-memory connection. */
    private class FakeBridge(val address: Pair<String, Int>) : AgentConnection {
        val toPhone = Channel<AgentMessage>(Channel.UNLIMITED)
        val fromPhone = Channel<AgentMessage>(Channel.UNLIMITED)
        var closed = false
            private set

        override suspend fun receive(): AgentMessage? = toPhone.receiveCatching().getOrNull()

        override suspend fun send(message: AgentMessage) {
            if (!closed) fromPhone.send(message)
        }

        override fun close() {
            closed = true
            toPhone.close()
        }
    }

    /** Bridges by address with the key they present; every accepted connection lands in [accepted]. */
    private class FakeTransport : AgentTransport {
        override val phoneFingerprint = PHONE_FP
        override val phoneName = "Pixel"
        val bridges = HashMap<Pair<String, Int>, String>()
        val attempts = mutableListOf<Pair<String, Int>>()
        val accepted = Channel<FakeBridge>(Channel.UNLIMITED)
        private val ads = MutableSharedFlow<BridgeAdvertisement>(replay = 16)

        suspend fun advertise(ad: BridgeAdvertisement) = ads.emit(ad)

        override fun discover(): Flow<BridgeAdvertisement> = ads

        override suspend fun connect(host: String, port: Int, fingerprint: String): AgentConnection {
            attempts += host to port
            val key = bridges[host to port] ?: throw IllegalStateException("nobody at $host:$port")
            if (key != fingerprint) throw IllegalStateException("another key at $host:$port")
            return FakeBridge(host to port).also { accepted.send(it) }
        }
    }

    private companion object {
        val TOKEN = encodeBase64Url(ByteArray(16) { it.toByte() })
        val BRIDGE_FP = encodeBase64Url(ByteArray(32) { 1 })
        val OTHER_FP = encodeBase64Url(ByteArray(32) { 3 })
        val PHONE_FP = encodeBase64Url(ByteArray(32) { 2 })
        val NONCE = encodeBase64Url(ByteArray(8) { 7 })

        fun hashFor(nonce: String, fingerprint: String) =
            encodeBase64Url(sha256(decodeBase64Url(nonce)!! + fingerprintBytes(fingerprint)!!)).take(16)
    }
}
