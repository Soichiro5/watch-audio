package app.watchaudio.harness

import app.watchaudio.core.summary.HighlightDraft
import app.watchaudio.core.summary.HourOutcome
import app.watchaudio.core.summary.HourSummarizer
import app.watchaudio.core.summary.LITTLE_SPEECH_CHARS
import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmProvider
import app.watchaudio.core.summary.PromptLines
import app.watchaudio.core.summary.SummaryDraft
import app.watchaudio.core.summary.SummaryPrompts
import java.io.File

/**
 * Arguments of `summary-eval`:
 * `<outDir> [--provider fake|replay=<dir>] [--context n] [--asr <evaluate workDir>] [--manifest <manifest.json>] [--inputs script,clean,...] [--conversations 01,05,...]`.
 *
 * - `--provider`: `fake` ([ExtractiveLlmProvider], the default: a dry run without a model) or
 *   `replay=<dir>` ([ReplayLlmProvider]: answers made on a device). Further providers (LiteRT-LM in
 *   the JVM) are added in [SummaryEval.provider].
 * - `--context`: the model's context in tokens (default 4,096, Apple Foundation Models).
 * - `--asr`: a work directory of `evaluate` whose `<recording>/segments.tsv` are the ASR inputs, with
 *   the test set's `--manifest` (default `tools/test-audio/cache/out/manifest.json`).
 * - `--inputs`: `script` (the scripts' text) and recording ids of the test set. Default: `script`,
 *   plus `clean`, `wrist` and `cafe` when `--asr` has their `segments.tsv`.
 * - `--conversations`: corpus ids or their number prefixes (default: all 16).
 */
data class SummaryEvalArgs(
    val outDir: File,
    val provider: String = "fake",
    val context: Int = 4_096,
    val asrDir: File? = null,
    val manifest: File? = null,
    val inputs: List<String>? = null,
    val conversations: List<String> = emptyList(),
) {
    companion object {
        /** Parses [args] (without the command name); throws [IllegalArgumentException] on bad input. */
        fun parse(args: List<String>): SummaryEvalArgs {
            val positional = mutableListOf<String>()
            val options = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val arg = args[index++]
                if (!arg.startsWith("--")) {
                    positional += arg
                    continue
                }
                val name = arg.removePrefix("--").substringBefore('=')
                require(name in OPTIONS) { "unknown option --$name (one of ${OPTIONS.joinToString { "--$it" }})" }
                options[name] = if ('=' in arg) {
                    arg.substringAfter('=')
                } else {
                    require(index < args.size) { "--$name needs a value" }
                    args[index++]
                }
            }
            require(positional.size == 1) { "summary-eval needs exactly one <outDir>" }
            val provider = options["provider"] ?: "fake"
            require(provider == "fake" || provider.startsWith("replay=")) { "--provider is fake or replay=<dir>" }
            val context = options["context"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("--context needs a number") } ?: 4_096
            fun list(name: String) = options[name]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            val inputs = list("inputs")
            val asr = options["asr"]?.let(::File)
            require(asr != null || inputs.orEmpty().all { it == SCRIPT }) { "ASR inputs need --asr <evaluate workDir>" }
            return SummaryEvalArgs(
                File(positional.single()), provider, context, asr, options["manifest"]?.let(::File), inputs, list("conversations").orEmpty(),
            )
        }

        private val OPTIONS = listOf("provider", "context", "asr", "manifest", "inputs", "conversations")
        const val SCRIPT = "script"
        val DEFAULT_ASR_INPUTS = listOf("clean", "wrist", "cafe")
        const val DEFAULT_MANIFEST = "tools/test-audio/cache/out/manifest.json"
    }
}

/**
 * The hour-summary evaluation (#197, docs/research/on-device-llm.md §7): every conversation of the
 * test-audio corpus goes, as the app's segments, through [PromptLines] and [HourSummarizer] with
 * [provider] — the app's code path, one conversation standing for one hour — and the answer is
 * scored against `testdata/summary-eval-facts.json` ([SummaryScoring]).
 */
