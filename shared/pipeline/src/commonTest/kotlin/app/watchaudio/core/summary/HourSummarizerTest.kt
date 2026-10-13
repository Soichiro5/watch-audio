package app.watchaudio.core.summary

import app.watchaudio.core.db.SegmentRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Splitting an hour to fit a small context, merging the parts, and the model's failures (#27 §4.2, §4.5). */
class HourSummarizerTest {
    /** A busy hour: [chars] characters of speech in segments of about 12 characters, 3 s apart. */
    private fun busyHour(chars: Int = 20_000, marks: List<Long> = emptyList()): List<PromptLine> {
        val words = "今日の会議では来週の予定と予算について話し合った"
        val segments = (0 until chars / 12).map { i ->
            val text = (0 until 12).map { words[(i * 12 + it) % words.length] }.joinToString("")
            SegmentRecord(i.toLong() + 1, "c", i * 3_000L, i * 3_000L + 2_500, text, "ja", "e")
        }
        return PromptLines.build(segments, marks)
    }

    private fun HourOutcome.done(): HourOutcome.Done = this as? HourOutcome.Done ?: throw AssertionError("not done: $this")

    @Test
    fun twentyThousandCharactersFitEveryContextAndAreMerged() = runTest {
        val lines = busyHour()
        val numbers = lines.map { it.number }.toSet()
        assertTrue(lines.sumOf { it.text.length } >= 19_000)
        for (context in listOf(4_096, 8_192, 32_768)) {
            for ((counts, perChar) in listOf(true to 1.0, true to 1.3, false to 1.0)) {
                val provider = FakeLlmProvider(context = context, countsTokens = counts, tokensPerChar = perChar)
                val outcome = HourSummarizer(provider).summarize(lines).done()
                val what = "context $context, counts=$counts, $perChar tokens per character"
                assertTrue(provider.largestRequest <= context, what)
                assertTrue(outcome.draft.highlights.isNotEmpty(), what)
                assertTrue(outcome.draft.highlights.all { h -> h.lines.all { it in numbers } }, what)
                val parts = provider.requests.filter { it.instructions == SummaryPrompts.PART_INSTRUCTIONS }
                if (context == 32_768 && perChar == 1.0) {
                    // The whole hour in one call.
                    assertEquals(listOf(SummaryPrompts.HOUR_INSTRUCTIONS), provider.requests.map { it.instructions }, what)
                } else {
                    // Every line went into exactly one part, and the last call merged them.
                    assertEquals(lines.map { it.number }, parts.flatMap { FakeLlmProvider.linesOf(it.prompt) }, what)
                    assertEquals(SummaryPrompts.MERGE_INSTRUCTIONS, provider.requests.last().instructions, what)
                }
                if (context == 4_096) assertTrue(parts.size >= 7, "$what: ${parts.size} parts")
                assertEquals(provider.requests.size, outcome.calls, what)
            }
        }
    }

    @Test
    fun partsThatDoNotFitOneMergeAreMergedInRounds() = runTest {
        // Long answers: nine parts of the 4,096 context do not fit one merge prompt.
        val provider = FakeLlmProvider(context = 4_096) { request ->
            val lines = FakeLlmProvider.linesOf(request.prompt)
            val highlight = """{"title": "${"題".repeat(40)}", "body": "${"本".repeat(200)}", "lines": [${lines.first()}, ${lines.last()}]}"""
            LlmResult.Ok("""{"summary": "${"要".repeat(400)}", "highlights": [$highlight, $highlight, $highlight]}""")
        }
        val outcome = HourSummarizer(provider).summarize(busyHour()).done()
        val merges = provider.requests.count { it.instructions == SummaryPrompts.MERGE_INSTRUCTIONS }
        assertTrue(merges >= 3, "$merges merges")
        assertTrue(provider.largestRequest <= 4_096)
        assertEquals(3, outcome.draft.highlights.size)
    }

