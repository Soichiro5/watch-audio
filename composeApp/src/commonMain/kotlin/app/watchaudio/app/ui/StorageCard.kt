package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatBytes
import app.watchaudio.app.state.StorageActions
import app.watchaudio.app.state.StorageUiState
import app.watchaudio.core.pipeline.AudioRetention

/** What the storage card can ask for; implemented by [app.watchaudio.app.state.StatusViewModel]. */
internal class StorageCallbacks(
    val onSelectRetention: (AudioRetention) -> Unit,
    val onConfirmRetention: () -> Unit,
    val onCancelRetention: () -> Unit,
    val onOpenDeleteAll: () -> Unit,
    val onDeleteAllInput: (String) -> Unit,
    val onConfirmDeleteAll: () -> Unit,
    val onCancelDeleteAll: () -> Unit,
)

/** Usage breakdown, low-space warning, audio retention and 「すべてのデータを削除」 (#33). */
@Composable
internal fun StorageCard(storage: StorageUiState, actions: StorageActions, callbacks: StorageCallbacks) {
    val breakdown = storage.breakdown
    Section(
        Strings.STORAGE_USAGE,
        container = if (breakdown.isLowOnSpace) MaterialTheme.colorScheme.errorContainer else null,
    ) {
        if (breakdown.isLowOnSpace) {
            Text(
                Strings.lowFreeSpace(formatBytes(breakdown.freeBytes ?: 0)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        StatRow(Strings.USAGE_AUDIO, formatBytes(breakdown.audioBytes))
        StatRow(Strings.USAGE_DATABASE, formatBytes(breakdown.databaseBytes))
        StatRow(Strings.USAGE_MODELS, breakdown.modelBytes?.let(::formatBytes) ?: Strings.USAGE_MODELS_BY_OS)
        StatRow(Strings.USAGE_TOTAL, formatBytes(breakdown.totalBytes))
        breakdown.freeBytes?.let { StatRow(Strings.USAGE_FREE, formatBytes(it)) }
        if (breakdown.audioPurgedChunks > 0) {
            Text(Strings.audioPurgedChunks(breakdown.audioPurgedChunks), style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(Strings.RETENTION, style = MaterialTheme.typography.titleSmall)
        Text(
            Strings.RETENTION_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioRetention.entries.forEach { option ->
                FilterChip(
                    selected = option == storage.retention,
                    onClick = { callbacks.onSelectRetention(option) },
                    enabled = !actions.busy,
                    label = { Text(Strings.retention(option)) },
                )
            }
        }
        actions.pendingRetention?.let { pending ->
            Confirmation(
                confirmLabel = Strings.CHANGE,
                confirmEnabled = !actions.busy,
                onConfirm = callbacks.onConfirmRetention,
                onCancel = callbacks.onCancelRetention,
            ) {
                Text(
                    Strings.confirmRetention(
                        Strings.retention(pending.target),
                        pending.usage.chunks,
                        formatBytes(pending.usage.bytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val input = actions.deleteAllInput
        if (input == null) {
            OutlinedButton(
                onClick = callbacks.onOpenDeleteAll,
                enabled = !actions.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(Strings.DELETE_ALL) }
        } else {
            Confirmation(
                confirmLabel = Strings.DELETE_ALL_CONFIRM,
                confirmEnabled = actions.canConfirmDeleteAll,
                onConfirm = callbacks.onConfirmDeleteAll,
                onCancel = callbacks.onCancelDeleteAll,
            ) {
                Text(Strings.DELETE_ALL_NOTE, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = callbacks.onDeleteAllInput,
                    label = { Text(Strings.DELETE_ALL_INPUT) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (actions.busy) {
            Text(Strings.DELETING, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        actions.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** In-app confirmation step for a destructive action (no system dialog). */
@Composable
internal fun Confirmation(
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(confirmLabel) }
                TextButton(onClick = onCancel) { Text(Strings.CANCEL) }
            }
        }
    }
}
