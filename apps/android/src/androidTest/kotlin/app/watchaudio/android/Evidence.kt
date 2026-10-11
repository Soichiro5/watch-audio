package app.watchaudio.android

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import org.junit.Assume.assumeTrue

/**
 * Evidence for the emulator checks (emulator.yml): `*_CHECK` log lines that the workflow copies
 * into the job summary, and screenshots taken with `screencap` (whole screen, including the
 * notification shade and system UI) that the workflow pulls as an artifact.
 */
object Evidence {
    const val TAG = "EmulatorEvidence"

    /** Where [screenshot] writes; owned by the shell user, so it survives uninstalling the app. */
    const val SCREENSHOT_DIR = "/data/local/tmp/watchaudio-screenshots"

    /** Logs one evidence line, e.g. `ASR_CHECK chunks=1 …`. */
    fun log(line: String) {
        Log.i(TAG, line)
    }

    /** Instrumentation argument (`-e name value`, or `android.testInstrumentationRunnerArguments.name`). */
    fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    /**
     * Scenario tests need steps outside the instrumentation (e.g. `am force-stop` between two runs,
     * or a fresh install) and only run when the workflow passes `-e scenario <name>`.
     */
    fun assumeScenario(name: String) = assumeTrue("scenario $name only runs from emulator.yml", argument("scenario") == name)

    /** Runs [command] as the shell user (no shell syntax: no pipes or redirections) and returns its output. */
    fun shell(command: String): String = shellStream(command).use { it.readBytes().decodeToString() }

    /** Output of [command] run as the shell user; closing the stream ends the command. */
    fun shellStream(command: String): InputStream {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd)
    }

    /** Copies a file the shell user can read (e.g. under /data/local/tmp) into the app's storage. */
    fun copyFromDevice(devicePath: String, target: File): Long {
        target.parentFile?.mkdirs()
        return shellStream("cat $devicePath").use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    /** Size of a file the shell user can see, or null if it does not exist. */
    fun deviceFileSize(devicePath: String): Long? = shell("stat -c %s $devicePath").trim().toLongOrNull()

    /** Saves a screenshot of the whole screen as `$SCREENSHOT_DIR/<name>.png`. */
    fun screenshot(name: String) {
        shell("mkdir -p $SCREENSHOT_DIR")
        // Let animations (shade, ripples) settle.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(1_000)
        shell("screencap -p $SCREENSHOT_DIR/$name.png")
        val size = deviceFileSize("$SCREENSHOT_DIR/$name.png")
        log("SCREENSHOT name=$name.png bytes=$size")
        check(size != null && size > 0) { "screenshot $name was not written" }
    }
}