class SummaryEval(private val provider: LlmProvider) {
    /** One conversation of one input. */
    data class Result(
        val input: String,
        val conversation: String,
        /** `done`, `refused`, `rate_limited`, `failed:<type>` or `little_speech` (no call, like the app). */
        val outcome: String,
        val chars: Int,
        val promptLines: Int,
        val calls: List<RecordingLlmProvider.Call>,
        val draft: SummaryDraft?,
        val score: SummaryScoring.Score,
    ) {
        val latencyMs: Long get() = calls.sumOf { it.latencyMs }
    }

    /** Summarizes [input] (a conversation of the input [inputName]) and scores it against [facts]. */
    suspend fun evaluate(inputName: String, input: SummaryInput, facts: ConversationFacts): Result {
        val lines = PromptLines.build(input.segments, marks = emptyList())
        if (input.chars < LITTLE_SPEECH_CHARS || lines.isEmpty()) {
            return Result(inputName, input.conversation, "little_speech", input.chars, lines.size, emptyList(), null, SummaryScoring.score(facts, null) { emptySet() })
        }
        val recorder = RecordingLlmProvider(provider)
        val outcome = HourSummarizer(recorder).summarize(lines)
        val draft = (outcome as? HourOutcome.Done)?.draft
        val byNumber = lines.associateBy { it.number }
        val cited = { h: HighlightDraft ->
            h.lines.flatMap { byNumber[it]?.segmentIds.orEmpty() }.flatMapTo(sortedSetOf()) { input.scriptLines[it].orEmpty() }
        }
        val name = when (outcome) {
            is HourOutcome.Done -> "done"
            HourOutcome.Refused -> "refused"
            is HourOutcome.RateLimited -> "rate_limited"
            is HourOutcome.Failed -> "failed:${outcome.type}"
        }
        return Result(inputName, input.conversation, name, input.chars, lines.size, recorder.calls.toList(), draft, SummaryScoring.score(facts, draft, cited))
    }

    /** The metrics of one input over its conversations (a row of the results table). */
    data class Row(
        val input: String,
        val conversations: Int,
        val facts: Int,
        val covered: Int,
        val hallucinations: Int,
        val citationsJudged: Int,
        val citationsCorrect: Int,
        /** Highlights that state no listed fact (their citations are not judged). */
        val citationsUnjudged: Int,
        val okCalls: Int,
        val schemaValid: Int,
        /** Conversations sent to the model (not 「発話が少ない」). */
        val summarized: Int,
        val refused: Int,
        val failed: Int,
        val littleSpeech: Int,
        val calls: Int,
        /** Per conversation (all its calls). */
        val latency: LatencyStats?,
    ) {
        companion object {
            fun of(input: String, results: List<Result>): Row {
                val calls = results.flatMap { it.calls }
                val summarized = results.filter { it.outcome != "little_speech" }
                val citations = results.flatMap { it.score.citations }
                return Row(
                    input = input,
                    conversations = results.size,
                    facts = results.sumOf { it.score.facts },
                    covered = results.sumOf { it.score.covered.size },
                    hallucinations = results.sumOf { it.score.hallucinations.size },
                    citationsJudged = citations.count { it.correct != null },
                    citationsCorrect = citations.count { it.correct == true },
                    citationsUnjudged = citations.count { it.correct == null },
                    okCalls = calls.count { it.result == "ok" },
                    schemaValid = calls.count { it.schemaValid == true },
                    summarized = summarized.size,
                    refused = summarized.count { it.outcome == "refused" },
                    failed = summarized.count { it.outcome != "done" && it.outcome != "refused" },
                    littleSpeech = results.size - summarized.size,
                    calls = calls.size,
                    latency = LatencyStats.of(summarized.filter { it.calls.isNotEmpty() }.map { it.latencyMs }),
                )
            }
        }
    }

