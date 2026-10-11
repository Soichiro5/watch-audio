package app.watchaudio.android.diag

import android.app.ApplicationExitInfo
import app.watchaudio.core.pipeline.DiagnosticsLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.io.files.Path

class CrashRecorderTest {
    private val dir = Files.createTempDirectory("crash-recorder").toFile()
    private val file = File(dir, DiagnosticsLog.FILE_NAME)
    private val log = DiagnosticsLog(Path(file.path))

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writesTheCrashLineBeforeDelegating() {
        val delegated = mutableListOf<Pair<Thread, Throwable>>()
        var logWhenDelegated = ""
        val next = Thread.UncaughtExceptionHandler { thread, throwable ->
            logWhenDelegated = file.readText()
            delegated += thread to throwable
        }
        val crash = IllegalArgumentException("chunk text: 社外秘")
        val thread = Thread({}, "pipeline-1")

        CrashRecorder(log, next).uncaughtException(thread, crash)

        assertEquals(1, delegated.size)
        assertSame(crash, delegated.single().second)
        val line = logWhenDelegated.lines()[1]
        assertTrue(line.contains(",crash,thread=pipeline-1 type=java.lang.IllegalArgumentException"), line)
        assertTrue(line.contains(" at="), line)
        assertFalse(line.contains("社外秘"), line)
    }

    @Test
    fun aBrokenLogStillDelegates() {
        file.mkdirs() // appending to a directory fails
        var delegated = false
        CrashRecorder(log) { _, _ -> delegated = true }.uncaughtException(Thread.currentThread(), RuntimeException())
        assertTrue(delegated)
    }

    @Test
    fun installWrapsTheDefaultHandlerOnce() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashRecorder.install(log)
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertTrue(installed is CrashRecorder)
            CrashRecorder.install(log)
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun previousExitDescribesTheReasonAndDropsCrashDescriptions() {
        assertEquals(
            "at=1000 reason=LOW_MEMORY status=0 importance=400 pss_kb=2048 description=lmk",
            PreviousExits.describe(1_000, ApplicationExitInfo.REASON_LOW_MEMORY, 0, 400, 2048, "lmk"),
        )
        assertEquals(
            "at=1000 reason=CRASH status=0 importance=100 pss_kb=0",
            PreviousExits.describe(1_000, ApplicationExitInfo.REASON_CRASH, 0, 100, 0, "java.lang.Exception: secret"),
        )
        assertEquals("UNKNOWN(999)", PreviousExits.reasonName(999))
    }
}
