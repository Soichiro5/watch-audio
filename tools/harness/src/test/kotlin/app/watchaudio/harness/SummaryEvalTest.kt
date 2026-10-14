package app.watchaudio.harness

import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.summary.HighlightDraft
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.SummaryDraft
import app.watchaudio.core.summary.SummaryPrompts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Scoring, inputs, providers and the `summary-eval` dry run of the summary evaluation (#197); no model. */
class SummaryEvalTest {
    private val repoRoot = File("").absoluteFile.parentFile.parentFile

    private val facts = ConversationFacts(
        id = "x",
        facts = listOf(
            SummaryFact("f1", "リリースは 11 月 20 日", listOf(10), listOf(listOf("11月20日"))),
            SummaryFact("f2", "サーバー費用が 12 万円増える", listOf(15, 16), listOf(listOf("サーバー"), listOf("12万"))),
            SummaryFact("f3", "定例は水曜", listOf(25), listOf(listOf("水曜"))),
        ),
        mustNot = listOf(SummaryClaim("n1", "延期", listOf(listOf("延期")))),
    )

    @Test
    fun scoresRecallHallucinationsAndCitations() {
        val draft = SummaryDraft(
            summary = "リリースは十一月二十日のまま。",
            highlights = listOf(
                HighlightDraft("費用", "サーバー費用が月に12万円増える", listOf(1)),
                HighlightDraft("延期なし", "リリースは11月20日", listOf(2)),
                HighlightDraft("雑談", "天気の話", listOf(3)),
            ),
        )
        val cited = mapOf(1 to setOf(14), 2 to setOf(3), 3 to setOf(30))
        val score = SummaryScoring.score(facts, draft) { h -> h.lines.flatMap { cited.getValue(it) }.toSet() }
        assertEquals(listOf("f1", "f2"), score.covered)
        assertEquals(listOf("f3"), score.missed)
        assertEquals(listOf("n1"), score.hallucinations)
        // Line 14 is next to fact f2's line 15 (tolerance 1): correct. Line 3 is far from f1's 10: wrong.
        assertEquals(listOf(true, false, null), score.citations.map { it.correct })
        assertEquals(listOf(listOf("f2"), listOf("f1"), emptyList()), score.citations.map { it.facts })
    }

    @Test
    fun aMissingSummaryMissesEveryFact() {
        val score = SummaryScoring.score(facts, null) { emptySet() }
        assertEquals(listOf("f1", "f2", "f3"), score.missed)
        assertEquals(3, score.facts)
        assertTrue(score.hallucinations.isEmpty() && score.citations.isEmpty())
    }

    @Test
    fun checksTheSchemaStrictly() {
        val schema = SummaryPrompts.SCHEMA
        val ok = """{"summary": "s", "highlights": [{"title": "t", "body": "b", "lines": [1, 2]}]}"""
        assertTrue(SummaryScoring.conformsToSchema(ok, schema))
        assertTrue(SummaryScoring.conformsToSchema("```json\n$ok\n```", schema), "a fence the app tolerates")
        assertTrue(SummaryScoring.conformsToSchema("""{"summary": "s", "highlights": [], "extra": 1}""", schema))
        assertFalse(SummaryScoring.conformsToSchema("""{"summary": "s"}""", schema), "missing highlights")
        assertFalse(SummaryScoring.conformsToSchema("""{"summary": "s", "highlights": [{"title": "t", "body": "b", "lines": ["1"]}]}""", schema))
        assertFalse(SummaryScoring.conformsToSchema("""{"summary": 3, "highlights": []}""", schema))
        val four = List(4) { """{"title": "t", "body": "b", "lines": [1]}""" }.joinToString(",")
        assertFalse(SummaryScoring.conformsToSchema("""{"summary": "s", "highlights": [$four]}""", schema), "more than 3 highlights")
        assertFalse(SummaryScoring.conformsToSchema("""{summary: "s", highlights: []}""", schema), "not strict JSON")
        assertFalse(SummaryScoring.conformsToSchema("要約できません", schema))
    }

    @Test
    fun latencyStatsUseNearestRank() {
        val stats = LatencyStats.of((1L..10L).map { it * 100 })!!
        assertEquals(500, stats.medianMs)
        assertEquals(900, stats.p90Ms)
        assertEquals(1000, stats.maxMs)
        assertEquals(5500, stats.totalMs)
        assertEquals(LatencyStats(1, 7, 7, 7, 7), LatencyStats.of(listOf(7)))
        assertNull(LatencyStats.of(emptyList()))
    }

    @Test
    fun scriptInputIsOneSegmentPerSentence() {
        val c = CorpusConversation.parse("c", "A: はい。そうです。\nB: なるほど")
        val input = SummaryInputs.fromScript(c)
        assertEquals(listOf("はい。", "そうです。", "なるほど"), input.segments.map { it.text })
        assertEquals(mapOf(1L to setOf(1), 2L to setOf(1), 3L to setOf(2)), input.scriptLines)
        assertTrue(input.segments.zipWithNext().all { (a, b) -> b.startAt - a.endAt == 400L })
    }

    @Test
    fun asrInputIsSplitIntoConversationsAndMappedToScriptLines() {
        val manifest = TestSetManifest.load(File(repoRoot, "tools/test-audio/fixtures/manifest.json"))
        val recording = manifest.recordings.first()
        val corpus = CorpusConversation.loadAll(File(repoRoot, CorpusConversation.DIRECTORY))
        val truth = recording.segments
        // The recognizer joined the first two utterances, and heard noise long after the conversation.
        val hypotheses = listOf(Hypothesis(truth[0].startMs, truth[1].endMs, "おはようございます お願いします")) +
            truth.drop(2).map { Hypothesis(it.startMs, it.endMs, it.text) } +
            Hypothesis(recording.durationMs + 60_000, recording.durationMs + 61_000, "ノイズ")
        val inputs = SummaryInputs.fromAsr(recording, hypotheses, corpus)
        assertEquals(listOf("01_weekly_meeting"), inputs.map { it.conversation })
        val input = inputs.single()
        assertEquals(truth.size - 1, input.segments.size, "the noise is outside the conversation")
        assertEquals(setOf(1, 2), input.scriptLines.getValue(1))
        assertEquals((3..14).map { setOf(it) }, (2L..13L).map { input.scriptLines.getValue(it) })
    }

    @Test
    fun asrInputFailsWhenTheTestSetDoesNotMatchTheScripts() {
        val c = CorpusConversation.parse("01_weekly_meeting", "A: はい。\nB: いいえ。")
        val truth = listOf(TruthSegment(0, 1000, "A", "wearer", "01_weekly_meeting", "ちがう文"))
        assertFailsWith<IllegalArgumentException> { SummaryInputs.scriptLineNumbers(truth, c) }
    }

    @Test
    fun replayWritesMissingRequestsAndReadsResponses() = runBlocking {
        val dir = Files.createTempDirectory("replay").toFile()
        try {
            val request = LlmRequest("指示", "[1] こんにちは", SummaryPrompts.SCHEMA, 500)
            val first = ReplayLlmProvider(dir)
            assertEquals(LlmResult.Failed("no_response"), first.generate(request))
            assertEquals(LlmResult.Failed("no_response"), first.generate(request))
            assertEquals(1, first.missing)
            val written = File(dir, ReplayLlmProvider.REQUESTS).readLines()
            assertEquals(1, written.size)
            val id = ReplayLlmProvider.requestId(request)
            assertTrue(""""id":"$id"""" in written.single() && "\"schema\"" in written.single())

            File(dir, ReplayLlmProvider.RESPONSES).writeText(
                """{"id": "$id", "ok": "{\"summary\": \"s\", \"highlights\": []}", "latencyMs": 1234, "model": "ios-26.4"}""" + "\n",
            )
            val second = ReplayLlmProvider(dir)
            assertEquals(LlmResult.Ok("""{"summary": "s", "highlights": []}"""), second.generate(request))
            assertEquals(1234L, second.latencyMsOf(request))
            assertEquals(0, second.missing)
            assertEquals(LlmResult.Refused, ReplayLlmProvider.resultOf("guardrail_violation"))
            assertEquals(LlmResult.ContextExceeded, ReplayLlmProvider.resultOf("exceeded_context_window"))
            assertEquals(LlmResult.Failed("decoding_failure"), ReplayLlmProvider.resultOf("decoding_failure"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recordingProviderLogsCalls() = runBlocking {
        var now = 0L
        val recorder = RecordingLlmProvider(ExtractiveLlmProvider(), nanoTime = { now.also { now += 2_000_000 } })
        val result = recorder.generate(LlmRequest(SummaryPrompts.HOUR_INSTRUCTIONS, "[1] 明日は九時から\n[2] 了解", SummaryPrompts.SCHEMA, 500))
        assertTrue(result is LlmResult.Ok)
        assertEquals(listOf(RecordingLlmProvider.Call("hour", 18, "ok", true, 2)), recorder.calls)
    }

    @Test
    fun parsesArguments() {
        val args = SummaryEvalArgs.parse(listOf("out", "--provider=replay=dir", "--context", "8192", "--asr", "w", "--inputs", "script,wrist", "--conversations", "01,05"))
        assertEquals(SummaryEvalArgs(File("out"), "replay=dir", 8192, File("w"), null, listOf("script", "wrist"), listOf("01", "05")), args)
        assertEquals(SummaryEvalArgs(File("out")), SummaryEvalArgs.parse(listOf("out")))
        assertFailsWith<IllegalArgumentException> { SummaryEvalArgs.parse(listOf("out", "--inputs", "wrist")) }
        assertFailsWith<IllegalArgumentException> { SummaryEvalArgs.parse(listOf("out", "--bogus", "1")) }
        assertFailsWith<IllegalArgumentException> { SummaryEvalArgs.parse(listOf("out", "--provider", "cloud")) }
        assertFailsWith<IllegalArgumentException> { SummaryEvalArgs.parse(emptyList()) }
    }

    /** The CI dry run: the whole command over the 16 scripts and a fixture ASR input, with the fake model. */
    @Test
    fun dryRunWithTheFakeModel() = runBlocking {
        val out = Files.createTempDirectory("summary-eval").toFile()
        try {
            // An evaluate work directory whose `sample_clean` heard every utterance exactly.
            val asr = File(out, "asr/sample_clean").apply { mkdirs() }
            val recording = TestSetManifest.load(File(repoRoot, "tools/test-audio/fixtures/manifest.json")).recordings.first()
            File(asr, "segments.tsv").writeText(
                SignalLog.TSV_HEADER + "\n" + recording.segments.joinToString("") { SignalLog.tsvLine(Hypothesis(it.startMs, it.endMs, it.text), null, false) + "\n" },
            )
            val args = SummaryEvalArgs(
                File(out, "result"), asrDir = asr.parentFile, manifest = File(repoRoot, "tools/test-audio/fixtures/manifest.json"),
                inputs = listOf("script", "sample_clean"),
            )
            val table = SummaryEval.run(repoRoot, args)
            println(table)
            assertTrue("provider: fake-extractive" in table)
            val rows = File(out, "result/results.tsv").readLines()
            assertEquals(SummaryEval.TSV_HEADER, rows[0])
            val script = rows[1].split('\t')
            val header = SummaryEval.TSV_HEADER.split('\t')
            fun value(name: String) = script[header.indexOf(name)]
            assertEquals("script", value("input"))
            assertEquals("16", value("conversations"))
            assertEquals("16", value("summarized"), "every conversation has enough speech")
            assertEquals(value("ok_calls"), value("schema_valid"), "the fake always answers in shape")
            assertEquals("0", value("refused"))
            assertEquals("0", value("failed"))
            assertTrue(value("covered").toInt() > 0, "the extractive answers cover some facts")
            assertTrue(value("citations_judged").toInt() > 0, "some highlights state a fact")
            assertEquals(value("citations_judged"), value("citations_correct"), "the fake cites the lines it copies")
            assertTrue(rows[2].startsWith("sample_clean\t1\t"), rows[2])
            assertEquals(1 + 16 + 1, File(out, "result/conversations.tsv").readLines().size)
            assertTrue("## script / 01_weekly_meeting: done" in File(out, "result/summaries.md").readText())
        } finally {
            out.deleteRecursively()
        }
    }

    /** A small context splits the conversations into parts and merges them, still in shape. */
    @Test
    fun dryRunSplitsAndMergesInASmallContext() = runBlocking {
        val out = Files.createTempDirectory("summary-eval-small").toFile()
        try {
            // 01 (787 characters) does not fit one call of 2,250 tokens, but its two parts fit a merge.
            SummaryEval.run(repoRoot, SummaryEvalArgs(out, context = 2_250, conversations = listOf("01")))
            val conversation = File(out, "conversations.tsv").readLines()[1].split('\t')
            assertEquals("done", conversation[2])
            assertEquals("3", conversation[5], "two parts and a merge")
            val row = File(out, "results.tsv").readLines()[1].split('\t')
            assertEquals(row[10], row[11], "every answer in shape")
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun littleSpeechIsNotSentToTheModel() = runBlocking {
        val input = SummaryInput("x", listOf(SegmentRecord(1, "c", 0, 1000, "はい", "ja", "asr")), mapOf(1L to setOf(1)))
        val result = SummaryEval(ExtractiveLlmProvider()).evaluate("wrist", input, facts)
        assertEquals("little_speech", result.outcome)
        assertTrue(result.calls.isEmpty())
        assertEquals(3, result.score.missed.size)
    }
}
