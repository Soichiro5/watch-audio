package app.watchaudio.core.summary

import app.watchaudio.core.db.HOUR_MS
import app.watchaudio.core.db.HighlightRecord
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewHourSummary
import app.watchaudio.core.db.hourSourceHash
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.timeline.DayWindow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.datetime.TimeZone

/** Where a [SummaryRun] is: [done] of [total] hours that need the LLM, now at [currentHour]. */
data class SummaryProgress(val done: Int, val total: Int, val currentHour: Long?)

/** How a [SummaryRun] ended. Cancellation (the screen closed) is not a result: it throws. */
sealed interface SummaryRunResult {
    /** Every planned hour was handled; failures are stored per hour. */
    data class Finished(val summarized: Int, val failed: Int) : SummaryRunResult

    /** The model cannot be used now (nothing was done). */
    data class Unavailable(val availability: LlmAvailability) : SummaryRunResult

    /** The system asked to slow down: the run stopped, the rest is left for the next one. */
    data class RateLimited(val summarized: Int) : SummaryRunResult
}

/**
 * Makes the hour summaries of a day (#27 §4.4), one hour at a time, each saved as soon as it is
 * done, so stopping (cancelling the coroutine, e.g. when the screen closes) keeps the finished
 * hours. Started by the user's 「この日を要約」 for now; the automatic run while charging will call
 * the same code.
 *
 * A failure stays in its hour (stored as [HourSummaryState.FAILED] with the error type) and never
 * stops the other hours or any other feature; only a rate limit stops the run. Before saving, the
 * hour's segments are checked against those that were read ([NewHourSummary.sourceHash]): if they
 * changed meanwhile, nothing is saved and the hour stays to do.
 */
class SummaryRun(
    private val store: LocalStore,
    private val provider: LlmProvider,
    private val timeZone: TimeZone,
    private val clock: () -> Long,
    private val diagnostics: Diagnostics = Diagnostics.NONE,
) {
    /**
     * Summarizes the hours of [window] that need it ([planDay]); with [onlyHours], only those of
     * them (the per-hour 「もう一度」). [onProgress] is called before each LLM hour and at the end.
     */
    suspend fun run(
        window: DayWindow,
        onlyHours: Set<Long>? = null,
        onProgress: (SummaryProgress) -> Unit = {},
    ): SummaryRunResult {
        val availability = provider.availability()
        if (availability !is LlmAvailability.Available) return SummaryRunResult.Unavailable(availability)
        val started = clock()
        val plan = store.read {
            planDay(
                window,
                chunksBetween(window.start, window.end),
                segmentsStartedBetween(window.start, window.end),
                hourSummariesBetween(window.start, window.end),
                clock(),
            )
        }.filter { onlyHours == null || it.hourStart in onlyHours }
        val little = plan.filter { it.action == HourAction.LITTLE_SPEECH }
        val todo = plan.filter { it.action == HourAction.SUMMARIZE }
        little.forEach { saveLittleSpeech(it.hourStart, availability.modelVersion) }

        var summarized = 0
        var failed = 0
        for ((index, hour) in todo.withIndex()) {
            onProgress(SummaryProgress(index, todo.size, hour.hourStart))
            when (summarizeHour(hour.hourStart, availability.modelVersion)) {
                HourResult.SAVED -> summarized++
                HourResult.FAILED -> failed++
                HourResult.CHANGED -> Unit
                HourResult.RATE_LIMITED -> {
                    log("rate_limited", todo.size, summarized, failed, started)
                    onProgress(SummaryProgress(index, todo.size, null))
                    return SummaryRunResult.RateLimited(summarized)
                }
            }
        }
        onProgress(SummaryProgress(todo.size, todo.size, null))
        if (todo.isNotEmpty()) log("finished", todo.size, summarized, failed, started)
        return SummaryRunResult.Finished(summarized, failed)
    }

    private enum class HourResult { SAVED, FAILED, CHANGED, RATE_LIMITED }

    private suspend fun summarizeHour(hourStart: Long, modelVersion: String?): HourResult {
        val (segments, marks) = store.read {
            segmentsStartedBetween(hourStart, hourStart + HOUR_MS) to markTimesBetween(hourStart - PromptLines.MARK_WINDOW_MS, hourStart + HOUR_MS + PromptLines.MARK_WINDOW_MS)
        }
        val hash = hourSourceHash(segments)
        val lines = PromptLines.build(segments, marks)
        val outcome = try {
            if (lines.isEmpty()) HourOutcome.Failed("no_lines") else HourSummarizer(provider).summarize(lines)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HourOutcome.Failed(e::class.simpleName ?: "exception")
        }
        val base = NewHourSummary(
            hourStart = hourStart,
            timeZone = timeZone.id,
            state = HourSummaryState.DONE,
            summary = null,
            provider = provider.id,
            modelVersion = modelVersion,
            promptVersion = SummaryPrompts.VERSION,
            sourceHash = hash,
            createdAt = clock(),
        )
        val record = when (outcome) {
            is HourOutcome.Done -> {
                val byNumber = lines.associateBy { it.number }
                base.copy(
                    summary = outcome.draft.summary,
                    highlights = outcome.draft.highlights.map { h ->
                        HighlightRecord(h.title, h.body, h.lines.mapNotNull { byNumber[it] }.flatMap { it.segmentIds })
                    },
                )
            }
            HourOutcome.Refused -> base.copy(state = HourSummaryState.REFUSED)
            is HourOutcome.Failed -> {
                diagnostics.log(DiagnosticEvents.SUMMARY_FAILED, "hour=$hourStart type=${outcome.type}")
                base.copy(state = HourSummaryState.FAILED, lastError = outcome.type)
            }
            is HourOutcome.RateLimited -> return HourResult.RATE_LIMITED
        }
        val saved = store.transaction { saveHourSummary(record) }
        return when {
            !saved -> HourResult.CHANGED
            record.state == HourSummaryState.FAILED -> HourResult.FAILED
            else -> HourResult.SAVED
        }
    }

    private suspend fun saveLittleSpeech(hourStart: Long, modelVersion: String?) {
        val segments = store.read { segmentsStartedBetween(hourStart, hourStart + HOUR_MS) }
        store.transaction {
            saveHourSummary(
                NewHourSummary(
                    hourStart = hourStart,
                    timeZone = timeZone.id,
                    state = HourSummaryState.LITTLE_SPEECH,
                    summary = null,
                    provider = provider.id,
                    modelVersion = modelVersion,
                    promptVersion = SummaryPrompts.VERSION,
                    sourceHash = hourSourceHash(segments),
                    createdAt = clock(),
                ),
            )
        }
    }

    private fun log(outcome: String, hours: Int, summarized: Int, failed: Int, started: Long) {
        diagnostics.log(
            DiagnosticEvents.SUMMARY_RUN,
            "outcome=$outcome provider=${provider.id} hours=$hours summarized=$summarized failed=$failed ms=${clock() - started}",
        )
    }
}
