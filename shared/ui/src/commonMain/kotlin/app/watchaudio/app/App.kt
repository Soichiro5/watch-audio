package app.watchaudio.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.state.DayViewModel
import app.watchaudio.app.state.BackupViewModel
import app.watchaudio.app.state.ExportViewModel
import app.watchaudio.app.state.NowPlaying
import app.watchaudio.app.state.NowProvider
import app.watchaudio.app.state.OnboardingViewModel
import app.watchaudio.app.state.SearchViewModel
import app.watchaudio.app.state.SettingsViewModel
import app.watchaudio.app.state.StatusViewModel
import app.watchaudio.app.ui.AppIcons
import app.watchaudio.app.ui.DayScreen
import app.watchaudio.app.ui.BackupCallbacks
import app.watchaudio.app.ui.ExportCallbacks
import app.watchaudio.app.ui.OnboardingScreen
import app.watchaudio.app.ui.SearchScreen
import app.watchaudio.app.ui.StatusScreen
import app.watchaudio.app.ui.StorageCallbacks
import kotlinx.datetime.TimeZone

private enum class Tab(val label: String, val icon: ImageVector) {
    TIMELINE(Strings.TAB_TIMELINE, AppIcons.Timeline),
    SEARCH(Strings.TAB_SEARCH, AppIcons.Search),
    STATUS(Strings.TAB_STATUS, AppIcons.Info),
}

/**
 * The phone UI: timeline of a day, search and status, over the platform's [services].
 * Times are shown in [timeZone] (the device's by default).
 */
@Composable
fun App(
    services: AppServices,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    clock: NowProvider = NowProvider.System,
) {
    val dayViewModel = viewModel { DayViewModel(services, timeZone, clock) }
    val searchViewModel = viewModel { SearchViewModel(services, timeZone) }
    val statusViewModel = viewModel { StatusViewModel(services, clock) }
    val exportViewModel = viewModel { ExportViewModel(services, timeZone, clock) }
    val backupViewModel = viewModel { BackupViewModel(services, timeZone, clock) }
    val settingsViewModel = viewModel { SettingsViewModel(services) }
    val onboardingViewModel = viewModel { OnboardingViewModel(services) }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
        when (settings?.onboardingCompleted) {
            // Loading: show an empty surface rather than flashing the wrong screen.
            null -> Surface(Modifier.fillMaxSize()) {}
            false -> Onboarding(onboardingViewModel, onFinish = settingsViewModel::completeOnboarding)
            true -> MainTabs(timeZone, dayViewModel, searchViewModel, statusViewModel, exportViewModel, backupViewModel, settingsViewModel) {
                onboardingViewModel.restart()
                settingsViewModel.showOnboardingAgain()
            }
        }
    }
}

@Composable
private fun Onboarding(viewModel: OnboardingViewModel, onFinish: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Permission states can change outside the app (system settings); re-read them on resume.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPermissions()
        onPauseOrDispose {}
    }
    OnboardingScreen(
        state = state,
        onBack = viewModel::back,
        onNext = viewModel::next,
        onFinish = onFinish,
        onConsentChange = viewModel::setConsent,
        onRequestPermission = viewModel::requestPermission,
        onOpenAppSettings = viewModel::openAppSettings,
        onDownloadModel = viewModel::downloadModels,
    )
}

