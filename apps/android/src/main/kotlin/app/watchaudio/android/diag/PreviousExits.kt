package app.watchaudio.android.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics

/**
 * Logs why earlier processes of the app ended (killed for memory, ANR, crash, force-stopped, ...)
 * from `ApplicationExitInfo` (Android 11+), each exit once (#77).
 */
object PreviousExits {
    private const val PREFS = "diagnostics"
    private const val KEY_LAST_EXIT = "last_logged_exit_at"
    private const val MAX_EXITS = 10

    fun record(context: Context, diagnostics: Diagnostics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS) }
            .getOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastLogged = prefs.getLong(KEY_LAST_EXIT, 0)
        exits.filter { it.timestamp > lastLogged }.sortedBy { it.timestamp }.forEach { exit ->
            diagnostics.log(
                DiagnosticEvents.PREVIOUS_EXIT,
                describe(exit.timestamp, exit.reason, exit.status, exit.importance, exit.pss, exit.description),
            )
        }
        exits.maxOfOrNull { it.timestamp }?.takeIf { it > lastLogged }?.let { prefs.edit().putLong(KEY_LAST_EXIT, it).apply() }
    }

    /**
     * One `previous_exit` detail. The system's description is kept except for Java crashes, whose
     * own crash line (types and frames, no message) is already in the log.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun describe(timestamp: Long, reason: Int, status: Int, importance: Int, pssKb: Long, description: String?): String =
        buildList {
            add("at=$timestamp")
            add("reason=${reasonName(reason)}")
            add("status=$status")
            add("importance=$importance")
            add("pss_kb=$pssKb")
            if (reason != ApplicationExitInfo.REASON_CRASH && !description.isNullOrBlank()) {
                add("description=${description.take(120)}")
            }
        }.joinToString(" ")

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }
}
