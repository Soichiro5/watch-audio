package app.watchaudio.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import app.watchaudio.wear.outbox.UploadScheduler
import app.watchaudio.wear.recording.RecorderStore
import app.watchaudio.wear.recording.RecordingService
import app.watchaudio.wear.ui.RecorderScreen

/**
 * Start/stop, Mark and status. Also the entry point for "resume" taps (tile, complication, alert
 * notification): the microphone foreground service can only be started from here.
 */
class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph.of(this) }
    private var armed by mutableStateOf(false)
    private var uploadEnabled by mutableStateOf(true)

    private var askedOptional = false

    /** Label of the debug-only audio file input screen; null in release builds (not declared there). */
    private val debugInputLabel: String? by lazy {
        packageManager.resolveActivity(debugInputIntent(), PackageManager.ResolveInfoFlags.of(0))?.loadLabel(packageManager)?.toString()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMicPermission()) {
            if (!RecorderStore.state.value.serviceRunning) startRecording()
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPrefs()
        handleLaunchIntent(intent)
        setContent {
            val state by RecorderStore.state.collectAsStateWithLifecycle()
            MaterialTheme {
                AppScaffold {
                    RecorderScreen(
                        state = state,
                        armed = armed,
                        uploadEnabled = uploadEnabled,
                        onStart = ::requestStart,
                        onStop = ::stopRecording,
                        onMark = { RecordingService.mark(this@MainActivity) },
                        onUploadEnabledChange = ::changeUploadEnabled,
                        debugInputLabel = debugInputLabel,
                        onDebugInput = ::openDebugInput,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPrefs()
        graph.refreshPendingCount()
        // Armed but not running (process died, reboot): opening the app is the foreground start
        // Android 14+ requires, so resume right away.
        if (graph.prefs.armed && !RecorderStore.state.value.serviceRunning && hasMicPermission()) startRecording()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        val fromTile = intent.getBooleanExtra(EXTRA_FROM_TILE, false)
        if ((fromTile || intent.action == ACTION_RESUME) && !RecorderStore.state.value.serviceRunning) requestStart()
    }

    private fun requestStart() {
        if (hasMicPermission()) {
            startRecording()
            // Optional permissions are asked once in passing; recording works without them.
            val optional = optionalPermissions().filter { !isGranted(it) }
            if (optional.isNotEmpty() && !askedOptional) {
                askedOptional = true
                permissionLauncher.launch(optional.toTypedArray())
            }
        } else {
            permissionLauncher.launch((listOf(Manifest.permission.RECORD_AUDIO) + optionalPermissions()).toTypedArray())
        }
    }

    private fun startRecording() {
        RecordingService.start(this)
        armed = true
    }

    private fun stopRecording() {
        RecordingService.stop(this)
        armed = false
    }

    private fun debugInputIntent() = Intent(ACTION_DEBUG_AUDIO_INPUT).setPackage(packageName)

    private fun openDebugInput() = startActivity(debugInputIntent())

    private fun changeUploadEnabled(enabled: Boolean) {
        graph.prefs.uploadEnabled = enabled
        uploadEnabled = enabled
        if (enabled) UploadScheduler.uploadNow(this)
    }

    private fun refreshPrefs() {
        armed = graph.prefs.armed
        uploadEnabled = graph.prefs.uploadEnabled
    }

    private fun hasMicPermission() = isGranted(Manifest.permission.RECORD_AUDIO)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Notifications (stop alerts) and, up to Wear OS 5, BODY_SENSORS for the off-body sensor. Newer
     * releases replaced BODY_SENSORS with granular health permissions; off-body detection works
     * without them (to be confirmed on devices, see docs/impl/wearos.md).
     */
    private fun optionalPermissions() = buildList {
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM) add(Manifest.permission.BODY_SENSORS)
    }

    companion object {
        const val ACTION_RESUME = "app.watchaudio.wear.action.RESUME"
        const val EXTRA_FROM_TILE = "from_tile"

        /** Declared only by the debug manifest (`src/debug`): the audio file input screen. */
        private const val ACTION_DEBUG_AUDIO_INPUT = "app.watchaudio.wear.debug.AUDIO_INPUT"
    }
}
