package app.watchaudio.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.PassphraseStrength
import app.watchaudio.app.logic.formatBytes
import app.watchaudio.app.state.BackupUiState
import app.watchaudio.core.backup.RestoreProgress

/** What the backup card can ask for; implemented by [app.watchaudio.app.state.BackupViewModel]. */
internal class BackupCallbacks(
    val onPassphrase: (String) -> Unit,
    val onConfirmation: (String) -> Unit,
    val onIncludeAudio: (Boolean) -> Unit,
    val onCreate: () -> Unit,
    val onRestorePassphrase: (String) -> Unit,
    val onRestore: () -> Unit,
)

/**
 * 「暗号化バックアップ」 (#40): a passphrase-encrypted file of everything, saved where the user
 * picks, and restoring one. Data only leaves the phone through 「バックアップを作成」.
 * @param audioBytes audio on the device, to show what including it costs; null while unknown.
 */
@Composable
internal fun BackupCard(state: BackupUiState, audioBytes: Long?, callbacks: BackupCallbacks) {
    val enabled = !state.busy
    Section(Strings.BACKUP_TITLE) {
        Text(Strings.BACKUP_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PassphraseField(state.passphrase, Strings.BACKUP_PASSPHRASE, enabled, callbacks.onPassphrase)
        if (state.passphrase.isNotEmpty()) {
            Text(
                strengthText(state.strength),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.strength <= PassphraseStrength.WEAK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PassphraseField(state.confirmation, Strings.BACKUP_PASSPHRASE_CONFIRM, enabled, callbacks.onConfirmation)
        if (state.confirmationMismatch) {
            Text(Strings.BACKUP_PASSPHRASE_MISMATCH, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(Strings.BACKUP_FORGOTTEN_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        SwitchRow(
            Strings.BACKUP_INCLUDE_AUDIO,
            Strings.backupIncludeAudioDetail(formatBytes(audioBytes ?: 0)),
            state.includeAudio,
            onCheckedChange = { if (enabled) callbacks.onIncludeAudio(it) },
        )
        Button(onClick = callbacks.onCreate, enabled = state.canCreate) { Text(Strings.BACKUP_CREATE_BUTTON) }
        if (state.creating) {
            Text(Strings.BACKUP_CREATING, style = MaterialTheme.typography.bodySmall)
            Progress(state.createProgress)
        }
        state.createMessage?.let { Message(it) }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(Strings.RESTORE_TITLE, style = MaterialTheme.typography.titleSmall)
        Text(Strings.RESTORE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PassphraseField(state.restorePassphrase, Strings.RESTORE_PASSPHRASE, enabled, callbacks.onRestorePassphrase)
        OutlinedButton(onClick = callbacks.onRestore, enabled = state.canRestore) { Text(Strings.RESTORE_BUTTON) }
        if (state.restoring) {
            when (val progress = state.restoreProgress) {
                null -> Unit
                RestoreProgress.Verifying -> {
                    Text(Strings.RESTORE_VERIFYING, style = MaterialTheme.typography.bodySmall)
                    Progress(null)
                }
                is RestoreProgress.Applying -> {
                    Text(Strings.restoring(progress.doneDays, progress.totalDays), style = MaterialTheme.typography.bodySmall)
                    Progress(if (progress.totalDays > 0) progress.doneDays.toFloat() / progress.totalDays else null)
                }
            }
        }
        state.restoreMessage?.let { Message(it) }
    }
}

@Composable
private fun PassphraseField(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Progress(fraction: Float?) {
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun Message(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun strengthText(strength: PassphraseStrength): String = when (strength) {
    PassphraseStrength.TOO_SHORT -> Strings.PASSPHRASE_TOO_SHORT
    PassphraseStrength.WEAK -> Strings.PASSPHRASE_WEAK
    PassphraseStrength.FAIR -> Strings.PASSPHRASE_FAIR
    PassphraseStrength.STRONG -> Strings.PASSPHRASE_STRONG
}
