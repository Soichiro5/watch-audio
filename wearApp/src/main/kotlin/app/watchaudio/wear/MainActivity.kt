package app.watchaudio.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import app.watchaudio.wear.recording.RecorderStore
import app.watchaudio.wear.recording.RecordingService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold {
                    ScreenScaffold { SpikeRecorderScreen() }
                }
            }
        }
    }
}

/** Minimal controls + live stats for the M0-4 battery spike (#10). */
@Composable
private fun SpikeRecorderScreen() {
    val context = LocalContext.current
    val state by RecorderStore.state.collectAsStateWithLifecycle()
    var sendChunks by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) RecordingService.start(context, sendChunks)
    }
    fun startRecording() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) RecordingService.start(context, sendChunks) else launcher.launch(permissions)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            if (state.isRecording) {
                val elapsedMin = (now - state.startedAtMillis) / 60_000
                Text("● REC  ${elapsedMin / 60}h ${elapsedMin % 60}m")
            } else {
                Text("watch-audio spike")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.isRecording) {
                    Button(onClick = { RecordingService.mark(context) }) { Text("Mark") }
                    Button(onClick = { RecordingService.stop(context) }) { Text("Stop") }
                } else {
                    Button(onClick = ::startRecording) { Text("Start") }
                }
            }
        }
        if (!state.isRecording) {
            item {
                SwitchButton(
                    checked = sendChunks,
                    onCheckedChange = { sendChunks = it },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send to phone") }
            }
        }
        item {
            val drain = state.drainPerHour(now)?.let { "%.1f%%/h".format(it) } ?: "-"
            Text("Battery ${state.batteryAtStart ?: "-"}% → ${state.batteryNow ?: "-"}% ($drain)")
        }
        item { Text("Chunks ${state.chunksClosed} / sent ${state.chunksSent} / marks ${state.marks}") }
        item { Text("${state.bytesRecorded / 1024} KB  ${state.lastEvent}") }
    }
}
