package app.watchaudio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.DayTimeline
import app.watchaudio.app.logic.GapCause
import app.watchaudio.app.logic.TimelineSpan

@Immutable
internal data class TimelineColors(
    val audio: Color,
    val silent: Color,
    val stopped: Color,
    val missing: Color,
    val awaiting: Color,
    val notRecorded: Color,
    val future: Color,
)

@Composable
internal fun timelineColors(): TimelineColors {
    val scheme = MaterialTheme.colorScheme
    return TimelineColors(
        audio = scheme.primary,
        silent = scheme.primary.copy(alpha = 0.35f),
        stopped = scheme.tertiary,
        missing = scheme.error,
        awaiting = scheme.outline,
        notRecorded = scheme.surfaceVariant,
        future = scheme.surfaceVariant.copy(alpha = 0.4f),
    )
}

internal fun TimelineColors.of(span: TimelineSpan): Color = when (span) {
    is TimelineSpan.Audio -> audio
    is TimelineSpan.Silent -> silent
    is TimelineSpan.Future -> future
    is TimelineSpan.Gap -> of(span.cause)
}

internal fun TimelineColors.of(cause: GapCause): Color = when (cause) {
    is GapCause.Stopped -> stopped
    GapCause.Missing -> missing
    GapCause.Awaiting -> awaiting
    GapCause.NoRecording -> notRecorded
}

/** A 24-hour bar: recorded, silent, gap (colored by cause) and not-yet-happened spans. */
@Composable
internal fun TimelineBar(timeline: DayTimeline, description: String, modifier: Modifier = Modifier) {
    val colors = timelineColors()
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .semantics { contentDescription = description },
        ) {
            val total = timeline.window.lengthMs.toFloat().coerceAtLeast(1f)
            for (span in timeline.spans) {
                val x = (span.start - timeline.window.start) / total * size.width
                // At least a hairline so a short span stays visible.
                val w = (span.durationMs / total * size.width).coerceAtLeast(1f)
                drawRect(colors.of(span), topLeft = Offset(x, 0f), size = Size(w, size.height))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (hour in listOf(0, 6, 12, 18, 24)) {
                Text(hour.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun TimelineLegend(modifier: Modifier = Modifier) {
    val colors = timelineColors()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem(colors.audio, Strings.LEGEND_AUDIO)
        LegendItem(colors.silent, Strings.LEGEND_SILENT)
        LegendItem(colors.stopped, Strings.LEGEND_STOPPED)
        LegendItem(colors.missing, Strings.LEGEND_MISSING)
        LegendItem(colors.notRecorded, Strings.LEGEND_NOT_RECORDED)
    }
}

@Composable
internal fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
