package app.watchaudio.wear.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import app.watchaudio.wear.R
import app.watchaudio.wear.recording.RecorderStore
import app.watchaudio.wear.recording.RecordingService
import app.watchaudio.wear.recording.statusKey
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: feeds an audio file to the recorder instead of the microphone.
 *
 * ```
 * adb shell am start -n app.watchaudio/app.watchaudio.wear.debug.DebugAudioInputActivity \
 *     --es file test.wav --ef speed 10 [--ez loop true]
 * adb shell am start -n app.watchaudio/app.watchaudio.wear.debug.DebugAudioInputActivity --ez stop true
 * ```
 *
 * `file` is an absolute path or a name in `files/debug-input/` or
 * `/sdcard/Android/data/app.watchaudio/files/debug-input/`. Without extras it shows the files
 * found there. Must run in the foreground: it starts the microphone foreground service.
 */
class DebugAudioInputActivity : ComponentActivity() {
    private var pending: (() -> Unit)? = null
    private var speed by mutableDoubleStateOf(DEFAULT_SPEED)
    private var loop by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme {
                AppScaffold { DebugScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            stopPlayback()
            return
        }
        val name = intent.getStringExtra(EXTRA_FILE) ?: return
        intent.speedExtra()?.let { speed = it }
        loop = intent.getBooleanExtra(EXTRA_LOOP, false)
        val file = DebugAudioInput.resolve(this, name)
        if (file == null) {
            Toast.makeText(this, getString(R.string.debug_input_not_found, name), Toast.LENGTH_LONG).show()
            return
        }
        play(file)
    }

    private fun play(file: File) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // The service is a `microphone` foreground service even when it plays a file.
            pending = { play(file) }
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        DebugAudioInput.use(this, file, speed, loop)
        lifecycleScope.launch {
            if (RecorderStore.state.value.serviceRunning) {
                // Restart so the next recorder run uses the file.
                RecordingService.stop(this@DebugAudioInputActivity)
                withTimeoutOrNull(RESTART_TIMEOUT_MS) { RecorderStore.state.first { !it.serviceRunning } }
            }
            RecordingService.start(this@DebugAudioInputActivity)
        }
    }

    private fun stopPlayback() {
        DebugAudioInput.useMicrophone(this)
        if (RecorderStore.state.value.serviceRunning) RecordingService.stop(this)
    }

    @Composable
    private fun DebugScreen() {
        val listState = rememberScalingLazyListState()
        val playback by DebugAudioInput.playback.collectAsStateWithLifecycle()
        val recorder by RecorderStore.state.collectAsStateWithLifecycle()
        val files = remember(playback) { DebugAudioInput.availableFiles(this) }
        var positionMs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(playback) {
            while (true) {
                positionMs = (playback?.positionSamples ?: 0L) * 1000 / 16_000
                delay(500)
            }
        }

        ScreenScaffold(scrollState = listState) { contentPadding ->
            ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
                item { Text(getString(R.string.debug_input_title), style = MaterialTheme.typography.titleSmall) }
                item {
                    val current = playback
                    val status = if (recorder.serviceRunning) recorder.mode.statusKey() else "stopped"
                    SmallText(
                        if (current == null) {
                            getString(R.string.debug_input_status_mic, status)
                        } else {
                            getString(R.string.debug_input_status_file, current.file.name, current.speed, positionMs / 1000, status)
                        },
                    )
                }
                if (playback != null || recorder.serviceRunning) {
                    item {
                        Button(
                            onClick = ::stopPlayback,
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(getString(R.string.debug_input_stop)) }
                    }
                }
                item {
                    Button(onClick = { speed = nextSpeed(speed) }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.debug_input_speed, speed))
                    }
                }
                item {
                    SwitchButton(checked = loop, onCheckedChange = { loop = it }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.debug_input_loop))
                    }
                }
                if (files.isEmpty()) {
                    item { SmallText(getString(R.string.debug_input_no_files, DebugAudioInput.inputDirs(this@DebugAudioInputActivity).joinToString("\n"))) }
                }
                items(files) { file ->
                    Button(onClick = { play(file) }, modifier = Modifier.fillMaxWidth()) { Text(file.name) }
                }
            }
        }
    }

    @Composable
    private fun SmallText(text: String) = Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    private fun Intent.speedExtra(): Double? {
        if (!hasExtra(EXTRA_SPEED)) return null
        getStringExtra(EXTRA_SPEED)?.let { return it.toDoubleOrNull() }
        val asFloat = getFloatExtra(EXTRA_SPEED, Float.NaN)
        if (!asFloat.isNaN()) return asFloat.toDouble()
        return getIntExtra(EXTRA_SPEED, 0).takeIf { it > 0 }?.toDouble()
    }

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_STOP = "stop"
        private const val DEFAULT_SPEED = 1.0
        private const val RESTART_TIMEOUT_MS = 5_000L
        private val SPEEDS = listOf(1.0, 10.0, 30.0, 60.0)

        private fun nextSpeed(current: Double) = SPEEDS.firstOrNull { it > current } ?: SPEEDS.first()
    }
}