@Composable
private fun MainTabs(
    timeZone: TimeZone,
    dayViewModel: DayViewModel,
    searchViewModel: SearchViewModel,
    statusViewModel: StatusViewModel,
    exportViewModel: ExportViewModel,
    backupViewModel: BackupViewModel,
    settingsViewModel: SettingsViewModel,
    onShowOnboarding: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(Tab.TIMELINE.ordinal) }
    val tab = Tab.entries[tabIndex]
    val dayState by dayViewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Column {
                dayState.nowPlaying?.let { PlaybackBar(it, timeZone, onStop = dayViewModel::stopPlayback) }
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == tab,
                            onClick = { tabIndex = item.ordinal },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (tab) {
            Tab.TIMELINE -> DayScreen(
                state = dayState,
                timeZone = timeZone,
                onPreviousDay = dayViewModel::previousDay,
                onNextDay = dayViewModel::nextDay,
                onToday = dayViewModel::goToToday,
                onPlay = dayViewModel::play,
                onTranscribe = dayViewModel::transcribe,
                onFocusHandled = dayViewModel::focusHandled,
                onDeleteDay = dayViewModel::requestDeleteDay,
                onConfirmDeleteDay = dayViewModel::confirmDeleteDay,
                onCancelDeleteDay = dayViewModel::cancelDeleteDay,
                modifier = modifier,
            )
            Tab.SEARCH -> {
                val query by searchViewModel.query.collectAsStateWithLifecycle()
                val results by searchViewModel.results.collectAsStateWithLifecycle()
                val searching by searchViewModel.searching.collectAsStateWithLifecycle()
                SearchScreen(
                    query = query,
                    results = results,
                    searching = searching,
                    onQueryChange = searchViewModel::setQuery,
                    onOpen = { result ->
                        dayViewModel.openSegment(result.chunkId, result.segmentId, result.startAt)
                        tabIndex = Tab.TIMELINE.ordinal
                    },
                    modifier = modifier,
                )
            }
            Tab.STATUS -> {
                val statusState by statusViewModel.state.collectAsStateWithLifecycle()
                val exportState by exportViewModel.state.collectAsStateWithLifecycle()
                val backupState by backupViewModel.state.collectAsStateWithLifecycle()
                val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
                StatusScreen(
                    state = statusState,
                    timeZone = timeZone,
                    onDownloadModel = statusViewModel::downloadModels,
                    onTranscribeNow = statusViewModel::requestTranscription,
                    settings = settings,
                    onGapAlertsEnabledChange = settingsViewModel::setGapAlertsEnabled,
                    onQuietHoursChange = settingsViewModel::setQuietHours,
                    onTranscribeOnlyWhileChargingChange = settingsViewModel::setTranscribeOnlyWhileCharging,
                    onShowOnboarding = onShowOnboarding,
                    storageCallbacks = StorageCallbacks(
                        onSelectRetention = statusViewModel::selectRetention,
                        onConfirmRetention = statusViewModel::confirmRetention,
                        onCancelRetention = statusViewModel::cancelRetention,
                        onOpenOffload = statusViewModel::openOffload,
                        onSelectOffloadAge = statusViewModel::selectOffloadAge,
                        onConfirmOffload = statusViewModel::confirmOffload,
                        onCancelOffload = statusViewModel::cancelOffload,
                        onOpenDeleteAll = statusViewModel::openDeleteAll,
                        onDeleteAllInput = statusViewModel::updateDeleteAllInput,
                        onConfirmDeleteAll = {
                            // The day screen's playback bar must not outlive the data.
                            if (statusViewModel.confirmDeleteAll()) dayViewModel.stopPlayback()
                        },
                        onCancelDeleteAll = statusViewModel::cancelDeleteAll,
                    ),
                    onExportDiagnostics = statusViewModel::exportDiagnostics,
                    export = exportState,
                    exportCallbacks = ExportCallbacks(
                        onMode = exportViewModel::setMode,
                        onFrom = exportViewModel::setFrom,
                        onTo = exportViewModel::setTo,
                        onIncludeAudio = exportViewModel::setIncludeAudio,
                        onExport = exportViewModel::export,
                    ),
                    backup = backupState,
                    backupCallbacks = BackupCallbacks(
                        onPassphrase = backupViewModel::setPassphrase,
                        onConfirmation = backupViewModel::setConfirmation,
                        onIncludeAudio = backupViewModel::setIncludeAudio,
                        onCreate = backupViewModel::create,
                        onRestorePassphrase = backupViewModel::setRestorePassphrase,
                        onRestore = backupViewModel::restore,
                    ),
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun PlaybackBar(nowPlaying: NowPlaying, timeZone: TimeZone, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Play, contentDescription = null)
            Text(
                Strings.playingFrom(formatClock(nowPlaying.startsAt, timeZone)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onStop) {
                Icon(AppIcons.Stop, contentDescription = null)
                Text(Strings.STOP)
            }
        }
    }
}
