package app.watchaudio.core.summary

import kotlin.math.ceil

/**
 * An [LlmProvider] for tests (#27 §4.1): answers from [scripted] first, then from [respond] (by
 * default [echo], a valid summary citing the prompt's lines). Every request is checked against the
 * context the way the platform would: instructions + prompt + schema + output at most [context]
 * tokens, counted at [tokensPerChar] (rounded up); a request that does not fit fails the test.
 */
class FakeLlmProvider(
    var availability: LlmAvailability = LlmAvailability.Available("fake-1"),
    private val context: Int = 4_096,
    /** Whether [countTokens] answers (iOS 26.4+) or returns null (older: the caller estimates). */
    private val countsTokens: Boolean = true,
    private val tokensPerChar: Double = 1.0,
    private val respond: (LlmRequest) -> LlmResult = ::echo,
) : LlmProvider {
    override val id: String = "fake"

    val requests = mutableListOf<LlmRequest>()
    val scripted = ArrayDeque<LlmResult>()

    /** Largest request seen, in tokens. */
    var largestRequest = 0
        private set

    override suspend fun availability(): LlmAvailability = availability

    override suspend fun contextTokens(): Int = context

    override suspend fun countTokens(text: String): Int? = if (countsTokens) count(text) else null

    override suspend fun generate(request: LlmRequest): LlmResult {
        requests += request
        val used = count(request.instructions) + count(request.prompt) + count(request.schema.toJson()) + request.maxOutputTokens
        largestRequest = maxOf(largestRequest, used)
        if (used > context) throw AssertionError("request of $used tokens exceeds the context of $context")
        return scripted.removeFirstOrNull() ?: respond(request)
    }

    private fun count(text: String): Int = ceil(text.length * tokensPerChar).toInt()

    companion object {
        private val LINE = Regex("""^\[(\d+)]""", RegexOption.MULTILINE)
        private val CITED = Regex("""（行 ([\d, ]+)）""")

        /** Line numbers in a prompt: the `[n]` lines, or the candidates' 「（行 n, m）」 of a merge. */
        fun linesOf(prompt: String): List<Int> {
            val lines = LINE.findAll(prompt).map { it.groupValues[1].toInt() }.toList()
            return lines.ifEmpty { CITED.findAll(prompt).flatMap { m -> m.groupValues[1].split(",").map { it.trim().toInt() } }.toList() }
        }

        /** A valid answer citing the first and the last line of the prompt. */
        fun echo(request: LlmRequest): LlmResult {
            val lines = linesOf(request.prompt)
            val first = lines.firstOrNull() ?: 0
            val last = lines.lastOrNull() ?: 0
            return LlmResult.Ok(
                """{"summary": "${lines.size} 行の会話の要約。", "highlights": [""" +
                    """{"title": "最初", "body": "行 $first の話", "lines": [$first]},""" +
                    """{"title": "最後", "body": "行 $last の話", "lines": [$last]}]}""",
            )
        }
    }
}
