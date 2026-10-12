package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatBytes
import app.watchaudio.app.logic.formatDate
import app.watchaudio.app.state.ExportRangeMode
import app.watchaudio.app.state.ExportUiState
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** What the export card can ask for; implemented by [app.watchaudio.app.state.ExportViewModel]. */
internal class ExportCallbacks(
    val onMode: (ExportRangeMode) -> Unit,
    val onFrom: (LocalDate) -> Unit,
    val onTo: (LocalDate) -> Unit,
    val onIncludeAudio: (Boolean) -> Unit,
    val onExport: () -> Unit,
)

private enum class PickedEnd { FROM, TO }

/**
 * 「データの書き出し」 (#155): range and audio choice, then the platform asks where to save. The
 * data only leaves the phone through this button.
 * @param audioBytes audio on the device, to show what including it costs; null while unknown.
 */
@Composable
internal fun ExportCard(state: ExportUiState, audioBytes: Long?, callbacks: ExportCallbacks) {
    var picking by remember { mutableStateOf<PickedEnd?>(null) }
    val enabled = !state.busy
    Section(Strings.EXPORT_TITLE) {
        Text(Strings.EXPORT_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == ExportRangeMode.ALL,
                onClick = { callbacks.onMode(ExportRangeMode.ALL) },
                enabled = enabled,
                label = { Text(Strings.EXPORT_RANGE_ALL) },
            )
            FilterChip(
                selected = state.mode == ExportRangeMode.DATES,
                onClick = { callbacks.onMode(ExportRangeMode.DATES) },
                enabled = enabled,
                label = { Text(Strings.EXPORT_RANGE_DATES) },
            )
        }
        if (state.mode == ExportRangeMode.DATES) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = PickedEnd.FROM }, enabled = enabled) { Text(Strings.exportFrom(formatDate(state.from))) }
                OutlinedButton(onClick = { picking = PickedEnd.TO }, enabled = enabled) { Text(Strings.exportTo(formatDate(state.to))) }
            }
        }
        SwitchRow(
            Strings.EXPORT_INCLUDE_AUDIO,
            Strings.exportIncludeAudioDetail(formatBytes(audioBytes ?: 0)),
            state.includeAudio,
            onCheckedChange = { if (enabled) callbacks.onIncludeAudio(it) },
        )
        Button(onClick = callbacks.onExport, enabled = enabled) { Text(Strings.EXPORT_BUTTON) }
        if (state.busy) {
            Text(Strings.EXPORTING, style = MaterialTheme.typography.bodySmall)
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    picking?.let { end ->
        ExportDatePicker(
            initial = if (end == PickedEnd.FROM) state.from else state.to,
            latest = state.today,
            onPick = { date ->
                picking = null
                if (end == PickedEnd.FROM) callbacks.onFrom(date) else callbacks.onTo(date)
            },
            onDismiss = { picking = null },
        )
    }
}

/** The material date picker in a dialog; days after [latest] cannot be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDatePicker(initial: LocalDate, latest: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC midnights.
    val latestMs = latest.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= latestMs

            override fun isSelectableYear(year: Int): Boolean = year <= latest.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = pickerState.selectedDateMillis
                    if (picked == null) onDismiss() else onPick(Instant.fromEpochMilliseconds(picked).toLocalDateTime(TimeZone.UTC).date)
                },
            ) { Text(Strings.DATE_PICKER_CONFIRM) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    ) {
        DatePicker(state = pickerState)
    }
}
