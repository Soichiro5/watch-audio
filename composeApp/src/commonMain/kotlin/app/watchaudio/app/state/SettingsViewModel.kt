package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.logic.QuietHours
import app.watchaudio.app.settings.AppSettings
import app.watchaudio.app.settings.loadSettings
import app.watchaudio.app.settings.updateSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The persisted [AppSettings] (null until loaded) and the actions that change them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val services: AppServices,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    val settings: StateFlow<AppSettings?> = services.store.changes
        .mapLatest { services.store.loadSettings() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun completeOnboarding() = update { it.copy(onboardingCompleted = true) }

    fun showOnboardingAgain() = update { it.copy(onboardingCompleted = false) }

    fun setGapAlertsEnabled(enabled: Boolean) = update(alertSettings = true) {
        it.copy(gapAlert = it.gapAlert.copy(enabled = enabled))
    }

    fun setQuietHours(quietHours: QuietHours) = update(alertSettings = true) {
        it.copy(gapAlert = it.gapAlert.copy(quietHours = quietHours))
    }

    private fun update(alertSettings: Boolean = false, transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            services.store.updateSettings(transform)
            if (alertSettings) services.gapAlertSettingsChanged()
        }
    }
}
