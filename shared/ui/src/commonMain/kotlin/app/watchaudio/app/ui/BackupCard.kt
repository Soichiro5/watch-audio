package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import app.watchaudio.app.logic.formatBackupSize
import app.watchaudio.app.logic.formatBytes
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatDate
import app.watchaudio.app.state.BackupUiState
import app.watchaudio.core.backup.BackupKind
import app.watchaudio.core.backup.RestoreProgress
import app.watchaudio.core.db.SavedBackups
import app.watchaudio.core.timeline.localDateOf
import kotlinx.datetime.TimeZone

/** What the backup card can ask for; implemented by [app.watchaudio.app.state.BackupViewModel]. */
internal class BackupCallbacks(
    val onPassphrase: (String) -> Unit,
    val onConfirmation: (String) -> Unit,
    val onIncludeAudio: (Boolean) -> Unit,
    val onKind: (BackupKind) -> Unit,
    val onCreate: () -> Unit,
    val onRestorePassphrase: (String) -> Unit,
    val onRestore: () -> Unit,
)

/**
 * 「暗号化バックアップ」 (#40): a passphrase-encrypted file of everything (full) or of what changed
 * since the last saved backup (diff, #164), saved where the user picks, and restoring either.
 * Data only leaves the phone through 「バックアップを作成」.
 * @param audioBytes audio on the device, to show what including it costs; null while unknown.
 */
@Composable
internal fun BackupCard(state: BackupUiState, audioBytes: Long?, timeZone: TimeZone, callbacks: BackupCallbacks) {
    val enabled = !state.busy
    Section(Strings.BACKUP_TITLE) {
        Text(Strings.BACKUP_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.preview?.let { Text(lastBackupText(it.saved, timeZone), style = MaterialTheme.typography.bodyMedium) }
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
        KindChoice(state, enabled, callbacks.onKind)
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

/** Full backup or diff (#164), with what each would hold; a diff only once one can be made. */
@Composable
private fun KindChoice(state: BackupUiState, enabled: Boolean, onKind: (BackupKind) -> Unit) {
    val preview = state.preview ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.kind == BackupKind.FULL,
            onClick = { onKind(BackupKind.FULL) },
            enabled = enabled,
            label = { Text(Strings.BACKUP_KIND_FULL) },
        )
        FilterChip(
            selected = state.kind == BackupKind.DIFF,
            onClick = { onKind(BackupKind.DIFF) },
            enabled = enabled && state.diffPossible,
            label = { Text(Strings.BACKUP_KIND_DIFF) },
        )
    }
    val diff = preview.diff
    val detail = when {
        state.kind == BackupKind.DIFF && diff != null && diff.chunks == 0 -> Strings.BACKUP_NOTHING_CHANGED
        state.kind == BackupKind.DIFF && diff != null -> Strings.backupDiffDetail(diff.chunks, diff.audioFiles, formatBackupSize(diff.bytes))
        else -> Strings.backupFullDetail(preview.fullChunks, formatBackupSize(preview.fullBytes))
    }
    Text(detail, style = MaterialTheme.typography.bodySmall)
    val note = when {
        state.kind == BackupKind.DIFF -> Strings.BACKUP_DIFF_KEEP_FILES
        diff != null -> null
        preview.saved.deletedSinceFull -> Strings.BACKUP_DIFF_AFTER_DELETE
        else -> Strings.BACKUP_DIFF_NOT_YET
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun lastBackupText(saved: SavedBackups, timeZone: TimeZone): String {
    fun dateTime(at: Long) = "${formatDate(localDateOf(at, timeZone))} ${formatClock(at, timeZone)}"
    val last = saved.lastAt ?: return Strings.BACKUP_NONE_SAVED
    val full = saved.fullAt
    return if (saved.lastIsDiff && full != null) {
        Strings.lastBackupDiff(dateTime(last), formatDate(localDateOf(full, timeZone)), saved.diffsSinceFull)
    } else {
        Strings.lastBackupFull(dateTime(last))
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
