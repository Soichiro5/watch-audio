package app.watchaudio.core.agent

import app.watchaudio.core.agent.AgentToolsTest.Companion.DAY
import app.watchaudio.core.agent.AgentToolsTest.Companion.NOW
import app.watchaudio.core.db.AgentAccessEntry
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.export.ExportRange
import app.watchaudio.core.export.ExportRequest
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** The access log (search terms) and pairings never leave the phone through the data export (#155). */
class AgentDataExportTest {
    private val dir = TempDir("agent-export")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun exportLeavesOutPairingsAndTheAccessLog() = runTest {
        store.transaction {
            insertChunk(testManifest("a", NOW - DAY), "a.m4a", 0)
            saveTranscript("a", listOf(NewSegment(NOW - DAY + 1_000, NOW - DAY + 5_000, "記録された発話", "ja")), "test")
            val pairing = addAgentPairing("PAIRED-PC-NAME", "FINGERPRINT-OF-THE-PC", "192.0.2.7:47823", 30, false, NOW)
            logAgentAccess(AgentAccessEntry(NOW, pairing, "search_transcripts", AgentAccessOutcome.OK, 1, "SECRET-SEARCH-TERM"))
        }
        val plan = assertNotNull(DataExporter(store, dir.resolve("audio")).plan(ExportRequest(ExportRange.ALL, false, TimeZone.UTC, NOW)))
        val buffer = Buffer()
        DataExporter(store, dir.resolve("audio")).write(plan, buffer)
        val archive = buffer.readByteArray()
        // Entries are stored uncompressed, so text in the archive can be searched for directly.
        assertTrue(archive.containsBytes("記録された発話".encodeToByteArray()))
        for (secret in listOf("SECRET-SEARCH-TERM", "PAIRED-PC-NAME", "FINGERPRINT-OF-THE-PC", "192.0.2.7")) {
            assertFalse(archive.containsBytes(secret.encodeToByteArray()), "$secret must not be exported")
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
