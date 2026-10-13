package app.watchaudio.core.agent

import app.watchaudio.core.agent.AgentToolsTest.Companion.DAY
import app.watchaudio.core.agent.AgentToolsTest.Companion.NOW
import app.watchaudio.core.agent.AgentToolsTest.Companion.args
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The phone's session: revocation, rate limit and the access log, and the link's conversations. */
class AgentServiceTest {
    private val dir = TempDir("agent-service")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val clock = FixedClock(NOW)
    private val service = AgentService(store, AgentTools(store, TimeZone.UTC, clock = clock), clock, callsPerMinute = 3)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun callsAreAnsweredAndLogged() = runTest {
        seed()
        val pairing = pair()
        val reply = service.handle(pairing, call(1, "search_transcripts", args("query" to "予算")))
        assertFalse(reply.revoked)
        val result = reply.response!!.getValue("result").jsonObject
        assertEquals(1, reply.response!!.getValue("id").jsonPrimitive.int)
        assertFalse(result.getValue("isError").jsonPrimitive.boolean)

        val entry = store.read { agentAccessLog(10) }.single()
        assertEquals("search_transcripts", entry.tool)
        assertEquals(AgentAccessOutcome.OK, entry.outcome)
        assertEquals(1, entry.itemCount)
        assertEquals("予算", entry.query)
    }

    @Test
    fun revokedPairingIsRefusedAndLogged() = runTest {
        seed()
        val pairing = pair()
        store.transaction { revokeAgentPairing(pairing, NOW) }
        val reply = service.handle(pairing, call(1, "search_transcripts", args("query" to "予算")))
        assertTrue(reply.revoked)
        val result = reply.response!!.getValue("result").jsonObject
        assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        assertFalse("来期" in result.toString())
        val entry = store.read { agentAccessLog(10) }.single()
        assertEquals(AgentAccessOutcome.REVOKED, entry.outcome)
        assertEquals("予算", entry.query)
    }

    @Test
    fun callsPerMinuteAreLimited() = runTest {
        seed()
        val pairing = pair()
        repeat(3) { assertFalse(isError(service.handle(pairing, call(it, "search_transcripts", args("query" to "予算"))).response!!)) }
        val limited = service.handle(pairing, call(9, "search_transcripts", args("query" to "予算"))).response!!
        assertTrue(isError(limited))
        assertEquals(AgentAccessOutcome.RATE_LIMITED, store.read { agentAccessLog(1) }.single().outcome)
        clock.nowMs += 60_001
        assertFalse(isError(service.handle(pairing, call(10, "search_transcripts", args("query" to "予算"))).response!!))
    }

