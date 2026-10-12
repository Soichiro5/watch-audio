package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.watchaudio.app.ModelSetup
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.ArrivalState
import app.watchaudio.app.logic.Thresholds
import app.watchaudio.app.logic.formatAgo
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatHours
import app.watchaudio.app.logic.formatMinuteOfDay
import app.watchaudio.app.state.DiagnosticsExport
import app.watchaudio.app.state.StatusUiState
import app.watchaudio.core.alert.GapAlertSettings
import app.watchaudio.core.alert.QuietHours
import app.watchaudio.core.db.StoreStats
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.ProcessingPolicy
import app.watchaudio.core.settings.AppSettings
import kotlinx.datetime.TimeZone

@Composable
internal fun StatusScreen(
    state: StatusUiState,
    timeZone: TimeZone,
    onDownloadModel: (allowMetered: Boolean) -> Unit,
    onTranscribeNow: () -> Unit,
    settings: AppSettings?,
    onGapAlertsEnabledChange: (Boolean) -> Unit,
    onQuietHoursChange: (QuietHours) -> Unit,
    onTranscribeOnlyWhileChargingChange: (Boolean) -> Unit,
    onShowOnboarding: () -> Unit,
    onTranscribeEnglishChange: (Boolean) -> Unit = {},
    onDownloadEnglishModel: () -> Unit = {},
    storageCallbacks: StorageCallbacks,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArrivalCard(state, timeZone)
        ModelCard(state, onDownloadModel, onTranscribeNow)
        settings?.let { TranscriptionSettingsCard(it.transcribeOnlyWhileCharging, onTranscribeOnlyWhileChargingChange) }
        if (state.supportsEnglish) {
            settings?.let { LanguageSettingsCard(it.transcribeEnglish, state, onTranscribeEnglishChange, onDownloadEnglishModel) }
        }
        settings?.let { AlertSettingsCard(it.gapAlert, onGapAlertsEnabledChange, onQuietHoursChange) }
        state.stats?.let { StatsCard(it) }
        state.storage?.let { StorageCard(it, state.storageActions, storageCallbacks) }
        Section(Strings.PRIVACY_TITLE) {
            Text(Strings.PRIVACY_NOTE, style = MaterialTheme.typography.bodyMedium)
        }
        DiagnosticsCard(state.diagnosticsExport, onExportDiagnostics)
        Section(Strings.GUIDE_TITLE) {
            OutlinedButton(onClick = onShowOnboarding) { Text(Strings.SHOW_ONBOARDING_AGAIN) }
        }
    }
}