    companion object {
        /** The provider named by `--provider` (add real ones here). */
        fun provider(args: SummaryEvalArgs): LlmProvider = when {
            args.provider == "fake" -> ExtractiveLlmProvider(args.context)
            args.provider.startsWith("replay=") -> ReplayLlmProvider(File(args.provider.removePrefix("replay=")), args.context)
            else -> throw IllegalArgumentException("unknown provider ${args.provider}")
        }

        private fun percent(n: Int, of: Int) = if (of == 0) "-" else "%.0f%% (%d/%d)".format(100.0 * n / of, n, of)

        private fun seconds(ms: Long) = "%.1f".format(ms / 1000.0)

        /** The results table in Markdown (Japanese headers, for the docs and the issue). */
        fun markdownTable(rows: List<Row>): String = buildString {
            appendLine("| 入力 | 会話 | 事実の再現率 | 作り話（mustNot） | 引用の正しさ | 形の成功率 | 拒否の率 | 失敗 | 発話が少ない | 呼び出し | 時間/会話 中央値・p90・最大 (s) |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            for (r in rows) {
                val latency = r.latency?.let { "${seconds(it.medianMs)} / ${seconds(it.p90Ms)} / ${seconds(it.maxMs)}" } ?: "-"
                appendLine(
                    "| ${r.input} | ${r.conversations} | ${percent(r.covered, r.facts)} | ${r.hallucinations} | " +
                        "${percent(r.citationsCorrect, r.citationsJudged)}、判定外 ${r.citationsUnjudged} | ${percent(r.schemaValid, r.okCalls)} | " +
                        "${percent(r.refused, r.summarized)} | ${r.failed} | ${r.littleSpeech} | ${r.calls} | $latency |",
                )
            }
        }

        const val TSV_HEADER = "input\tconversations\tfacts\tcovered\trecall\thallucinations\tcitations_judged\tcitations_correct\tcitation_rate\t" +
            "citations_unjudged\tok_calls\tschema_valid\tschema_rate\tsummarized\trefused\trefusal_rate\tfailed\tlittle_speech\tcalls\t" +
            "latency_median_ms\tlatency_p90_ms\tlatency_max_ms\tlatency_total_ms"

        /** The results table as TSV (one row per input, rates as fractions). */
        fun tsv(rows: List<Row>): String = buildString {
            appendLine(TSV_HEADER)
            fun rate(n: Int, of: Int) = if (of == 0) "" else "%.3f".format(n.toDouble() / of)
            for (r in rows) {
                appendLine(
                    listOf(
                        r.input, r.conversations, r.facts, r.covered, rate(r.covered, r.facts), r.hallucinations, r.citationsJudged,
                        r.citationsCorrect, rate(r.citationsCorrect, r.citationsJudged), r.citationsUnjudged, r.okCalls, r.schemaValid,
                        rate(r.schemaValid, r.okCalls), r.summarized, r.refused, rate(r.refused, r.summarized), r.failed, r.littleSpeech, r.calls,
                        r.latency?.medianMs ?: "", r.latency?.p90Ms ?: "", r.latency?.maxMs ?: "", r.latency?.totalMs ?: "",
                    ).joinToString("\t"),
                )
            }
        }

        /** One line per conversation and input. */
        fun conversationsTsv(results: List<Result>): String = buildString {
            appendLine("input\tconversation\toutcome\tchars\tprompt_lines\tcalls\tlatency_ms\tcovered\tmissed\thallucinations\tcitations_correct\tcitations_judged\tschema_valid_calls\tok_calls")
            for (r in results) {
                val citations = r.score.citations
                appendLine(
                    listOf(
                        r.input, r.conversation, r.outcome, r.chars, r.promptLines, r.calls.size, r.latencyMs,
                        r.score.covered.joinToString(","), r.score.missed.joinToString(","), r.score.hallucinations.joinToString(","),
                        citations.count { it.correct == true }, citations.count { it.correct != null },
                        r.calls.count { it.schemaValid == true }, r.calls.count { it.result == "ok" },
                    ).joinToString("\t"),
                )
            }
        }

        /** Every summary with its scoring, for a person to check what keywords cannot (§7.2 「人が確認」). */
        fun summariesMarkdown(results: List<Result>, facts: SummaryFactSet): String = buildString {
            appendLine("# 要約の評価: 会話ごとの出力")
            for (r in results) {
                val truth = facts.of(r.conversation)
                appendLine()
                appendLine("## ${r.input} / ${r.conversation}: ${r.outcome}")
                appendLine()
                appendLine("- ${r.chars} 文字・${r.promptLines} 行・呼び出し ${r.calls.size} 回・${seconds(r.latencyMs)} s")
                r.draft?.let { draft ->
                    appendLine("- 要約: ${draft.summary}")
                    r.score.citations.forEach { c ->
                        val verdict = when (c.correct) {
                            true -> "引用 正"
                            false -> "引用 誤"
                            null -> "判定外"
                        }
                        appendLine("- 要点「${c.highlight.title}」${c.highlight.body}（台本の行 ${c.citedLines.joinToString(", ")}; 事実 ${c.facts.joinToString(", ").ifEmpty { "-" }}; $verdict）")
                    }
                }
                truth?.facts?.forEach { f -> appendLine("- [${if (f.id in r.score.covered) "x" else " "}] ${f.id} ${f.text}") }
                truth?.mustNot?.filter { it.id in r.score.hallucinations }?.forEach { appendLine("- 作り話 ${it.id}: ${it.text}") }
            }
        }

        /** Runs the evaluation for [args]; returns the table printed and written to `results.md`. */
        suspend fun run(repoRoot: File, args: SummaryEvalArgs, provider: LlmProvider = provider(args)): String {
            val outDir = args.outDir.absoluteFile.apply { mkdirs() }
            val facts = SummaryFactSet.load(File(repoRoot, SummaryFactSet.PATH))
            val conversations = CorpusConversation.loadAll(File(repoRoot, CorpusConversation.DIRECTORY))
                .filter { c -> args.conversations.isEmpty() || args.conversations.any { c.id == it || c.id.startsWith("${it}_") } }
            require(conversations.isNotEmpty()) { "no conversations ${args.conversations}" }
            val manifestFile = args.manifest ?: File(repoRoot, SummaryEvalArgs.DEFAULT_MANIFEST)
            val inputNames = args.inputs ?: (
                listOf(SummaryEvalArgs.SCRIPT) +
                    SummaryEvalArgs.DEFAULT_ASR_INPUTS.filter { args.asrDir != null && File(args.asrDir, "$it/segments.tsv").exists() }
                )
            val manifest by lazy { TestSetManifest.load(manifestFile) }
            val eval = SummaryEval(provider)
            val results = mutableListOf<Result>()
            for (name in inputNames) {
                val inputs = if (name == SummaryEvalArgs.SCRIPT) {
                    conversations.map(SummaryInputs::fromScript)
                } else {
                    val asrDir = requireNotNull(args.asrDir) { "input $name needs --asr" }
                    val tsv = File(asrDir, "$name/segments.tsv")
                    require(tsv.exists()) { "no $tsv: run evaluate for $name first (docs/dev/test-audio.md)" }
                    val recording = manifest.recordings.firstOrNull { it.id == name } ?: throw IllegalArgumentException("no recording $name in $manifestFile")
                    SummaryInputs.fromAsr(recording, SignalLog.readTsv(tsv).map { it.first }, conversations)
                }
                for (input in inputs) {
                    val truth = facts.of(input.conversation) ?: continue
                    results += eval.evaluate(name, input, truth).also { println("$name ${it.conversation}: ${it.outcome}, facts ${it.score.covered.size}/${it.score.facts}") }
                }
            }
            val rows = inputNames.map { name -> Row.of(name, results.filter { it.input == name }) }
            val model = (provider.availability() as? LlmAvailability.Available)?.modelVersion ?: "-"
            val table = buildString {
                appendLine("provider: ${provider.id}, model: $model, context: ${provider.contextTokens()}, prompt version: ${SummaryPrompts.VERSION}, facts version: ${facts.version}")
                appendLine()
                append(markdownTable(rows))
            }
            File(outDir, "results.md").writeText(table)
            File(outDir, "results.tsv").writeText(tsv(rows))
            File(outDir, "conversations.tsv").writeText(conversationsTsv(results))
            File(outDir, "summaries.md").writeText(summariesMarkdown(results, facts))
            (provider as? ReplayLlmProvider)?.takeIf { it.missing > 0 }?.let {
                println("${it.missing} requests without a response were added to ${ReplayLlmProvider.REQUESTS}: answer them on the device and run again")
            }
            return table
        }
    }
}
