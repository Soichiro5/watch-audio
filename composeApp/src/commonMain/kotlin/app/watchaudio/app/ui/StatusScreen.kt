package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.watchaudio.app.ModelState
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.ArrivalState
import app.watchaudio.app.logic.Thresholds
import app.watchaudio.app.logic.formatAgo
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatHours
import app.watchaudio.app.logic.formatMegabytes
import app.watchaudio.app.state.StatusUiState
import app.watchaudio.core.db.StoreStats
import kotlinx.datetime.TimeZone

@Composable
internal fun StatusScreen(
    state: StatusUiState,
    timeZone: TimeZone,
    onDownloadModel: (allowMetered: Boolean) -> Unit,
    onTranscribeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArrivalCard(state, timeZone)
        ModelCard(state, onDownloadModel, onTranscribeNow)
        state.stats?.let { StatsCard(it) }
        Section(Strings.PRIVACY_TITLE) {
            Text(Strings.PRIVACY_NOTE, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ArrivalCard(state: StatusUiState, timeZone: TimeZone) {
    val arrival = state.arrival
    val late = arrival is ArrivalState.Late
    Section(
        Strings.ARRIVAL,
        container = if (late) MaterialTheme.colorScheme.errorContainer else null,
    ) {
        val lastReceivedAt = when (arrival) {
            ArrivalState.NoData -> null
            is ArrivalState.Receiving -> arrival.lastReceivedAt
            is ArrivalState.Stopped -> arrival.lastReceivedAt
            is ArrivalState.Late -> arrival.lastReceivedAt
        }
        if (lastReceivedAt == null) {
            Text(Strings.NO_CHUNKS_YET, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                Strings.lastReceived(formatClock(lastReceivedAt, timeZone), formatAgo(state.now - lastReceivedAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (arrival) {
            is ArrivalState.Receiving -> Text(Strings.ARRIVAL_OK, style = MaterialTheme.typography.bodySmall)
            is ArrivalState.Stopped -> Text(
                Strings.recordingStopped(Strings.endReason(arrival.reason)),
                style = MaterialTheme.typography.bodySmall,
            )
            is ArrivalState.Late -> Text(
                Strings.arrivalLate(Thresholds.ARRIVAL_WARNING_MS / 60_000),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            ArrivalState.NoData -> Unit
        }
    }
}

@Composable
private fun ModelCard(state: StatusUiState, onDownloadModel: (Boolean) -> Unit, onTranscribeNow: () -> Unit) {
    val model = state.status.asrModel
    Section(Strings.ASR_MODEL) {
        Text(
            when (model) {
                ModelState.Unknown -> Strings.MODEL_UNKNOWN
                ModelState.Missing -> Strings.MODEL_MISSING
                is ModelState.Downloading -> Strings.modelDownloading((model.progress * 100).toInt().coerceIn(0, 100))
                ModelState.Ready -> Strings.MODEL_READY
                is ModelState.Failed -> Strings.modelFailed(model.reason)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (model is ModelState.Downloading) {
            LinearProgressIndicator(progress = { model.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (model == ModelState.Missing || model is ModelState.Failed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDownloadModel(false) }) { Text(Strings.DOWNLOAD_WIFI) }
                OutlinedButton(onClick = { onDownloadModel(true) }) { Text(Strings.DOWNLOAD_ANY) }
            }
        }
        if (state.status.transcribing) {
            Text(Strings.TRANSCRIBING_NOW, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (model == ModelState.Ready && (state.stats?.pendingChunks ?: 0) > 0) {
            OutlinedButton(onClick = onTranscribeNow) { Text(Strings.TRANSCRIBE_NOW) }
        }
        state.status.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatsCard(stats: StoreStats) {
    Section(Strings.STORAGE) {
        StatRow(Strings.STAT_RECORDED, formatHours(stats.recordedMs))
        StatRow(Strings.STAT_AUDIO, formatMegabytes(stats.audioBytes))
        StatRow(Strings.STAT_CHUNKS, Strings.count(stats.chunks))
        StatRow(Strings.STAT_SEGMENTS, Strings.count(stats.segments))
        StatRow(Strings.STAT_PENDING, Strings.count(stats.pendingChunks))
        StatRow(
            Strings.STAT_FAILED,
            Strings.count(stats.failedChunks),
            if (stats.failedChunks > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun Section(title: String, container: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = if (container != null) CardDefaults.cardColors(containerColor = container) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
