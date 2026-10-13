package app.watchaudio.core.summary

/** What [HourSummarizer.summarize] made of an hour. */
sealed interface HourOutcome {
    /** Highlights cite line numbers of the hour's [PromptLine]s. [calls] counts LLM calls. */
    data class Done(val draft: SummaryDraft, val calls: Int) : HourOutcome

    /** The model declined every part of the hour (guardrail). */
    data object Refused : HourOutcome

    /** The system is busy: stop and try again later. */
    data class RateLimited(val retryAfterMs: Long?) : HourOutcome

    /** [type] is a short code without user data. */
    data class Failed(val type: String) : HourOutcome
}

/**
 * Summarizes one hour within a small context (#27 §4.2, Apple's TN3193): if the hour's lines fit
 * one call they are summarized at once; otherwise they are split into parts that each fit the
 * budget, every part is summarized in its own (stateless) call, and the parts are merged, in
 * rounds if they do not fit one merge either. Highlights of a merge are chosen from the parts'
 * candidates and keep their line numbers.
 *
 * The budget of a call is `contextTokens − instructions − schema − maxOutputTokens − margin`,
 * counted with [LlmProvider.countTokens] where the platform can, 1 character = 1 token otherwise
 * (about right for Japanese, generous for English).
 */
class HourSummarizer(
    private val provider: LlmProvider,
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    private val marginTokens: Int = DEFAULT_MARGIN_TOKENS,
) {
    private var calls = 0

    /** Summarizes [lines] (one hour, numbered from 1; not empty). */
    suspend fun summarize(lines: List<PromptLine>): HourOutcome {
        require(lines.isNotEmpty()) { "no lines to summarize" }
        calls = 0
        val context = provider.contextTokens()
        val schema = tokens(SummaryPrompts.SCHEMA.toJson())
        suspend fun budget(instructions: String) = context - tokens(instructions) - schema - maxOutputTokens - marginTokens
        val hourBudget = budget(SummaryPrompts.HOUR_INSTRUCTIONS)
        val partBudget = budget(SummaryPrompts.PART_INSTRUCTIONS)
        val mergeBudget = budget(SummaryPrompts.MERGE_INSTRUCTIONS)
        if (minOf(hourBudget, partBudget, mergeBudget) < MIN_INPUT_TOKENS) return HourOutcome.Failed("context_too_small")
        val marked = lines.filter { it.marked }.mapTo(HashSet()) { it.number }

        val whole = SummaryPrompts.linesPrompt(lines)
        if (fits(whole, hourBudget)) {
            return when (val result = call(SummaryPrompts.HOUR_INSTRUCTIONS, whole, lines.mapTo(HashSet()) { it.number })) {
                is Call.Ok -> HourOutcome.Done(result.draft, calls)
                is Call.Stop -> result.outcome
            }
        }

        val parts = mutableListOf<SummaryDraft>()
        var refused = 0
        for (batch in split(lines, partBudget)) {
            when (val result = summarizePart(batch)) {
                is Parts.Ok -> parts += result.drafts
                is Parts.Refused -> refused++
                is Parts.Stop -> return result.outcome
            }
        }
        if (parts.isEmpty()) return if (refused > 0) HourOutcome.Refused else HourOutcome.Failed("no_output")
        if (parts.size == 1) return HourOutcome.Done(parts.single(), calls)
        return merge(parts.map { it.compact() }, marked, mergeBudget)
    }

    private sealed interface Call {
        data class Ok(val draft: SummaryDraft) : Call
        data class Stop(val outcome: HourOutcome) : Call
    }

    private sealed interface Parts {
        data class Ok(val drafts: List<SummaryDraft>) : Parts
        data object Refused : Parts
        data class Stop(val outcome: HourOutcome) : Parts
    }

    /** One part; if the platform still finds it too long, its halves. */
    private suspend fun summarizePart(batch: List<PromptLine>): Parts {
        val result = generateChecked(SummaryPrompts.PART_INSTRUCTIONS, SummaryPrompts.linesPrompt(batch), batch.mapTo(HashSet()) { it.number })
        return when (result) {
            is Generated.Ok -> Parts.Ok(listOf(result.draft))
            Generated.Refused -> Parts.Refused
            Generated.ContextExceeded -> {
                if (batch.size < 2) return Parts.Stop(HourOutcome.Failed("context_exceeded"))
                val halves = listOf(batch.subList(0, batch.size / 2), batch.subList(batch.size / 2, batch.size))
                val drafts = mutableListOf<SummaryDraft>()
                var refused = 0
                for (half in halves) {
                    when (val part = summarizePart(half)) {
                        is Parts.Ok -> drafts += part.drafts
                        Parts.Refused -> refused++
                        is Parts.Stop -> return part
                    }
                }
                if (drafts.isEmpty()) Parts.Refused else Parts.Ok(drafts)
            }
            is Generated.Stop -> Parts.Stop(result.outcome)
        }
    }

    /** Merges [parts] into one summary, in rounds of groups that fit [budget] when all do not. */
    private suspend fun merge(parts: List<SummaryDraft>, marked: Set<Int>, budget: Int): HourOutcome {
        var drafts = parts
        while (drafts.size > 1 && !fits(SummaryPrompts.mergePrompt(drafts, marked), budget)) {
            val groups = group(drafts, marked, budget)
            if (groups.none { it.size > 1 }) return HourOutcome.Failed("context_too_small")
            drafts = groups.map { group ->
                if (group.size == 1) return@map group.single()
                when (val result = call(SummaryPrompts.MERGE_INSTRUCTIONS, SummaryPrompts.mergePrompt(group, marked), group.citedLines())) {
                    is Call.Ok -> result.draft.compact()
                    is Call.Stop -> return result.outcome
                }
            }
        }
        // The last round merged everything into one.
        if (drafts.size == 1) return HourOutcome.Done(drafts.single(), calls)
        return when (val result = call(SummaryPrompts.MERGE_INSTRUCTIONS, SummaryPrompts.mergePrompt(drafts, marked), drafts.citedLines())) {
            is Call.Ok -> HourOutcome.Done(result.draft, calls)
            is Call.Stop -> result.outcome
        }
    }

    /** Consecutive groups of [drafts] whose merge prompt fits [budget]. */
    private suspend fun group(drafts: List<SummaryDraft>, marked: Set<Int>, budget: Int): List<List<SummaryDraft>> {
        val groups = mutableListOf<MutableList<SummaryDraft>>()
        for (draft in drafts) {
            val open = groups.lastOrNull()
            if (open != null && fits(SummaryPrompts.mergePrompt(open + draft, marked), budget)) open += draft else groups += mutableListOf(draft)
        }
        return groups
    }

    /**
     * Consecutive batches of [lines] whose prompt fits [budget]: by the 1-character-1-token
     * estimate first, then halved while the platform's count says a batch is still too long. A
     * single line longer than the budget is cut.
     */
    private suspend fun split(lines: List<PromptLine>, budget: Int): List<List<PromptLine>> {
        val estimated = mutableListOf<MutableList<PromptLine>>()
        var size = 0
        for (line in lines) {
            val cost = line.render().length + 1
            val open = estimated.lastOrNull()
            if (open != null && size + cost <= budget) {
                open += line
                size += cost
            } else {
                estimated += mutableListOf(line)
                size = cost
            }
        }
        return estimated.flatMap { fitting(it, budget) }
    }

    private suspend fun fitting(batch: List<PromptLine>, budget: Int): List<List<PromptLine>> {
        if (fits(SummaryPrompts.linesPrompt(batch), budget)) return listOf(batch)
        if (batch.size == 1) {
            var line = batch.single()
            while (line.text.length > 1 && !fits(line.render(), budget)) line = line.copy(text = line.text.take(line.text.length / 2) + "…")
            return listOf(listOf(line))
        }
        return fitting(batch.subList(0, batch.size / 2), budget) + fitting(batch.subList(batch.size / 2, batch.size), budget)
    }

    private sealed interface Generated {
        data class Ok(val draft: SummaryDraft) : Generated
        data object Refused : Generated
        data object ContextExceeded : Generated
        data class Stop(val outcome: HourOutcome) : Generated
    }

    /** One generation, checked; an answer that is not of the schema is asked for once more. */
    private suspend fun generateChecked(instructions: String, prompt: String, validLines: Set<Int>): Generated {
        repeat(ATTEMPTS) {
            calls++
            when (val result = provider.generate(LlmRequest(instructions, prompt, SummaryPrompts.SCHEMA, maxOutputTokens))) {
                is LlmResult.Ok -> parseSummaryOutput(result.json, validLines)?.let { return Generated.Ok(it) }
                LlmResult.Refused -> return Generated.Refused
                LlmResult.ContextExceeded -> return Generated.ContextExceeded
                is LlmResult.RateLimited -> return Generated.Stop(HourOutcome.RateLimited(result.retryAfterMs))
                is LlmResult.Failed -> return Generated.Stop(HourOutcome.Failed(result.type))
            }
        }
        return Generated.Stop(HourOutcome.Failed("invalid_output"))
    }

    /** [generateChecked] where a refusal or an overflow ends the hour. */
    private suspend fun call(instructions: String, prompt: String, validLines: Set<Int>): Call =
        when (val result = generateChecked(instructions, prompt, validLines)) {
            is Generated.Ok -> Call.Ok(result.draft)
            Generated.Refused -> Call.Stop(HourOutcome.Refused)
            Generated.ContextExceeded -> Call.Stop(HourOutcome.Failed("context_exceeded"))
            is Generated.Stop -> Call.Stop(result.outcome)
        }

    private suspend fun tokens(text: String): Int = provider.countTokens(text) ?: text.length

    private suspend fun fits(text: String, budget: Int): Boolean = tokens(text) <= budget

    companion object {
        /** Room for the answer: 2-3 sentences and three highlights. */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 500

        /** Kept free for what the platform adds around the prompt. */
        const val DEFAULT_MARGIN_TOKENS = 64

        /** Below this a call would hold too few lines to be useful. */
        const val MIN_INPUT_TOKENS = 200

        private const val ATTEMPTS = 2
    }
}

/** Line numbers cited by the drafts' highlights: what a merge may cite. */
private fun List<SummaryDraft>.citedLines(): Set<Int> = flatMapTo(HashSet()) { draft -> draft.highlights.flatMap { it.lines } }

/** A part as merge input: shorter texts, so that two or more parts always fit one merge. */
private fun SummaryDraft.compact(): SummaryDraft = SummaryDraft(
    summary = shorten(summary, PART_SUMMARY_CHARS),
    highlights = highlights.map { it.copy(body = shorten(it.body, PART_BODY_CHARS)) },
)

private const val PART_SUMMARY_CHARS = 200
private const val PART_BODY_CHARS = 100

private fun shorten(text: String, max: Int) = if (text.length > max) text.take(max - 1) + "…" else text