/** The on-device diagnostics log (#77); it only leaves the phone through this button. */
@Composable
private fun DiagnosticsCard(export: DiagnosticsExport, onExport: () -> Unit) {
    Section(Strings.DIAGNOSTICS_TITLE) {
        Text(Strings.DIAGNOSTICS_NOTE, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onExport, enabled = !export.busy) { Text(Strings.EXPORT_DIAGNOSTICS) }
        export.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** When the phone transcribes (#75): the charging-only switch and the on-battery rules. */
@Composable
private fun TranscriptionSettingsCard(chargingOnly: Boolean, onChange: (Boolean) -> Unit) {
    Section(Strings.TRANSCRIPTION_SETTINGS_TITLE) {
        SwitchRow(Strings.CHARGING_ONLY_SWITCH, Strings.CHARGING_ONLY_DETAIL, chargingOnly, onChange)
        if (!chargingOnly) {
            val limits = ProcessingPolicy.Limits()
            Text(
                Strings.batteryPolicyNote(limits.minBatteryPercent, limits.batteryAudioMsPerWindow / 60_000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlertSettingsCard(
    settings: GapAlertSettings,
    onEnabledChange: (Boolean) -> Unit,
    onQuietHoursChange: (QuietHours) -> Unit,
) {
    Section(Strings.ALERTS_TITLE) {
        SwitchRow(
            Strings.GAP_ALERT_SWITCH,
            Strings.gapAlertSwitchDetail(Thresholds.ARRIVAL_WARNING_MS / 60_000),
            settings.enabled,
            onEnabledChange,
        )
        if (settings.enabled) {
            val quiet = settings.quietHours
            SwitchRow(Strings.QUIET_HOURS_SWITCH, Strings.QUIET_HOURS_DETAIL, quiet.enabled) {
                onQuietHoursChange(quiet.copy(enabled = it))
            }
            if (quiet.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HourPicker(Strings.QUIET_HOURS_FROM, quiet.startMinute) { onQuietHoursChange(quiet.copy(startMinute = it)) }
                    Text("–")
                    HourPicker(Strings.QUIET_HOURS_TO, quiet.endMinute) { onQuietHoursChange(quiet.copy(endMinute = it)) }
                }
            }
        }
    }
}

/**
 * Which languages are transcribed (#81): Japanese always, English with the opt-in switch and its
 * extra model, whose download state shows below the switch.
 */
@Composable
private fun LanguageSettingsCard(english: Boolean, state: StatusUiState, onChange: (Boolean) -> Unit, onDownloadNow: () -> Unit) {
    Section(Strings.LANGUAGE_SETTINGS_TITLE) {
        SwitchRow(Strings.ENGLISH_SWITCH, Strings.englishDetail(ENGLISH_MODEL_MEGABYTES), english, onChange)
        val model = state.status.englishModel
        if (english && model != null) {
            Text(
                Strings.englishModelState(model, state.status.englishModelWaitingForWifi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model is ModelState.Downloading) LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
            if (model == ModelState.Missing || model is ModelState.Failed) {
                OutlinedButton(onClick = onDownloadNow) { Text(Strings.ENGLISH_DOWNLOAD_NOW) }
            }
        }
    }
}

/** Size of the English model download shown in the switch's description (SenseVoice Small int8). */
private const val ENGLISH_MODEL_MEGABYTES = 240

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A button showing `HH:00` that opens a menu of whole hours. */
@Composable
private fun HourPicker(label: String, minuteOfDay: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label ${formatMinuteOfDay(minuteOfDay)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0 until 24).forEach { hour ->
                DropdownMenuItem(
                    text = { Text(formatMinuteOfDay(hour * 60)) },
                    onClick = {
                        open = false
                        onSelect(hour * 60)
                    },
                )
            }
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
        Text(modelStateText(model, state.modelSetup), style = MaterialTheme.typography.bodyMedium)
        if (model is ModelState.Downloading) {
            LinearProgressIndicator(progress = { model.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (model == ModelState.Missing || model is ModelState.Failed) {
            when (state.modelSetup) {
                ModelSetup.DOWNLOAD -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDownloadModel(false) }) { Text(Strings.DOWNLOAD_WIFI) }
                    OutlinedButton(onClick = { onDownloadModel(true) }) { Text(Strings.DOWNLOAD_ANY) }
                }
                ModelSetup.OS_MANAGED -> Button(onClick = { onDownloadModel(false) }) { Text(Strings.MODEL_PREPARE) }
            }
        }
        if (state.status.transcribing) {
            Text(Strings.TRANSCRIBING_NOW, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (model == ModelState.Ready && (state.stats?.pendingChunks ?: 0) > 0) {
            OutlinedButton(onClick = onTranscribeNow) { Text(Strings.TRANSCRIBE_NOW) }
        }
        state.status.paused?.takeIf { !state.status.transcribing && (state.stats?.pendingChunks ?: 0) > 0 }?.let {
            Text(
                Strings.transcriptionPaused(it, ProcessingPolicy.Limits().minBatteryPercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.status.notice?.let {
            Text(Strings.statusNotice(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatsCard(stats: StoreStats) {
    Section(Strings.STORAGE) {
        StatRow(Strings.STAT_RECORDED, formatHours(stats.recordedMs))
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
internal fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
internal fun Section(title: String, container: Color? = null, content: @Composable ColumnScope.() -> Unit) {
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
