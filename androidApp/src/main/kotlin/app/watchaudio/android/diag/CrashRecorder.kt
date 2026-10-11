package app.watchaudio.android.diag

import app.watchaudio.core.pipeline.DiagnosticsLog

/**
 * Writes a crash line (exception types and top frames, no messages) to the diagnostics log, then
 * hands the exception on to [next], normally the platform handler that ends the process (#77).
 */
class CrashRecorder(
    private val log: DiagnosticsLog,
    private val next: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            log.recordCrash(thread.name, throwable)
        } catch (_: Throwable) {
            // Never let the log hide the original crash.
        }
        next?.uncaughtException(thread, throwable)
    }

    companion object {
        /** Wraps the current default handler once per process. */
        fun install(log: DiagnosticsLog) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashRecorder) return
            Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(log, current))
        }
    }
}
