package app.watchaudio.harness

import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmProvider
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.SummaryPrompts
import app.watchaudio.core.summary.toJson
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A provider that knows how long a call took on the model it stands for (a replay of a device run). */
interface ReportsLatency {
    /** Latency of the last [LlmProvider.generate] of [request], or null to measure the wall clock. */
    fun latencyMsOf(request: LlmRequest): Long?
}

/**
 * Wraps [inner] and logs every call for the metrics (#197): which kind of call it was, its result,
 * whether an answer had the schema's shape ([SummaryScoring.conformsToSchema]) and its latency.
 */
class RecordingLlmProvider(
    private val inner: LlmProvider,
    private val nanoTime: () -> Long = System::nanoTime,
) : LlmProvider by inner {
    data class Call(
        /** `hour`, `part` or `merge` ([SummaryPrompts]' instructions), `other` otherwise. */
        val kind: String,
        val promptChars: Int,
        /** `ok`, `refused`, `context_exceeded`, `rate_limited` or `failed:<type>`. */
        val result: String,
        /** For `ok`: whether the answer had the schema's shape; null otherwise. */
        val schemaValid: Boolean?,
        val latencyMs: Long,
    )

    val calls = mutableListOf<Call>()

    override suspend fun generate(request: LlmRequest): LlmResult {
        val started = nanoTime()
        val result = inner.generate(request)
        val latency = (inner as? ReportsLatency)?.latencyMsOf(request) ?: ((nanoTime() - started) / 1_000_000)
        calls += Call(
            kind = when (request.instructions) {
                SummaryPrompts.HOUR_INSTRUCTIONS -> "hour"
                SummaryPrompts.PART_INSTRUCTIONS -> "part"
                SummaryPrompts.MERGE_INSTRUCTIONS -> "merge"
                else -> "other"
            },
            promptChars = request.prompt.length,
            result = when (result) {
                is LlmResult.Ok -> "ok"
                LlmResult.Refused -> "refused"
                LlmResult.ContextExceeded -> "context_exceeded"
                is LlmResult.RateLimited -> "rate_limited"
                is LlmResult.Failed -> "failed:${result.type}"
            },
            schemaValid = (result as? LlmResult.Ok)?.let { SummaryScoring.conformsToSchema(it.json, request.schema) },
            latencyMs = latency,
        )
        return result
    }
}

/**
 * The pipeline dry run's model (`--provider fake`, CI): no model at all, but answers of the right
 * shape built from the prompt, so the evaluation runs end to end and its numbers mean something
 * as a floor. A lines prompt (`[n] text`) gets its first line as the summary and its three
 * longest lines (cut to a sentence's length) as highlights citing themselves; a merge prompt gets the parts' summaries and its
 * first three candidates. A request larger than [context] (1 character = 1 token) is
 * [LlmResult.ContextExceeded], as on a device.
 */
class ExtractiveLlmProvider(private val context: Int = 4_096) : LlmProvider {
    override val id: String = "fake-extractive"

    override suspend fun availability(): LlmAvailability = LlmAvailability.Available("fake-1")

    override suspend fun contextTokens(): Int = context

    override suspend fun countTokens(text: String): Int = text.length

    override suspend fun generate(request: LlmRequest): LlmResult {
        val used = request.instructions.length + request.prompt.length + request.schema.toJson().length + request.maxOutputTokens
        if (used > context) return LlmResult.ContextExceeded
        val lines = LINE.findAll(request.prompt).map { it.groupValues[1].toInt() to it.groupValues[2].trim() }.toList()
        return LlmResult.Ok(if (lines.isNotEmpty()) fromLines(lines) else fromMerge(request.prompt))
    }

    private fun fromLines(lines: List<Pair<Int, String>>): String {
        val summary = lines.first().second.take(SUMMARY_CHARS)
        val highlights = lines.sortedByDescending { it.second.length }.take(SummaryPrompts.MAX_HIGHLIGHTS).sortedBy { it.first }
            .map { (number, text) -> Triple(text.take(TITLE_CHARS), text.take(BODY_CHARS), listOf(number)) }
        return answer(summary, highlights)
    }

    private fun fromMerge(prompt: String): String {
        val summary = PART.findAll(prompt).joinToString(" ") { it.groupValues[1].trim() }.ifEmpty { "要約" }
        val highlights = CANDIDATE.findAll(prompt).take(SummaryPrompts.MAX_HIGHLIGHTS).map { m ->
            Triple(m.groupValues[1].trim(), m.groupValues[2].trim(), m.groupValues[3].split(',').mapNotNull { it.trim().toIntOrNull() })
        }.toList()
        return answer(summary, highlights)
    }

    private fun answer(summary: String, highlights: List<Triple<String, String, List<Int>>>): String = buildJsonObject {
        put("summary", summary)
        put(
            "highlights",
            JsonArray(
                highlights.map { (title, body, lines) ->
                    buildJsonObject {
                        put("title", title)
                        put("body", body)
                        put("lines", JsonArray(lines.map { JsonPrimitive(it) }))
                    }
                },
            ),
        )
    }.toString()

