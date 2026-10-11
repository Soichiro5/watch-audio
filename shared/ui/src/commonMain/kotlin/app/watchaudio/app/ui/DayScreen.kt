package app.watchaudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.DayTimeline
import app.watchaudio.app.logic.GapCause
import app.watchaudio.app.logic.TimelineSpan
import app.watchaudio.app.logic.TranscriptEntry
import app.watchaudio.app.logic.formatClock
import app.watchaudio.app.logic.formatClockRange
import app.watchaudio.app.logic.formatDate
import app.watchaudio.app.logic.formatDuration
import app.watchaudio.app.logic.formatPercent
import app.watchaudio.app.state.DayDeletion
import app.watchaudio.app.state.DayUiState
import kotlinx.datetime.TimeZone

/** Number of LazyColumn items above the transcript entries (summary, gaps, header). */
private const val HEADER_ITEMS = 3

@Composable
internal fun DayScreen(
    state: DayUiState,
    timeZone: TimeZone,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onPlay: (TranscriptEntry.Utterance) -> Unit,
    onTranscribe: (TranscriptEntry.Untranscribed) -> Unit,
    onFocusHandled: () -> Unit,
    onDeleteDay: () -> Unit,
    onConfirmDeleteDay: () -> Unit,
    onCancelDeleteDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf<Long?>(null) }
    val content = state.content

    LaunchedEffect(state.date) {
        if (state.focusSegmentId == null) listState.scrollToItem(0)
    }
    LaunchedEffect(state.focusSegmentId, content) {
        val target = state.focusSegmentId ?: return@LaunchedEffect
        if (content == null) return@LaunchedEffect
        val index = content.entries.indexOfFirst { it is TranscriptEntry.Utterance && it.segment.id == target }
        if (index >= 0) {
            highlighted = target
            listState.animateScrollToItem(HEADER_ITEMS + index)
        }
        onFocusHandled()
    }

    Column(modifier.fillMaxSize()) {
        DateBar(state, onPreviousDay, onNextDay, onToday)
        DayContentList(
            state, timeZone, listState, highlighted, onPlay, onTranscribe,
            DeleteDayCallbacks(onDeleteDay, onConfirmDeleteDay, onCancelDeleteDay),
        )
    }
}

private class DeleteDayCallbacks(val onRequest: () -> Unit, val onConfirm: () -> Unit, val onCancel: () -> Unit)

