package app.watchaudio.core.summary

/**
 * The prompts and the output shape of hour summaries (#27 §4.2). Summaries are written in Japanese,
 * also for English conversations (#39 §8 decision 8). A change to the wording or to the chunking
 * raises [VERSION], which is stored with every summary.
 */
object SummaryPrompts {
    const val VERSION = 1

    /** Highlights per summary (and per part). */
    const val MAX_HIGHLIGHTS = 3

    /** Cited lines per highlight. */
    const val MAX_CITED_LINES = 5

    /** The answer's shape, for a part of an hour, a whole hour and the merge alike. */
    val SCHEMA: OutputSchema = OutputSchema.Object(
        name = "HourSummary",
        properties = listOf(
            OutputSchema.Property("summary", OutputSchema.Text("話されたことの要約（日本語）")),
            OutputSchema.Property(
                "highlights",
                OutputSchema.ListOf(
                    OutputSchema.Object(
                        name = "Highlight",
                        properties = listOf(
                            OutputSchema.Property("title", OutputSchema.Text("要点の短い見出し（日本語、20 文字以内）")),
                            OutputSchema.Property("body", OutputSchema.Text("要点の内容（日本語、1 文）")),
                            OutputSchema.Property(
                                "lines",
                                OutputSchema.ListOf(OutputSchema.Integer("根拠の行番号"), MAX_CITED_LINES, "根拠になった行の番号"),
                            ),
                        ),
                    ),
                    MAX_HIGHLIGHTS,
                    "大事な出来事・決まったこと・予定・数字（最大 3 件）",
                ),
            ),
        ),
    )

    private const val COMMON = """
- 入力にないことは書かない。推測で補わない。
- 文字起こしには聞き間違いがある。意味が通らない部分は無理に解釈しない。
- 行番号（lines）は入力に書かれている番号だけを使う。
- 必ず日本語で書く（英語の会話も日本語でまとめる）。"""

    /** One whole hour in one call. */
    val HOUR_INSTRUCTIONS = """
あなたは、本人が身につけた録音機の文字起こしから、1 時間分の会話を振り返るための要約を作ります。
入力は 1 時間分の発話で、各行は「[行番号] 発話」です。★ の付いた行は、本人が印を付けた場面です。
- summary: この 1 時間に話されたことを 2〜3 文でまとめる。
- highlights: 大事な出来事・決まったこと・予定・数字を最大 3 件。★ の行があれば優先する。それぞれ title、body、根拠の行番号 lines を付ける。$COMMON
""".trim()

    /** One part of an hour that does not fit one call. */
    val PART_INSTRUCTIONS = """
あなたは、本人が身につけた録音機の文字起こしから、会話を振り返るための要約を作ります。
入力は 1 時間分の会話の一部で、各行は「[行番号] 発話」です。★ の付いた行は、本人が印を付けた場面です。
- summary: この部分で話されたことを 1〜2 文でまとめる。
- highlights: 要点の候補を最大 3 件。★ の行があれば優先する。それぞれ title、body、根拠の行番号 lines を付ける。$COMMON
""".trim()

    /** Merging the parts of an hour (or of a group of parts) into one. */
    val MERGE_INSTRUCTIONS = """
あなたは、1 時間分の会話を振り返るための要約を作ります。
入力は、同じ 1 時間を分けて作った部分ごとの要約と、要点の候補（根拠の行番号つき）です。★ は本人が印を付けた場面の候補です。
- summary: 部分ごとの要約をまとめ、この 1 時間に話されたことを 2〜3 文で書く。
- highlights: 候補の中から大事なものを最大 3 件選ぶ（★ を優先する）。lines には、選んだ候補の行番号をそのまま使う。$COMMON
""".trim()

    /** The prompt of a part or a whole hour: its lines. */
    fun linesPrompt(lines: List<PromptLine>): String = lines.joinToString("\n") { it.render() }

    /** The prompt of a merge: the parts' summaries, then their highlights as candidates. */
    fun mergePrompt(parts: List<SummaryDraft>, marked: Set<Int>): String = buildString {
        appendLine("部分ごとの要約:")
        parts.forEachIndexed { i, part -> appendLine("(${i + 1}) ${part.summary}") }
        val candidates = parts.flatMap { it.highlights }
        if (candidates.isNotEmpty()) {
            appendLine()
            appendLine("要点の候補:")
            candidates.forEach { h ->
                val star = if (h.lines.any { it in marked }) "★" else ""
                appendLine("- $star${h.title}: ${h.body}（行 ${h.lines.joinToString(", ")}）")
            }
        }
    }.trimEnd()
}