    private companion object {
        /** About what a model writes: a sentence of summary per part, a short sentence per highlight. */
        const val SUMMARY_CHARS = 60
        const val TITLE_CHARS = 20
        const val BODY_CHARS = 50
        val LINE = Regex("""^\[(\d+)]★? (.*)$""", RegexOption.MULTILINE)
        val PART = Regex("""^\(\d+\) (.*)$""", RegexOption.MULTILINE)
        val CANDIDATE = Regex("""^- ★?([^:\n]*): (.*)（行 ([\d, ]+)）$""", RegexOption.MULTILINE)
    }
}

/**
 * A model that runs somewhere else (Apple Foundation Models on a Mac or an iPhone, Gemini Nano or
 * LiteRT-LM on an Android device), connected by two JSON Lines files in [dir]:
 *
 * - `requests.jsonl`: written here. Every request the summarizer makes that has no answer yet is
 *   appended (once): `{"id", "instructions", "prompt", "schema", "maxOutputTokens"}`, `schema` being
 *   [app.watchaudio.core.summary.OutputSchema.toJson] and `id` a hash of the rest ([requestId]).
 * - `responses.jsonl`: written by the device run, one line per request id:
 *   `{"id", "ok": "<answer JSON>"}` or `{"id", "error": "<code>"}`, plus optional `"latencyMs"` and
 *   `"model"` (the model version). Error codes are those of `OnDeviceLanguageModel.errorCode`
 *   (mapped as the iOS app maps them, [resultOf]).
 *
 * A request without a response is [LlmResult.Failed] (`no_response`). The device run answers each
 * request once; a retry of the same request gets the same answer (Apple's greedy sampling would
 * give the same answer anyway). Run the evaluation, answer the new requests on the device, and run
 * again until no request is missing (one round when every conversation fits one call).
 */
class ReplayLlmProvider(
    private val dir: File,
    private val context: Int = 4_096,
    override val id: String = "replay",
) : LlmProvider, ReportsLatency {
    @Serializable
    data class Request(val id: String, val instructions: String, val prompt: String, val schema: String, val maxOutputTokens: Int)

    @Serializable
    data class Response(val id: String, val ok: String? = null, val error: String? = null, val latencyMs: Long? = null, val model: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val responses: Map<String, Response> = File(dir, RESPONSES).takeIf { it.exists() }?.readLines().orEmpty()
        .filter { it.isNotBlank() }.map { json.decodeFromString(Response.serializer(), it) }.associateBy { it.id }
    private val requested: MutableSet<String> = File(dir, REQUESTS).takeIf { it.exists() }?.readLines().orEmpty()
        .filter { it.isNotBlank() }.mapTo(HashSet()) { json.decodeFromString(Request.serializer(), it).id }

    /** Requests appended to `requests.jsonl` by this run. */
    var missing = 0
        private set

    override suspend fun availability(): LlmAvailability = LlmAvailability.Available(responses.values.firstNotNullOfOrNull { it.model })

    override suspend fun contextTokens(): Int = context

    override suspend fun countTokens(text: String): Int? = null

    override suspend fun generate(request: LlmRequest): LlmResult {
        val id = requestId(request)
        val response = responses[id]
        if (response == null) {
            if (requested.add(id)) {
                dir.mkdirs()
                val line = Request(id, request.instructions, request.prompt, request.schema.toJson(), request.maxOutputTokens)
                File(dir, REQUESTS).appendText(json.encodeToString(Request.serializer(), line) + "\n")
                missing++
            }
            return LlmResult.Failed("no_response")
        }
        return response.ok?.let { LlmResult.Ok(it) } ?: resultOf(response.error ?: "no_answer")
    }

    override fun latencyMsOf(request: LlmRequest): Long? = responses[requestId(request)]?.latencyMs

    companion object {
        const val REQUESTS = "requests.jsonl"
        const val RESPONSES = "responses.jsonl"

        /** Stable id of a request: SHA-256 of its parts, first 16 hex digits. */
        fun requestId(request: LlmRequest): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(request.instructions, request.prompt, request.schema.toJson(), request.maxOutputTokens.toString())
                .forEach { digest.update(it.toByteArray()); digest.update(0.toByte()) }
            return digest.digest().take(8).joinToString("") { "%02x".format(it) }
        }

        /** The app's mapping of a device error code (Kotlin `IosLlmProvider.resultOf`). */
        fun resultOf(error: String): LlmResult = when (error) {
            "guardrail_violation", "refusal" -> LlmResult.Refused
            "exceeded_context_window" -> LlmResult.ContextExceeded
            "rate_limited", "concurrent_requests" -> LlmResult.RateLimited(null)
            else -> LlmResult.Failed(error)
        }
    }
}
