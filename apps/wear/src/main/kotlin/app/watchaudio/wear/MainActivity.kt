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
import app.watchaudio.wear.ui.BodySensorRationaleScreen
import app.watchaudio.wear.ui.RecorderScreen

/**
 * Start/stop, Mark and status. Also the entry point for "resume" taps (tile, complication, alert
 * notification): the microphone foreground service can only be started from here.
 */
class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph.of(this) }
    private var armed by mutableStateOf(false)
    private var uploadEnabled by mutableStateOf(true)

    /** The body sensor explanation is on screen instead of the recorder (#161). */
    private var explainingBodySensors by mutableStateOf(false)
    private var offBodyDetectionOff by mutableStateOf(false)
    private var notificationsAsked = false

    /** Label of the debug-only audio file input screen; null in release builds (not declared there). */
    private val debugInputLabel: String? by lazy {
        packageManager.resolveActivity(debugInputIntent(), PackageManager.ResolveInfoFlags.of(0))?.loadLabel(packageManager)?.toString()
    }

    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMicPermission()) {
            if (!RecorderStore.state.value.serviceRunning) startRecording()
            continuePermissions()
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val bodySensorsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The recorder started before the dialog, so its off-body sensor registration failed: retry it.
        if (granted && RecorderStore.state.value.serviceRunning) RecordingService.refreshMonitors(this)
        graph.log(if (granted) "body_sensors_granted" else "body_sensors_declined", "dialog")
        refreshOffBodyNote()
        continuePermissions()
    }

    private val notificationsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPrefs()
        handleLaunchIntent(intent)
        setContent {
            val state by RecorderStore.state.collectAsStateWithLifecycle()
            MaterialTheme {
                AppScaffold {
                    if (explainingBodySensors) {
                        BodySensorRationaleScreen(onContinue = ::continueToBodySensors, onSkip = ::skipBodySensors)
                    } else {
                        RecorderScreen(
                            state = state,
                            armed = armed,
                            uploadEnabled = uploadEnabled,
                            onStart = ::requestStart,
                            onStop = ::stopRecording,
                            onMark = { RecordingService.mark(this@MainActivity) },
                            onUploadEnabledChange = ::changeUploadEnabled,
                            offBodyDetectionOff = offBodyDetectionOff,
                            debugInputLabel = debugInputLabel,
                            onDebugInput = ::openDebugInput,
                        )
                    }
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
        val offBodyWasOff = offBodyDetectionOff
        refreshOffBodyNote()
        // Body sensors allowed in the system settings meanwhile: register the off-body sensor now.
        if (offBodyWasOff && !offBodyDetectionOff && RecorderStore.state.value.serviceRunning) RecordingService.refreshMonitors(this)
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
        if (hasMicPermission()) startRecording()
        continuePermissions()
    }

    /**
     * Walks the start flow ([nextPermissionStep]): microphone, then the body sensor explanation
     * right before its own system dialog (#161), then notifications. Recording never waits for the
     * optional ones.
     */
    private fun continuePermissions() {
        when (nextPermissionStep(permissionState())) {
            PermissionStep.RequestMicrophone -> {
                val missingNotifications = !isGranted(Manifest.permission.POST_NOTIFICATIONS) && !notificationsAsked
                notificationsAsked = true
                val permissions = listOf(Manifest.permission.RECORD_AUDIO) +
                    listOfNotNull(Manifest.permission.POST_NOTIFICATIONS.takeIf { missingNotifications })
                microphoneLauncher.launch(permissions.toTypedArray())
            }
            PermissionStep.ExplainBodySensors -> explainingBodySensors = true
            PermissionStep.RequestNotifications -> {
                notificationsAsked = true
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PermissionStep.Done -> Unit
        }
    }

    private fun continueToBodySensors() {
        graph.prefs.bodySensorsAnswered = true
        explainingBodySensors = false
        bodySensorsLauncher.launch(Manifest.permission.BODY_SENSORS)
    }

    private fun skipBodySensors() {
        graph.prefs.bodySensorsAnswered = true
        explainingBodySensors = false
        graph.log("body_sensors_declined", "explanation")
        refreshOffBodyNote()
        continuePermissions()
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

    private fun permissionState() = PermissionState(
        microphone = hasMicPermission(),
        notifications = isGranted(Manifest.permission.POST_NOTIFICATIONS),
        bodySensorsDeclared = bodySensorsDeclared(),
        bodySensors = bodySensorsDeclared() && isGranted(Manifest.permission.BODY_SENSORS),
        bodySensorsAnswered = graph.prefs.bodySensorsAnswered,
        notificationsAsked = notificationsAsked,
    )

    private fun refreshOffBodyNote() {
        offBodyDetectionOff = offBodyDetectionDeclined(permissionState())
    }

    /**
     * `BODY_SENSORS` is declared only up to Wear OS 5 (`maxSdkVersion=35`), for the off-body sensor.
     * Wear OS 6 (API 36) moved `BODY_SENSORS` to granular health permissions (`READ_HEART_RATE`, ...)
     * that cover heart rate, SpO2 and skin temperature, not off-body detection; the app requests none
     * there and registers the sensor without a permission (see docs/platforms/wearos.md §6).
     */
    private fun bodySensorsDeclared() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM

    companion object {
        const val ACTION_RESUME = "app.watchaudio.wear.action.RESUME"
        const val EXTRA_FROM_TILE = "from_tile"

        /** Declared only by the debug manifest (`src/debug`): the audio file input screen. */
        private const val ACTION_DEBUG_AUDIO_INPUT = "app.watchaudio.wear.debug.AUDIO_INPUT"
    }
}