@Composable
private fun DayContentList(
    state: DayUiState,
    timeZone: TimeZone,
    listState: LazyListState,
    highlighted: Long?,
    onPlay: (TranscriptEntry.Utterance) -> Unit,
    onTranscribe: (TranscriptEntry.Untranscribed) -> Unit,
    deleteDay: DeleteDayCallbacks,
) {
    val content = state.content
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "summary") {
            if (content == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                CoverageCard(content.timeline)
            }
        }
        item(key = "gaps") {
            if (content != null) GapList(content.timeline, timeZone)
        }
        item(key = "transcriptHeader") {
            if (content != null) {
                Text(
                    Strings.TRANSCRIPT,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (content.audioPurgedChunks > 0) {
                    Text(
                        Strings.AUDIO_PURGED_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (content.entries.isEmpty()) {
                    Text(
                        if (content.timeline.hasRecording) Strings.NO_TRANSCRIPT_YET else Strings.NO_RECORDING_THIS_DAY,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (content != null) {
            items(content.entries, key = { it.key }) { entry ->
                when (entry) {
                    is TranscriptEntry.Utterance -> UtteranceRow(
                        entry = entry,
                        timeZone = timeZone,
                        highlighted = entry.segment.id == highlighted,
                        onClick = { onPlay(entry) },
                    )
                    is TranscriptEntry.Untranscribed -> UntranscribedRow(entry, timeZone) { onTranscribe(entry) }
                }
            }
            if (content.chunksStartedThisDay > 0) {
                item(key = "deleteDay") { DeleteDaySection(state, content.chunksStartedThisDay, deleteDay) }
            }
        }
    }
}

@Composable
private fun DeleteDaySection(state: DayUiState, chunks: Int, callbacks: DeleteDayCallbacks) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.deletion) {
            DayDeletion.NONE -> OutlinedButton(
                onClick = callbacks.onRequest,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(Strings.DELETE_DAY) }
            DayDeletion.CONFIRMING -> Confirmation(
                confirmLabel = Strings.DELETE,
                confirmEnabled = true,
                onConfirm = callbacks.onConfirm,
                onCancel = callbacks.onCancel,
            ) {
                Text(Strings.confirmDeleteDay(formatDate(state.date), chunks), style = MaterialTheme.typography.bodyMedium)
            }
            DayDeletion.DELETING -> {
                Text(Strings.DELETING, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DateBar(state: DayUiState, onPreviousDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousDay) { Icon(AppIcons.ChevronLeft, contentDescription = Strings.PREV_DAY) }
        Text(
            formatDate(state.date),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        if (!state.isToday) {
            TextButton(onClick = onToday) { Text(Strings.TODAY) }
        }
        IconButton(onClick = onNextDay, enabled = state.canGoForward) {
            Icon(AppIcons.ChevronRight, contentDescription = Strings.NEXT_DAY)
        }
    }
}

@Composable
private fun CoverageCard(timeline: DayTimeline) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Strings.COVERAGE, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(formatPercent(timeline.coverage), style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                Strings.coverageDetail(formatDuration(timeline.coveredMs), formatDuration(timeline.window.lengthMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Strings.audioAndSilent(formatDuration(timeline.audioMs), formatDuration(timeline.silentMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TimelineBar(timeline, description = "${Strings.COVERAGE} ${formatPercent(timeline.coverage)}")
            Spacer(Modifier.height(8.dp))
            TimelineLegend()
        }
    }
}

@Composable
private fun GapList(timeline: DayTimeline, timeZone: TimeZone) {
    // "No recording" before anything was ever recorded is not news; list only explained gaps.
    val gaps = timeline.gaps.filter { it.cause != GapCause.NoRecording && it.durationMs >= 60_000 }
    if (gaps.isEmpty()) return
    val colors = timelineColors()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(Strings.GAPS, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        for (gap in gaps) {
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendItem(colors.of(gap.cause), gapLabel(gap))
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatClockRange(gap.start, gap.end, timeZone)}（${formatDuration(gap.durationMs)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun gapLabel(gap: TimelineSpan.Gap): String = when (val cause = gap.cause) {
    is GapCause.Stopped -> Strings.endReason(cause.reason)
    GapCause.Missing -> Strings.GAP_MISSING
    GapCause.Awaiting -> Strings.GAP_AWAITING
    GapCause.NoRecording -> Strings.GAP_NO_RECORDING
}

@Composable
private fun UtteranceRow(
    entry: TranscriptEntry.Utterance,
    timeZone: TimeZone,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = entry.audioPath != null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                formatClock(entry.segment.startAt, timeZone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 48.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(entry.segment.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            // No play affordance once the audio is gone (retention): the transcript stays.
            if (entry.audioPath != null) {
                Icon(
                    AppIcons.Play,
                    contentDescription = Strings.PLAY,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun UntranscribedRow(entry: TranscriptEntry.Untranscribed, timeZone: TimeZone, onTranscribe: () -> Unit) {
    val range = formatClockRange(entry.from, entry.to, timeZone)
    val count = entry.chunkIds.size
    val failed = entry.kind == TranscriptEntry.Untranscribed.Kind.FAILED
    Card(
        colors = if (failed) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (entry.kind) {
                        TranscriptEntry.Untranscribed.Kind.PENDING -> Strings.pendingChunks(range, count)
                        TranscriptEntry.Untranscribed.Kind.TRANSCRIBING -> Strings.transcribingChunks(range, count)
                        TranscriptEntry.Untranscribed.Kind.FAILED -> Strings.failedChunks(range, count)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (failed) Text(Strings.FAILED_CHUNKS_NOTE, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.kind != TranscriptEntry.Untranscribed.Kind.TRANSCRIBING) {
                TextButton(onClick = onTranscribe) { Text(if (failed) Strings.RETRY else Strings.TRANSCRIBE_NOW) }
            }
        }
    }
}
