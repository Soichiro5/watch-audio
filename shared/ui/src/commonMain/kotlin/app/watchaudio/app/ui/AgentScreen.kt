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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatDateShort
import app.watchaudio.app.state.AccessLogRow
import app.watchaudio.app.state.AgentUiState
import app.watchaudio.app.state.PairingStep
import app.watchaudio.core.agent.AgentLimits
import app.watchaudio.core.agent.LinkState
import app.watchaudio.core.db.AgentPairing
import app.watchaudio.core.timeline.localDateOf
import kotlinx.datetime.TimeZone

/** What the 「エージェント連携」 screen can do (see [app.watchaudio.app.state.AgentViewModel]). */
internal class AgentCallbacks(
    val onBack: () -> Unit,
    val onSwitch: (Boolean) -> Unit,
    val onMinutes: (Int) -> Unit,
    val onScan: () -> Unit,
    val onOpenEntry: () -> Unit,
    val onEntryText: (String) -> Unit,
    val onSubmitEntry: () -> Unit,
    val onConfirmCode: (Boolean) -> Unit,
    val onDismissPairing: () -> Unit,
    val onScopeDays: (AgentPairing, Int) -> Unit,
    val onAllowAudio: (AgentPairing, Boolean) -> Unit,
    val onRequestRevoke: (AgentPairing) -> Unit,
    val onConfirmRevoke: () -> Unit,
    val onCancelRevoke: () -> Unit,
)

/**
 * Agent access (#184, docs/research/agent-access.md §4.4): the on/off switch with its auto-off,
 * pairing a PC (QR or the typed string, then the 6-digit code), each pairing's scope and
 * revocation, and the access log. Opens over the tabs from the status tab.
 */
@Composable
internal fun AgentScreen(state: AgentUiState, timeZone: TimeZone, callbacks: AgentCallbacks, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onBack) {
                    Icon(AppIcons.ChevronLeft, contentDescription = null)
                    Text(Strings.AGENT_BACK)
                }
                Text(Strings.AGENT_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(Strings.AGENT_INTRO, style = MaterialTheme.typography.bodyMedium)
                Note(Strings.AGENT_CLOUD_NOTE)
                SwitchCard(state, timeZone, callbacks)
                PairingCard(state, callbacks)
                if (state.pairings.isNotEmpty()) PairingsCard(state, timeZone, callbacks)
                HistoryCard(state.log, timeZone)
            }
        }
    }
}

