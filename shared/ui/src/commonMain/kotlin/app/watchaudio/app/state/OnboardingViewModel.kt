package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppPermission
import app.watchaudio.app.AppServices
import app.watchaudio.app.ModelSetup
import app.watchaudio.app.PermissionStatus
import app.watchaudio.core.pipeline.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pages of the first-run flow (#32), in order. */
enum class OnboardingStep {
    /** What the app does and the privacy promise. */
    WELCOME,

    /** Recording other people: notice and consent (required to continue). */
    CONSENT,

    /** Platform permissions; skipped when the platform needs none. */
    PERMISSIONS,

    /** Speech model download / installation. */
    MODEL,

    /** Start recording on the watch. */
    WATCH,
}

data class OnboardingUiState(
    val steps: List<OnboardingStep>,
    val step: OnboardingStep,
    val consentAccepted: Boolean = false,
    /** Permissions in request order; a missing value has not been looked up yet. */
    val permissions: Map<AppPermission, PermissionStatus?> = emptyMap(),
    val modelSetup: ModelSetup = ModelSetup.DOWNLOAD,
    val model: ModelState = ModelState.Unknown,
) {
    val index: Int get() = steps.indexOf(step)
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == steps.lastIndex
    val canContinue: Boolean get() = step != OnboardingStep.CONSENT || consentAccepted
}

/**
 * State of the first-run flow. Completion is stored by [SettingsViewModel.completeOnboarding];
 * permissions and the model are optional (everything can be done later from the status tab).
 */
class OnboardingViewModel(
    private val services: AppServices,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val steps = OnboardingStep.entries.filter { it != OnboardingStep.PERMISSIONS || services.permissions.isNotEmpty() }
    private val local = MutableStateFlow(
        OnboardingUiState(
            steps = steps,
            step = steps.first(),
            permissions = services.permissions.associateWith { null },
            modelSetup = services.modelSetup,
        ),
    )

    val state: StateFlow<OnboardingUiState> = combine(local, services.status) { state, status ->
        state.copy(model = status.asrModel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun next() = local.update { s -> if (s.canContinue && !s.isLast) s.copy(step = s.steps[s.index + 1]) else s }

    fun back() = local.update { s -> if (!s.isFirst) s.copy(step = s.steps[s.index - 1]) else s }

    /** Back to the first page (when the guide is opened again from the status tab). */
    fun restart() = local.update { it.copy(step = it.steps.first()) }

    fun setConsent(accepted: Boolean) = local.update { it.copy(consentAccepted = accepted) }

    /** Re-reads the permission states (e.g. after returning from the system settings). */
    fun refreshPermissions() {
        viewModelScope.launch {
            services.permissions.forEach { permission -> setPermission(permission, services.permissionStatus(permission)) }
        }
    }

    fun requestPermission(permission: AppPermission) {
        viewModelScope.launch { setPermission(permission, services.requestPermission(permission)) }
    }

    /**
     * A refused permission can only be granted in the system settings: opens the app's page there
     * (only while one is refused). [refreshPermissions] picks up the change on resume.
     */
    fun openAppSettings() {
        if (local.value.permissions.values.any { it == PermissionStatus.DENIED }) services.openAppSettings()
    }

    fun downloadModels(allowMetered: Boolean) = services.downloadModels(allowMetered)

    private fun setPermission(permission: AppPermission, status: PermissionStatus) =
        local.update { it.copy(permissions = it.permissions + (permission to status)) }
}
