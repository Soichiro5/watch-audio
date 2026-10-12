package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.Strings
import app.watchaudio.core.export.ExportRange
import app.watchaudio.core.export.ExportRequest
import app.watchaudio.core.export.ExportSummary
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.timeline.localDateOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/** Which days the user wants to export. */
enum class ExportRangeMode { ALL, DATES }

/** The 「データの書き出し」 card (#155): the user's choices and the running export. */
data class ExportUiState(
    val mode: ExportRangeMode = ExportRangeMode.ALL,
    /** The date range, used when [mode] is [ExportRangeMode.DATES]; [from] <= [to] <= today. */
    val from: LocalDate,
    val to: LocalDate,
    val today: LocalDate,
    val includeAudio: Boolean = false,
    val busy: Boolean = false,
    /** Share of the days written (0..1) while writing; null before (e.g. while the user picks the place). */
    val progress: Float? = null,
    /** Outcome of the last export when there is something to say. */
    val message: String? = null,
) {
    val range: ExportRange get() = if (mode == ExportRangeMode.ALL) ExportRange.ALL else ExportRange(from, to)
}

/**
 * Exports the user's data (#155) only when they tap 「書き出す」: checks there is something in the
 * range, lets the platform ask where to save it ([AppServices.saveExport]) and streams the archive
 * there ([AppServices.exporter]). Days are those of [timeZone], like the timeline.
 */
class ExportViewModel(
    private val services: AppServices,
    private val timeZone: TimeZone,
    private val clock: NowProvider = NowProvider.System,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val exporter = services.exporter
    private val mutableState = MutableStateFlow(today().let { ExportUiState(from = it.minus(6, DateTimeUnit.DAY), to = it, today = it) })

    val state: StateFlow<ExportUiState> = mutableState.asStateFlow()

    fun setMode(mode: ExportRangeMode) = edit { it.copy(mode = mode) }

    /** Moves the start; the end follows if it would come before it. */
    fun setFrom(date: LocalDate) = edit {
        val from = minOf(date, it.today)
        it.copy(from = from, to = maxOf(it.to, from))
    }

    /** Moves the end; the start follows if it would come after it. */
    fun setTo(date: LocalDate) = edit {
        val to = minOf(date, it.today)
        it.copy(to = to, from = minOf(it.from, to))
    }

    fun setIncludeAudio(include: Boolean) = edit { it.copy(includeAudio = include) }

    fun export() {
        val choice = mutableState.value
        if (choice.busy) return
        mutableState.value = choice.copy(busy = true, progress = null, message = null)
        viewModelScope.launch {
            val message = try {
                run(ExportRequest(choice.range, choice.includeAudio, timeZone, clock.now()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                services.diagnostics.log(DiagnosticEvents.DATA_EXPORT_FAILED, "error=${DiagnosticsLog.typeName(e)}")
                Strings.EXPORT_FAILED
            }
            mutableState.update { it.copy(busy = false, progress = null, message = message) }
        }
    }

    /** @return the message to show, or null when the user cancelled. */
    private suspend fun run(request: ExportRequest): String? {
        val plan = exporter.plan(request) ?: return Strings.EXPORT_NOTHING
        var summary: ExportSummary? = null
        val saved = services.saveExport(plan.fileName, ZIP_MIME_TYPE) { sink ->
            summary = exporter.write(plan, sink) { progress ->
                mutableState.update { it.copy(progress = progress.doneDays.toFloat() / progress.totalDays) }
            }
        }
        val written = summary?.takeIf { saved } ?: return null
        services.diagnostics.log(
            DiagnosticEvents.DATA_EXPORTED,
            "days=${written.days} chunks=${written.chunks} audio_files=${written.audioFiles} missing_audio=${written.missingAudioFiles}",
        )
        return listOfNotNull(
            Strings.exported(written.days, written.segments, written.audioFiles),
            written.missingAudioFiles.takeIf { it > 0 }?.let(Strings::exportMissingAudio),
        ).joinToString("\n")
    }

    /** Applies a choice (not while exporting), with today refreshed so a range never ends in the future. */
    private fun edit(change: (ExportUiState) -> ExportUiState) {
        val today = today()
        mutableState.update { if (it.busy) it else change(it.copy(today = today)) }
    }

    private fun today(): LocalDate = localDateOf(clock.now(), timeZone)

    private companion object {
        const val ZIP_MIME_TYPE = "application/zip"
    }
}
