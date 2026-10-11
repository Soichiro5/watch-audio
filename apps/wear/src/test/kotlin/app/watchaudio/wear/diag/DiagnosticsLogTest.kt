package app.watchaudio.wear.diag

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val snapshot = DiagnosticsLog.Snapshot(batteryPercent = 87, charging = false, mode = "recording", pendingUploads = 2)

    @Test
    fun `writes a csv header and escapes commas`() {
        val log = DiagnosticsLog(File(tmp.root, "diag/events.csv")) { 0L }
        log.append(snapshot, "chunk_closed", "a,b\nc")
        val lines = log.file.readLines()
        assertEquals(DiagnosticsLog.HEADER.trim(), lines[0])
        assertEquals("1970-01-01T00:00:00Z,87,false,recording,2,chunk_closed,a;b c", lines[1])
    }

    @Test
    fun `rotates when the cap is reached and keeps one previous file`() {
        val log = DiagnosticsLog(File(tmp.root, "events.csv"), maxBytes = 1_000) { 0L }
        repeat(200) { log.append(snapshot, "battery", "n=$it") }
        val previous = File(tmp.root, "events.csv.1")
        assertTrue(previous.exists())
        assertTrue(log.file.length() < 1_000 + 200)
        assertEquals(setOf("events.csv", "events.csv.1"), tmp.root.list()!!.toSet())
        assertTrue(log.file.readLines().first().startsWith("timestamp"))
    }
}
