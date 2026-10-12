package app.watchaudio.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.watchaudio.wear.R

/**
 * One-screen explanation shown right before the system asks for `BODY_SENSORS` (#161, Google Play
 * prominent disclosure): what it is used for (pausing when the watch is taken off), that nothing is
 * stored or sent, and that recording works without it. [onContinue] leads to the system dialog;
 * [onSkip] (also the back gesture) records without off-body detection.
 */
@Composable
fun BodySensorRationaleScreen(onContinue: () -> Unit, onSkip: () -> Unit) {
    val listState = rememberScalingLazyListState()
    BackHandler(onBack = onSkip)
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onContinue) { Text(stringResource(R.string.body_sensors_continue)) }
        },
    ) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    text = stringResource(R.string.body_sensors_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.body_sensors_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.body_sensors_optional),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.body_sensors_skip)) }
            }
        }
    }
}
