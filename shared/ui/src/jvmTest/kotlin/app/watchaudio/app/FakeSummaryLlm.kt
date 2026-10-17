package app.watchaudio.app

import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmProvider
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import kotlinx.coroutines.CompletableDeferred

/**
 * An on-device LLM for the UI tests of hour summaries (#27): [availability] as set, a 4,096-token
 * context, and a Japanese summary citing the first line of each prompt (or [answer]). While [gate]
 * is set, generation waits for it, so a test can look at the screen mid-run.
 */
class FakeSummaryLlm(
    var availability: LlmAvailability = LlmAvailability.Available("fake-26"),
    var answer: (LlmRequest) -> LlmResult = ::defaultAnswer,
) : LlmProvider {
    override val id: String = "fake"

    var gate: CompletableDeferred<Unit>? = null
    val requests = mutableListOf<LlmRequest>()

    /** Completed once a generation waits at [gate]. */
    val waiting = CompletableDeferred<Unit>()

    override suspend fun availability(): LlmAvailability = availability

    /** What [prepare] leaves behind (e.g. the model downloaded); null: [prepare] changes nothing. */
    var afterPrepare: LlmAvailability? = null

    /** While set, [prepare] waits for it after reporting half of the download. */
    var prepareGate: CompletableDeferred<Unit>? = null
    var prepareCalls = 0

    override suspend fun prepare(onProgress: (Float?) -> Unit): LlmAvailability {
        prepareCalls++
        val after = afterPrepare ?: return availability
        onProgress(0.5f)
        prepareGate?.await()
        availability = after
        return availability
    }

    override suspend fun contextTokens(): Int = 4_096

    override suspend fun countTokens(text: String): Int? = text.length

    override suspend fun generate(request: LlmRequest): LlmResult {
        requests += request
        gate?.let {
            waiting.complete(Unit)
            it.await()
        }
        return answer(request)
    }

    companion object {
        private val LINE = Regex("""^\[(\d+)]""", RegexOption.MULTILINE)

        fun defaultAnswer(request: LlmRequest): LlmResult {
            val first = LINE.find(request.prompt)?.groupValues?.get(1) ?: "1"
            return LlmResult.Ok(
                """{"summary": "明日の会議の時間と、資料を共有する段取りについて話した。", "highlights": [""" +
                    """{"title": "会議は十時から", "body": "明日の会議の開始時刻を確認した。", "lines": [$first]}]}""",
            )
        }
    }
}
