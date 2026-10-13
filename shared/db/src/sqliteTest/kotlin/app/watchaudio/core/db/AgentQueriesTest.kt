package app.watchaudio.core.db

import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AgentQueriesTest {
    private val dir = TempDir("agent-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun pairingLifecycle() = runTest {
        val id = store.transaction { addAgentPairing("laptop", "fp1", "192.168.1.2:47823", 30, allowAudio = false, createdAt = 100) }
        val pairing = store.read { agentPairing(id) }!!
        assertEquals(AgentPairing(id, "laptop", "fp1", "192.168.1.2:47823", 30, false, 100), pairing)
        assertEquals(pairing, store.read { activeAgentPairing("fp1") })

        store.transaction {
            setAgentScope(id, 7, allowAudio = true)
            setAgentAddress(id, "10.0.0.5:47823")
        }
        val changed = store.read { agentPairing(id) }!!
        assertEquals(7, changed.scopeDays)
        assertTrue(changed.allowAudio)
        assertEquals("10.0.0.5:47823", changed.address)

        assertTrue(store.transaction { revokeAgentPairing(id, 200) })
        assertFalse(store.transaction { revokeAgentPairing(id, 300) })
        assertEquals(200, store.read { agentPairing(id) }!!.revokedAt)
        assertNull(store.read { activeAgentPairing("fp1") })
    }

    @Test
    fun onlyOneActivePairingPerKey() = runTest {
        val first = store.transaction { addAgentPairing("laptop", "fp1", null, 30, false, 100) }
        assertFailsWith<Exception> { store.transaction { addAgentPairing("laptop", "fp1", null, 30, false, 101) } }
        store.transaction { revokeAgentPairing(first, 102) }
        val second = store.transaction { addAgentPairing("laptop", "fp1", null, 30, false, 103) }
        assertEquals(listOf(second, first), store.read { agentPairings() }.map { it.id })
    }

    @Test
    fun accessLogIsNewestFirstAndDeletedWithAllData() = runTest {
        val id = store.transaction { addAgentPairing("laptop", "fp1", null, 30, false, 100) }
        store.transaction {
            logAgentAccess(AgentAccessEntry(1_000, id, "search_transcripts", AgentAccessOutcome.OK, 3, "予算"))
            logAgentAccess(AgentAccessEntry(2_000, id, "get_audio_clip", AgentAccessOutcome.DENIED, 0, "utterance 5"))
        }
        val log = store.read { agentAccessLog(10) }
        assertEquals(listOf("get_audio_clip", "search_transcripts"), log.map { it.tool })
        assertEquals("予算", log[1].query)
        assertEquals(AgentAccessOutcome.DENIED, log[0].outcome)
        assertEquals(listOf("search_transcripts"), store.read { agentAccessLog(10, before = 2_000) }.map { it.tool })

        // Retention (#184): only entries strictly older than the cut-off go.
        assertEquals(0, store.transaction { deleteAgentAccessBefore(1_000) })
        assertEquals(1, store.transaction { deleteAgentAccessBefore(2_000) })
        assertEquals(listOf("get_audio_clip"), store.read { agentAccessLog(10) }.map { it.tool })

        store.transaction { deleteAllChunks() }
        assertTrue(store.read { agentAccessLog(10) }.isEmpty())
        // Pairings are settings, not recorded data: they stay.
        assertEquals(1, store.read { agentPairings() }.size)
    }

    @Test
    fun searchBetweenAndNeighbours() = runTest {
        store.transaction {
            registerChunk("a", 0)
            saveTranscript(
                "a",
                listOf(
                    NewSegment(1_000, 2_000, "予算の話 one", "ja"),
                    NewSegment(3_000, 4_000, "予算の話 two", "ja"),
                    NewSegment(5_000, 6_000, "予 short", "ja"),
                    NewSegment(7_000, 8_000, "予算の話 three", "ja"),
                ),
                "test",
            )
        }
        assertEquals(3, store.read { search("予算の話") }.size)
        assertEquals(listOf("予算の話 two"), store.read { searchBetween("予算の話", 2_000, 7_000) }.map { it.segment.text })
        // 1-2 character queries take the LIKE / vocabulary paths, both bounded too.
        assertEquals(listOf("予 short", "予算の話 two"), store.read { searchBetween("予", 2_000, 7_000) }.map { it.segment.text })
        assertTrue(store.read { searchBetween("予算の話", 7_000, 7_000) }.isEmpty())

        val all = store.read { segmentsBetween(0, 10_000) }
        val second = store.read { segment(all[1].id) }!!
        assertEquals(all[1], second)
        assertNull(store.read { segment(9_999) })
        assertEquals(all, store.read { segmentsAround(second, 5, 5) })
        assertEquals(all.subList(0, 3), store.read { segmentsAround(second, 1, 1) })
        // Bounded: neighbours outside [from, to) are left out, and the segment itself if it is.
        assertEquals(all.subList(1, 3), store.read { segmentsAround(second, 5, 1, from = 2_000) })
        assertEquals(all.subList(2, 4), store.read { segmentsAround(second, 5, 5, from = 4_000) })
    }
}
