package app.watchaudio.core.pipeline

import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** File-based tests (JVM and the iOS simulator); they live with the SQLite tests for [TempDir]. */
class DiagnosticsLogTest {
    private val dir = TempDir("diagnostics")
    private var now = 1_760_000_000_000L
    private val file = dir.resolve("diag/${DiagnosticsLog.FILE_NAME}")

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    private fun read(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun size(path: Path) = SystemFileSystem.metadataOrNull(path)?.size ?: 0

    @Test
    fun writesHeaderOnceAndOneSanitizedLinePerEvent() {
        val log = DiagnosticsLog(file, clock = { now })
        log.log(DiagnosticEvents.APP_START, "version=0.1.0")
        now += 1_000
        log.log(DiagnosticEvents.ACK_FAILED, "chunk=c1 reason=a,b\nc")

        assertEquals(
            DiagnosticsLog.HEADER +
                "2025-10-09T08:53:20Z,app_start,version=0.1.0\n" +
                "2025-10-09T08:53:21Z,ack_failed,chunk=c1 reason=a;b c\n",
            read(file),
        )
    }

    @Test
    fun rotatesAtTheCapAndKeepsOnlyOnePreviousFile() {
        val log = DiagnosticsLog(file, maxBytes = 300, clock = { now })
        repeat(40) { log.log(DiagnosticEvents.TRANSCRIPTION_STARTED, "chunk=chunk-$it") }

        assertTrue(SystemFileSystem.exists(log.previousFile))
        // Each file grows past the cap by at most one line before it is rotated.
        assertTrue(size(file) < 300 + 100, "current ${size(file)}")
        assertTrue(size(log.previousFile) < 300 + 100, "previous ${size(log.previousFile)}")
        val parent = file.parent!!
        assertEquals(setOf(file.name, log.previousFile.name), SystemFileSystem.list(parent).map { it.name }.toSet())
        // Both files start with the header and the newest event is in the current file.
        assertTrue(read(log.previousFile).startsWith(DiagnosticsLog.HEADER))
        assertTrue(read(file).startsWith(DiagnosticsLog.HEADER))
        assertTrue(read(file).trimEnd().endsWith("chunk=chunk-39"))
    }

    @Test
    fun exportJoinsPreviousAndCurrentInOrderUnderOneHeader() {
        val log = DiagnosticsLog(file, maxBytes = 300, clock = { now })
        repeat(12) { log.log(DiagnosticEvents.CHUNK_RECEIVED, "chunk=c$it") }
        val target = dir.resolve("export/out.csv")

        val count = log.exportTo(target)

        val lines = read(target).lines().filter { it.isNotBlank() }
        assertEquals(DiagnosticsLog.HEADER.trim(), lines.first())
        assertEquals(1, lines.count { it == DiagnosticsLog.HEADER.trim() })
        val chunks = lines.drop(1).map { it.substringAfterLast("chunk=c").toInt() }
        assertEquals(count, chunks.size)
        assertEquals(chunks.sorted(), chunks)
        assertEquals(11, chunks.last())
    }

    @Test
    fun exportOfAnEmptyLogHasOnlyTheHeader() {
        val target = dir.resolve("out.csv")
        assertEquals(0, DiagnosticsLog(file).exportTo(target))
        assertEquals(DiagnosticsLog.HEADER, read(target))
    }

    @Test
    fun crashLineHasTypesAndFramesButNoMessages() {
        val log = DiagnosticsLog(file, clock = { now })
        val crash = IllegalStateException("transcript: 秘密の会話", RuntimeException("cause message"))

        log.recordCrash("worker-1", crash)

        val line = read(file).lines()[1]
        assertTrue(line.contains(",crash,thread=worker-1 type="), line)
        assertTrue(line.contains("IllegalStateException"), line)
        assertTrue(line.contains("causes=") && line.contains("RuntimeException"), line)
        assertFalse(line.contains("秘密"), line)
        assertFalse(line.contains("cause message"), line)
    }

    @Test
    fun logFailuresAreSwallowed() {
        // The log path is an existing directory: appending fails, but the caller never sees it.
        SystemFileSystem.createDirectories(file)
        DiagnosticsLog(file).log(DiagnosticEvents.APP_START)
    }

    @Test
    fun realTimeFactorRoundsToTwoDecimals() {
        assertEquals(0.07, DiagnosticsLog.realTimeFactor(elapsedMs = 4_000, audioMs = 60_000))
        assertEquals(null, DiagnosticsLog.realTimeFactor(elapsedMs = 4_000, audioMs = 0))
    }

    @Test
    fun clearRemovesTheLogTheRotatedPartAndExportsOnly() {
        val exports = dir.resolve("export")
        val log = DiagnosticsLog(file, maxBytes = 200, clock = { now }, exportDirectory = exports)
        repeat(10) { log.log(DiagnosticEvents.CHUNK_RECEIVED, "chunk=c$it") }
        log.exportTo(Path(exports, "out.csv"))
        val neighbour = Path(file.parent!!, "other.txt")
        SystemFileSystem.sink(neighbour).buffered().use { it.writeString("keep") }

        assertTrue(log.clear())

        assertFalse(SystemFileSystem.exists(file))
        assertFalse(SystemFileSystem.exists(log.previousFile))
        assertEquals(emptyList(), SystemFileSystem.list(exports))
        assertTrue(SystemFileSystem.exists(neighbour), "other files next to the log are kept")
        assertEquals(0, log.exportTo(dir.resolve("after.csv")))
        log.log(DiagnosticEvents.APP_START)
        assertEquals(DiagnosticsLog.HEADER + "2025-10-09T08:53:20Z,app_start,\n", read(file))
    }

    @Test
    fun clearOfAnEmptyLogSucceeds() {
        assertTrue(DiagnosticsLog(file, exportDirectory = dir.resolve("missing")).clear())
    }
}
