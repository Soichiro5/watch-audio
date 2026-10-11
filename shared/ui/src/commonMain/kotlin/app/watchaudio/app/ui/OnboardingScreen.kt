package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.watchaudio.app.AppPermission
import app.watchaudio.app.ModelSetup
import app.watchaudio.app.PermissionStatus
import app.watchaudio.app.Strings
import app.watchaudio.app.state.OnboardingStep
import app.watchaudio.app.state.OnboardingUiState
import app.watchaudio.core.pipeline.ModelState

/** The first-run flow (#32): one page per [OnboardingStep], with back / next at the bottom. */
@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onRequestPermission: (AppPermission) -> Unit,
    onOpenAppSettings: () -> Unit,
    onDownloadModel: (allowMetered: Boolean) -> Unit,
) {
    Scaffold(
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.isFirst) TextButton(onClick = onBack) { Text(Strings.ONBOARDING_BACK) }
                Spacer(Modifier.weight(1f))
                Text(
                    Strings.onboardingProgress(state.index + 1, state.steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (state.isLast) {
                    Button(onClick = onFinish) { Text(Strings.ONBOARDING_START) }
                } else {
                    Button(onClick = onNext, enabled = state.canContinue) { Text(nextLabel(state)) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomePage()
                OnboardingStep.CONSENT -> ConsentPage(state.consentAccepted, onConsentChange)
                OnboardingStep.PERMISSIONS -> PermissionsPage(state.permissions, onRequestPermission, onOpenAppSettings)
                OnboardingStep.MODEL -> ModelPage(state.modelSetup, state.model, onDownloadModel)
                OnboardingStep.WATCH -> WatchPage()
            }
        }
    }
}

/** "Later" while an optional setup step is not done yet, otherwise "next". */
private fun nextLabel(state: OnboardingUiState): String {
    val pending = when (state.step) {
        OnboardingStep.PERMISSIONS -> state.permissions.values.any { it != PermissionStatus.GRANTED }
        OnboardingStep.MODEL -> state.model != ModelState.Ready && state.model !is ModelState.Downloading
        else -> false
    }
    return if (pending) Strings.ONBOARDING_SKIP else Strings.ONBOARDING_NEXT
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Points(points: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        points.forEach { point ->
            Row {
                Text("・", style = MaterialTheme.typography.bodyLarge)
                Text(point, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Note(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun WelcomePage() {
    Title(Strings.WELCOME_TITLE)
    Points(Strings.WELCOME_POINTS)
    Note {
        Text(Strings.PRIVACY_NOTE, style = MaterialTheme.typography.titleSmall)
        Text(Strings.WELCOME_PRIVACY_DETAIL, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConsentPage(accepted: Boolean, onConsentChange: (Boolean) -> Unit) {
    Title(Strings.CONSENT_TITLE)
    Points(Strings.CONSENT_POINTS)
    Text(
        Strings.CONSENT_DISCLAIMER,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.fillMaxWidth().toggleable(value = accepted, role = Role.Checkbox, onValueChange = onConsentChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = accepted, onCheckedChange = null)
        Text(Strings.CONSENT_CHECKBOX, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PermissionsPage(
    permissions: Map<AppPermission, PermissionStatus?>,
    onRequest: (AppPermission) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Title(Strings.PERMISSIONS_TITLE)
    permissions.forEach { (permission, status) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Strings.permissionName(permission), style = MaterialTheme.typography.titleSmall)
                Text(Strings.permissionReason(permission), style = MaterialTheme.typography.bodyMedium)
                when (status) {
                    null -> Text(Strings.PERMISSION_CHECKING, style = MaterialTheme.typography.bodySmall)
                    PermissionStatus.GRANTED -> Text(
                        Strings.PERMISSION_GRANTED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    PermissionStatus.NOT_REQUESTED -> Button(onClick = { onRequest(permission) }) { Text(Strings.PERMISSION_ALLOW) }
                    PermissionStatus.DENIED -> {
                        Text(
                            Strings.PERMISSION_DENIED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onOpenAppSettings) { Text(Strings.OPEN_SETTINGS) }
                    }
                }
            }
        }
    }
    Text(Strings.PERMISSIONS_INTRO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelPage(setup: ModelSetup, model: ModelState, onDownload: (Boolean) -> Unit) {
    Title(Strings.MODEL_SETUP_TITLE)
    Text(
        if (setup == ModelSetup.DOWNLOAD) Strings.MODEL_SETUP_DOWNLOAD else Strings.MODEL_SETUP_OS,
        style = MaterialTheme.typography.bodyLarge,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Strings.ASR_MODEL, style = MaterialTheme.typography.titleSmall)
            Text(modelStateText(model, setup), style = MaterialTheme.typography.bodyMedium)
            if (model is ModelState.Downloading) {
                LinearProgressIndicator(progress = { model.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            val canStart = model != ModelState.Ready && model !is ModelState.Downloading
            if (canStart) {
                when (setup) {
                    ModelSetup.DOWNLOAD -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(false) }) { Text(Strings.DOWNLOAD_WIFI) }
                        OutlinedButton(onClick = { onDownload(true) }) { Text(Strings.DOWNLOAD_ANY) }
                    }
                    ModelSetup.OS_MANAGED -> Button(onClick = { onDownload(false) }) { Text(Strings.MODEL_PREPARE) }
                }
            }
        }
    }
    if (setup == ModelSetup.DOWNLOAD) {
        Text(Strings.MODEL_SETUP_DOWNLOAD_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WatchPage() {
    Title(Strings.WATCH_TITLE)
    Note {
        Text(Strings.WATCH_INSTRUCTION, style = MaterialTheme.typography.titleMedium)
    }
    Points(Strings.WATCH_POINTS)
}

/** Shared with the status screen. */
internal fun modelStateText(model: ModelState, setup: ModelSetup): String = when (model) {
    ModelState.Unknown -> Strings.MODEL_UNKNOWN
    ModelState.Missing -> Strings.MODEL_MISSING
    is ModelState.Downloading -> Strings.modelDownloading((model.progress * 100).toInt().coerceIn(0, 100))
    ModelState.Ready -> Strings.MODEL_READY
    is ModelState.Failed -> Strings.modelFailed(setup)
}