    @Test
    fun otherMethodsAndNotifications() = runTest {
        val pairing = pair()
        val unknown = service.handle(pairing, buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "x")
            put("method", "resources/list")
        }).response!!
        assertEquals(AgentService.METHOD_NOT_FOUND, unknown.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        assertNull(service.handle(pairing, buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/cancelled")
        }).response)
        assertTrue(store.read { agentAccessLog(10) }.isEmpty())
    }

    @Test
    fun pairStoresThePairingAfterBothConfirm() = runTest {
        val bridge = FakeBridge()
        val uri = PairingUri(1, "laptop", BRIDGE_FP, listOf("192.168.1.2:50000"), TOKEN, NOW + 300_000)
        var shown: String? = null
        val outcome = run {
            val job = launch {
                assertEquals(PairRequest(1, TOKEN, "Pixel"), bridge.fromPhone.receive())
                bridge.toPhone.send(PairPending)
                assertEquals(PairConfirm, bridge.fromPhone.receive())
                bridge.toPhone.send(PairResult("laptop", 47823))
            }
            AgentLink.pair(bridge.phoneSide, store, uri, "192.168.1.2", "Pixel", PHONE_FP, { NOW }) { code ->
                shown = code
                true
            }.also { job.join() }
        }
        assertEquals(confirmationCode(TOKEN, BRIDGE_FP, PHONE_FP), shown)
        val pairing = assertIs<PairOutcome.Paired>(outcome).pairing
        assertEquals("192.168.1.2:47823", pairing.address)
        assertEquals(AgentLimits.DEFAULT_SCOPE_DAYS, pairing.scopeDays)
        assertFalse(pairing.allowAudio)
        assertEquals(pairing, store.read { activeAgentPairing(BRIDGE_FP) })
    }

    @Test
    fun pairStopsWhenTheBridgeRefusesOrTheUserDeclines() = runTest {
        val uri = PairingUri(1, "laptop", BRIDGE_FP, listOf("192.168.1.2:50000"), TOKEN, NOW)
        val expired = FakeBridge()
        expired.toPhone.send(ErrorMessage(AgentErrorCode.PAIRING_EXPIRED, "expired"))
        val refused = AgentLink.pair(expired.phoneSide, store, uri, "h", "Pixel", PHONE_FP, { NOW }) { error("no code may be shown") }
        assertEquals(PairOutcome.Refused(AgentErrorCode.PAIRING_EXPIRED, "expired"), refused)

        val declined = FakeBridge()
        declined.toPhone.send(PairPending)
        assertEquals(PairOutcome.Declined, AgentLink.pair(declined.phoneSide, store, uri, "h", "Pixel", PHONE_FP, { NOW }) { false })
        declined.fromPhone.receive() // the request
        assertEquals(AgentErrorCode.PAIRING_REJECTED, (declined.fromPhone.receive() as ErrorMessage).code)
        assertTrue(store.read { agentPairings() }.isEmpty())
    }

    @Test
    fun serveAnswersUntilByeAndLeavesOnRevocation() = runTest {
        seed()
        val id = pair()
        val pairing = store.read { agentPairing(id) }!!
        val bridge = FakeBridge()
        val end = run {
            val job = launch {
                assertIs<Hello>(bridge.fromPhone.receive())
                bridge.toPhone.send(Hello(1, AgentRole.BRIDGE, "laptop", "watch-audio-mcp/test"))
                bridge.toPhone.send(Rpc(call(1, "search_transcripts", args("query" to "予算"))))
                val answer = bridge.fromPhone.receive() as Rpc
                assertTrue("来期の予算" in answer.message.toString())
                store.transaction { revokeAgentPairing(pairing.id, NOW) }
                bridge.toPhone.send(Rpc(call(2, "search_transcripts", args("query" to "予算"))))
                assertIs<Rpc>(bridge.fromPhone.receive())
                assertEquals(Bye(ByeReason.REVOKED), bridge.fromPhone.receive())
            }
            AgentLink.serve(bridge.phoneSide, service, pairing, "Pixel", "test").also { job.join() }
        }
        assertEquals(ServeEnd.Revoked, end)

        val other = FakeBridge()
        other.toPhone.send(Hello(2, AgentRole.BRIDGE, "laptop", "future"))
        assertIs<ServeEnd.Refused>(AgentLink.serve(other.phoneSide, service, pairing, "Pixel", "test"))
    }

    private suspend fun seed() {
        store.transaction {
            insertChunk(testManifest("a", NOW - DAY), "a.m4a", 0)
            saveTranscript("a", listOf(NewSegment(NOW - DAY + 1_000, NOW - DAY + 5_000, "来期の予算を確認する", "ja")), "test")
        }
    }

    private suspend fun pair(): Long = store.transaction { addAgentPairing("laptop", BRIDGE_FP, null, 30, false, NOW) }

    private fun call(id: Int, tool: String, arguments: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "tools/call")
        put(
            "params",
            buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            },
        )
    }

    private fun isError(response: JsonObject) = response.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean

    /** Both ends of an in-memory connection; the test plays the bridge. */
    private class FakeBridge {
        val toPhone = Channel<AgentMessage>(Channel.UNLIMITED)
        val fromPhone = Channel<AgentMessage>(Channel.UNLIMITED)
        val phoneSide = object : AgentChannel {
            override suspend fun receive(): AgentMessage? = toPhone.receiveCatching().getOrNull()

            override suspend fun send(message: AgentMessage) {
                fromPhone.send(message)
            }
        }
    }

    private companion object {
        val TOKEN = encodeBase64Url(ByteArray(16) { it.toByte() })
        val BRIDGE_FP = encodeBase64Url(ByteArray(32) { 1 })
        val PHONE_FP = encodeBase64Url(ByteArray(32) { 2 })
    }
}