@Composable
private fun SwitchCard(state: AgentUiState, timeZone: TimeZone, callbacks: AgentCallbacks) {
    val offAt = state.access.offAt
    val enabled = state.pairings.isNotEmpty() || offAt != null
    Section(Strings.AGENT_SWITCH_SECTION) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = offAt != null, enabled = enabled, role = Role.Switch, onValueChange = callbacks.onSwitch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(Strings.AGENT_SWITCH, style = MaterialTheme.typography.bodyMedium)
                Note(if (offAt != null) Strings.agentOnUntil(formatClock(offAt, timeZone)) else Strings.AGENT_SWITCH_DETAIL_OFF)
            }
            Switch(checked = offAt != null, onCheckedChange = null, enabled = enabled)
        }
        if (state.foregroundOnly) Note(Strings.AGENT_FOREGROUND_ONLY)
        if (state.pairings.isEmpty()) Note(Strings.AGENT_NO_PAIRINGS)
        if (offAt == null && state.pairings.isNotEmpty()) {
            Text(Strings.AGENT_DURATION, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentLimits.ON_MINUTES_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = state.onMinutes == minutes,
                        onClick = { callbacks.onMinutes(minutes) },
                        label = { Text(Strings.agentMinutes(minutes)) },
                    )
                }
            }
        }
        if (offAt != null) {
            state.pairings.forEach { pairing ->
                val link = state.access.links[pairing.id]
                Text(
                    "${pairing.name}: " + if (link == LinkState.CONNECTED) Strings.AGENT_LINK_CONNECTED else Strings.AGENT_LINK_SEARCHING,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (link == LinkState.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (offAt != null && state.access.localNetworkDenied) {
            Text(Strings.agentPermissionDenied(state.localNetworkSetting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PairingCard(state: AgentUiState, callbacks: AgentCallbacks) {
    Section(Strings.AGENT_PAIR_TITLE) {
        when (val step = state.pairing) {
            PairingStep.Idle -> {
                Note(Strings.AGENT_PAIR_HOWTO)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canScanQr) Button(onClick = callbacks.onScan) { Text(Strings.agentScan(state.qrScanner)) }
                    OutlinedButton(onClick = callbacks.onOpenEntry) { Text(Strings.AGENT_ENTER) }
                }
                if (state.canScanQr) Note(Strings.agentScanNote(state.qrScanner))
            }
            is PairingStep.Entering -> {
                Note(Strings.AGENT_PAIR_HOWTO)
                OutlinedTextField(
                    value = step.text,
                    onValueChange = callbacks.onEntryText,
                    label = { Text(Strings.AGENT_ENTER_LABEL) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = callbacks.onSubmitEntry, enabled = step.text.isNotBlank()) { Text(Strings.AGENT_ENTER_SUBMIT) }
                    OutlinedButton(onClick = callbacks.onDismissPairing) { Text(Strings.AGENT_CANCEL) }
                }
            }
            PairingStep.Scanning -> Busy(Strings.AGENT_SCANNING, callbacks)
            is PairingStep.Connecting -> Busy(Strings.agentConnecting(step.pcName), callbacks)
            is PairingStep.Confirm -> {
                Text(Strings.agentConfirmTitle(step.pcName), style = MaterialTheme.typography.bodyMedium)
                Text(
                    step.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Note(Strings.AGENT_CONFIRM_NOTE)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { callbacks.onConfirmCode(true) }) { Text(Strings.AGENT_CODE_MATCHES) }
                    OutlinedButton(onClick = { callbacks.onConfirmCode(false) }) { Text(Strings.AGENT_CODE_DIFFERS) }
                }
            }
            is PairingStep.WaitingForPc -> Busy(Strings.AGENT_WAITING_PC, callbacks)
            is PairingStep.Paired -> {
                Text(Strings.agentPaired(step.pcName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = callbacks.onDismissPairing) { Text(Strings.AGENT_CLOSE) }
            }
            is PairingStep.Failed -> {
                Text(step.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = callbacks.onDismissPairing) { Text(Strings.AGENT_CLOSE) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Busy(text: String, callbacks: AgentCallbacks) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(Modifier.fillMaxWidth())
    OutlinedButton(onClick = callbacks.onDismissPairing) { Text(Strings.AGENT_CANCEL) }
}

@Composable
private fun PairingsCard(state: AgentUiState, timeZone: TimeZone, callbacks: AgentCallbacks) {
    Section(Strings.AGENT_PAIRINGS) {
        state.pairings.forEachIndexed { index, pairing ->
            if (index > 0) HorizontalDivider()
            Text(pairing.name, style = MaterialTheme.typography.bodyLarge)
            Note(Strings.agentPairedAt(formatDateShort(localDateOf(pairing.createdAt, timeZone))))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Strings.AGENT_SCOPE_PERIOD, style = MaterialTheme.typography.bodyMedium)
                ScopePicker(pairing.scopeDays) { callbacks.onScopeDays(pairing, it) }
            }
            SwitchRow(Strings.AGENT_ALLOW_AUDIO, Strings.AGENT_ALLOW_AUDIO_DETAIL, pairing.allowAudio) { callbacks.onAllowAudio(pairing, it) }
            if (state.revoking?.id == pairing.id) {
                Text(Strings.agentConfirmRevoke(pairing.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = callbacks.onConfirmRevoke) { Text(Strings.AGENT_REVOKE_CONFIRM) }
                    OutlinedButton(onClick = callbacks.onCancelRevoke) { Text(Strings.AGENT_CANCEL) }
                }
            } else {
                TextButton(onClick = { callbacks.onRequestRevoke(pairing) }) {
                    Text(Strings.AGENT_REVOKE, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ScopePicker(days: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(Strings.agentScopeDays(days)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (AgentLimits.SCOPE_DAYS_CHOICES + days).distinct().sorted().forEach { choice ->
                DropdownMenuItem(
                    text = { Text(Strings.agentScopeDays(choice)) },
                    onClick = {
                        open = false
                        onSelect(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(log: List<AccessLogRow>, timeZone: TimeZone) {
    Section(Strings.AGENT_HISTORY) {
        Note(Strings.agentHistoryNote(AgentLimits.ACCESS_LOG_RETENTION_DAYS))
        if (log.isEmpty()) {
            Text(Strings.AGENT_HISTORY_EMPTY, style = MaterialTheme.typography.bodyMedium)
        }
        log.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider()
            val entry = row.entry
            val time = "${formatDateShort(localDateOf(entry.at, timeZone))} ${formatClock(entry.at, timeZone)}"
            Text(
                "$time  ${row.pcName}  ${Strings.agentTool(entry.tool)} ${Strings.agentItems(entry.itemCount)}",
                style = MaterialTheme.typography.bodySmall,
            )
            entry.query?.let { Note("「$it」") }
            Strings.agentOutcome(entry.outcome)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