    @Test
    fun marksAreStarredInPartsAndCandidates() = runTest {
        val lines = busyHour(marks = listOf(1_800_000))
        val marked = lines.filter { it.marked }.map { it.number }
        assertTrue(marked.isNotEmpty())
        val provider = FakeLlmProvider(context = 4_096) { request ->
            val numbers = FakeLlmProvider.linesOf(request.prompt)
            val cite = numbers.firstOrNull { it in marked } ?: numbers.first()
            LlmResult.Ok("""{"summary": "要約。", "highlights": [{"title": "t", "body": "b", "lines": [$cite]}]}""")
        }
        val outcome = HourSummarizer(provider).summarize(lines).done()
        assertTrue(provider.requests.any { "]★ " in it.prompt })
        assertTrue("- ★t: b" in provider.requests.last().prompt, provider.requests.last().prompt)
        assertTrue(outcome.draft.highlights.single().lines.single() in marked)
    }

    @Test
    fun citationsOutsideThePromptAreDroppedAndUnsupportedHighlightsDoNotRemain() = runTest {
        val lines = busyHour(chars = 600)
        val provider = FakeLlmProvider()
        provider.scripted += LlmResult.Ok(
            """{"summary": "短い会話。", "highlights": [{"title": "a", "body": "b", "lines": [1, 4000]}, {"title": "c", "body": "d", "lines": [4001]}]}""",
        )
        val outcome = HourSummarizer(provider).summarize(lines).done()
        assertEquals(listOf(HighlightDraft("a", "b", listOf(1))), outcome.draft.highlights)
        assertEquals(1, outcome.calls)
    }

    @Test
    fun aBrokenAnswerIsAskedForOnce() = runTest {
        val provider = FakeLlmProvider()
        provider.scripted += LlmResult.Ok("申し訳ありません")
        assertEquals(2, HourSummarizer(provider).summarize(busyHour(chars = 600)).done().calls)

        repeat(2) { provider.scripted += LlmResult.Ok("{") }
        assertEquals(HourOutcome.Failed("invalid_output"), HourSummarizer(provider).summarize(busyHour(chars = 600)))
    }

    @Test
    fun refusalsRateLimitsAndFailures() = runTest {
        val small = busyHour(chars = 600)
        FakeLlmProvider().also { provider ->
            provider.scripted += LlmResult.Refused
            assertEquals(HourOutcome.Refused, HourSummarizer(provider).summarize(small))
            assertEquals(1, provider.requests.size, "a refusal is not retried")
        }
        FakeLlmProvider().also { provider ->
            provider.scripted += LlmResult.RateLimited(5_000)
            assertEquals(HourOutcome.RateLimited(5_000), HourSummarizer(provider).summarize(small))
        }
        FakeLlmProvider().also { provider ->
            provider.scripted += LlmResult.Failed("assets_unavailable")
            assertEquals(HourOutcome.Failed("assets_unavailable"), HourSummarizer(provider).summarize(small))
        }
        // A busy hour: a refused part is left out, the others are still merged.
        FakeLlmProvider().also { provider ->
            provider.scripted += LlmResult.Refused
            val outcome = HourSummarizer(provider).summarize(busyHour()).done()
            assertTrue(outcome.draft.highlights.isNotEmpty())
        }
        // Every part refused: the hour is refused.
        FakeLlmProvider { LlmResult.Refused }.also { provider ->
            assertEquals(HourOutcome.Refused, HourSummarizer(provider).summarize(busyHour()))
        }
    }

    @Test
    fun aPartThePlatformFindsTooLongIsHalved() = runTest {
        val provider = FakeLlmProvider()
        val lines = busyHour(chars = 6_000)
        provider.scripted += LlmResult.ContextExceeded
        val outcome = HourSummarizer(provider).summarize(lines).done()
        val parts = provider.requests.filter { it.instructions == SummaryPrompts.PART_INSTRUCTIONS }
        // The first part was asked again as two halves.
        val first = FakeLlmProvider.linesOf(parts[0].prompt)
        assertEquals(first, FakeLlmProvider.linesOf(parts[1].prompt) + FakeLlmProvider.linesOf(parts[2].prompt))
        assertTrue(outcome.draft.summary.isNotEmpty())
    }

    @Test
    fun aContextTooSmallForAnyLinesFails() = runTest {
        assertEquals(HourOutcome.Failed("context_too_small"), HourSummarizer(FakeLlmProvider(context = 1_000)).summarize(busyHour(chars = 600)))
    }
}
