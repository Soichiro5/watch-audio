package app.watchaudio.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import app.watchaudio.wear.R
import app.watchaudio.wear.recording.RecorderMode
import app.watchaudio.wear.recording.RecorderUiState
import app.watchaudio.wear.recording.statusKey
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun RecorderScreen(
    state: RecorderUiState,
    armed: Boolean,
    uploadEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit,
    onUploadEnabledChange: (Boolean) -> Unit,
    debugInputLabel: String? = null,
    onDebugInput: () -> Unit = {},
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val running = state.serviceRunning
    val recording = state.mode == RecorderMode.Recording
    val statusKey = if (running) state.mode.statusKey() else "stopped"

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (recording) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = if (running) onStop else onStart,
                colors = if (running) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
            ) {
                Text(stringResource(if (running) R.string.action_stop else if (armed) R.string.action_resume else R.string.action_start))
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    text = statusText(context, statusKey, armed),
                    color = when {
                        recording -> Color(0xFFE53935)
                        running -> Color(0xFFFFB300)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val started = state.sessionStartedAtEpochMs
            if (recording && started != null) {
                item {
                    Text(
                        text = formatElapsed(now - started),
                        fontSize = 28.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(onClick = onMark, modifier = Modifier.fillMaxWidth()) {
                        Text("${stringResource(R.string.action_mark)}  (${state.marksInSession})")
                    }
                }
            }
            item { SmallText(stringResource(R.string.pending_uploads, state.pendingUploads)) }
            item {
                val lastAck = state.lastAckAtEpochMs
                SmallText(
                    if (lastAck == null) {
                        stringResource(R.string.last_ack_never)
                    } else {
                        stringResource(R.string.last_ack, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastAck)))
                    },
                )
            }
            state.batteryPercent?.let { battery -> item { SmallText(stringResource(R.string.battery, battery)) } }
            item {
                SwitchButton(
                    checked = uploadEnabled,
                    onCheckedChange = onUploadEnabledChange,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.send_to_phone)) }
            }
            // Debug builds only: the screen that feeds an audio file instead of the microphone.
            if (debugInputLabel != null) {
                item {
                    Button(
                        onClick = onDebugInput,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(debugInputLabel) }
                }
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

private fun formatElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
}
