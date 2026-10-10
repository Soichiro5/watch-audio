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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.state.DayViewModel
import app.watchaudio.app.state.NowPlaying
import app.watchaudio.app.state.NowProvider
import app.watchaudio.app.state.SearchViewModel
import app.watchaudio.app.state.StatusViewModel
import app.watchaudio.app.ui.AppIcons
import app.watchaudio.app.ui.DayScreen
import app.watchaudio.app.ui.SearchScreen
import app.watchaudio.app.ui.StatusScreen
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

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
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
                    StatusScreen(
                        state = statusState,
                        timeZone = timeZone,
                        onDownloadModel = statusViewModel::downloadModel,
                        onTranscribeNow = statusViewModel::requestTranscription,
                        modifier = modifier,
                    )
                }
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
