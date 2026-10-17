package app.watchaudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatClockRange
import app.watchaudio.app.logic.formatPercent
import app.watchaudio.app.state.DaySummaries
import app.watchaudio.app.state.DayUiState
import app.watchaudio.app.state.SummaryNotice
import app.watchaudio.core.db.HighlightRecord
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.summary.HourAction
import app.watchaudio.core.summary.HourPlan
import app.watchaudio.core.summary.LlmAvailability
import kotlinx.datetime.TimeZone

/** What the hour summaries of the day screen can ask for (#27). */
internal class SummaryCallbacks(
    val onSummarizeDay: () -> Unit,
    val onStop: () -> Unit,
    val onRetry: (hourStart: Long) -> Unit,
    val onOpenHighlight: (HighlightRecord) -> Unit,
)

/**
 * 「1 時間ごとの要約」 on the day screen (#27, docs/research/on-device-llm.md §4.6): whether the
 * on-device LLM can be used, the 「この日を要約」 button and its progress, then one row per hour with
 * speech: its summary and highlights (a tap opens the cited utterance and plays it) or why there is
 * none. Without an on-device LLM only one line says so; nothing else changes on the screen.
 */
@Composable
internal fun SummaryCard(state: DayUiState, summaries: DaySummaries, timeZone: TimeZone, callbacks: SummaryCallbacks) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Strings.SUMMARIES, style = MaterialTheme.typography.titleSmall)
            val availability = summaries.availability
            if (availability is LlmAvailability.Unsupported) {
                Note(Strings.SUMMARY_UNSUPPORTED)
                return@Column
            }
            val available = availability is LlmAvailability.Available
            val run = state.summaryRun
            when {
                run != null && run.preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            run.downloaded?.let { Strings.summaryDownloading(formatPercent(it)) } ?: Strings.SUMMARY_GETTING_MODEL,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = callbacks.onStop) { Text(Strings.SUMMARY_STOP) }
                    }
                    val downloaded = run.downloaded
                    if (downloaded != null) {
                        LinearProgressIndicator(progress = { downloaded }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                // The OS downloads its model only after the user's tap (Android AICore, #205).
                availability is LlmAvailability.NeedsDownload && run == null && summaries.toSummarize > 0 -> {
                    Note(availabilityText(availability))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Strings.summaryTodo(summaries.toSummarize), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Button(onClick = callbacks.onSummarizeDay) { Text(Strings.SUMMARIZE_DAY) }
                    }
                }
                // A run that has just got the model goes on while the day is re-read.
                !available && run == null -> Note(availabilityText(availability))
                run != null -> {
                    val progress = run.progress
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Strings.summarizing(progress.done, progress.total),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = callbacks.onStop) { Text(Strings.SUMMARY_STOP) }
                    }
                    if (progress.total > 0) {
                        LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                summaries.toSummarize > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Strings.summaryTodo(summaries.toSummarize), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Button(onClick = callbacks.onSummarizeDay) { Text(Strings.SUMMARIZE_DAY) }
                }
                summaries.hours.any { it.summary?.state == HourSummaryState.DONE } -> Note(Strings.SUMMARY_ALL_DONE)
            }
            when (state.summaryNotice) {
                SummaryNotice.RATE_LIMITED -> Note(Strings.SUMMARY_RATE_LIMITED, error = true)
                SummaryNotice.FAILED -> Note(Strings.SUMMARY_RUN_FAILED, error = true)
                SummaryNotice.MODEL_FAILED -> Note(Strings.SUMMARY_MODEL_FAILED, error = true)
                null -> Unit
            }
            if (summaries.hours.any { it.summary?.state == HourSummaryState.DONE }) Note(Strings.SUMMARY_NOTE)
            for (hour in summaries.hours) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                HourRow(hour, timeZone, generating = run?.progress?.currentHour == hour.hourStart, canRetry = available && run == null, callbacks)
            }
        }
    }
}

private fun availabilityText(availability: LlmAvailability): String = when (availability) {
    LlmAvailability.TurnedOff -> Strings.SUMMARY_TURNED_OFF
    LlmAvailability.LanguageNotSupported -> Strings.SUMMARY_LANGUAGE_NOT_SUPPORTED
    is LlmAvailability.Downloading ->
        availability.fraction?.let { Strings.summaryDownloading(formatPercent(it)) } ?: Strings.SUMMARY_PREPARING
    is LlmAvailability.NeedsDownload ->
        availability.bytes?.let { Strings.summaryNeedsDownload("${it / 100_000_000 / 10.0} GB") } ?: Strings.SUMMARY_NEEDS_MODEL
    is LlmAvailability.Unsupported -> Strings.SUMMARY_UNSUPPORTED
    is LlmAvailability.Available -> ""
}

@Composable
private fun HourRow(hour: HourPlan, timeZone: TimeZone, generating: Boolean, canRetry: Boolean, callbacks: SummaryCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatClockRange(hour.hourStart, hour.hourEnd, timeZone),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // How much was said (computed from the transcript, not by the LLM).
            val density = hour.density.score
            LinearProgressIndicator(
                progress = { density },
                modifier = Modifier.width(56.dp).semantics { contentDescription = Strings.summaryDensity(formatPercent(density)) },
            )
        }
        val summary = hour.summary
        when {
            generating -> {
                Note(Strings.SUMMARY_GENERATING)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            hour.action == HourAction.WAIT_TRANSCRIPTION -> Note(Strings.SUMMARY_WAIT_TRANSCRIPTION)
            hour.action == HourAction.HOUR_NOT_OVER && summary == null -> Note(Strings.SUMMARY_HOUR_NOT_OVER)
            hour.action == HourAction.LITTLE_SPEECH || summary?.state == HourSummaryState.LITTLE_SPEECH -> Note(Strings.SUMMARY_LITTLE_SPEECH)
            summary == null -> Note(Strings.SUMMARY_NOT_YET)
            summary.state == HourSummaryState.REFUSED -> Note(Strings.SUMMARY_REFUSED)
            summary.state == HourSummaryState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Note(Strings.SUMMARY_FAILED, error = true, modifier = Modifier.weight(1f))
                if (canRetry) TextButton(onClick = { callbacks.onRetry(hour.hourStart) }) { Text(Strings.SUMMARY_RETRY) }
            }
            else -> Done(summary.summary.orEmpty(), summary.highlights, stale = summary.stale, callbacks.onOpenHighlight)
        }
    }
}

@Composable
private fun Done(text: String, highlights: List<HighlightRecord>, stale: Boolean, onOpen: (HighlightRecord) -> Unit) {
    if (stale) Note(Strings.SUMMARY_STALE)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
    // A highlight whose utterances were all replaced since (stale) has nothing to open.
    for (highlight in highlights.filter { it.segmentIds.isNotEmpty() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(highlight) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                AppIcons.Play,
                contentDescription = Strings.PLAY,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).widthIn(min = 0.dp)) {
                Text(highlight.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(highlight.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Note(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
