package app.watchaudio.android.llm

import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.summary.LlmAvailability
import app.watchaudio.core.summary.LlmRequest
import app.watchaudio.core.summary.LlmResult
import app.watchaudio.core.summary.SummaryPrompts
import app.watchaudio.core.summary.exampleJson
import app.watchaudio.core.summary.parseSummaryOutput
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Gemini Nano's provider (#205) over a fake ML Kit: AICore's statuses as the shared states, the
 * download only from [GeminiNanoLlmProvider.prepare], token limits, the request as one text with the
 * schema, and ML Kit's errors as the shared results. The ML Kit mapping itself
 * ([MlKitGeminiNano.errorOf], [MlKitGeminiNano.featureStateOf]) is checked against ML Kit's constants.
 */
class GeminiNanoLlmProviderTest {
    private class FakeNano(
        var state: FeatureState = FeatureState.AVAILABLE,
        var statusError: Exception? = null,
        var answer: () -> String? = { """{"summary": "要約。", "highlights": []}""" },
        var limit: () -> Int = { 4_096 },
        var downloadEvents: List<DownloadEvent> = emptyList(),
    ) : GeminiNano {
        var statusCalls = 0
        var downloads = 0
        val texts = mutableListOf<String>()
        val maxTokens = mutableListOf<Int>()

        override suspend fun status(): FeatureState {
            statusCalls++
            statusError?.let { throw it }
            return state
        }

        override suspend fun baseModelName(): String = "nano-v3"

        override suspend fun tokenLimit(): Int = limit()

        override suspend fun countTokens(text: String): Int = if (text == "壊れる") throw GeminiNanoException(GeminiError.OTHER, 1) else text.length * 2

        override fun download(): Flow<DownloadEvent> {
            downloads++
            if (downloadEvents.lastOrNull() == DownloadEvent.Completed) state = FeatureState.AVAILABLE
            return flowOf(*downloadEvents.toTypedArray())
        }

        override suspend fun generate(text: String, maxOutputTokens: Int): String? {
            texts += text
            maxTokens += maxOutputTokens
            return answer()
        }
    }

    private class Log : Diagnostics {
        val lines = mutableListOf<String>()

        override fun log(event: String, detail: String) {
            lines += "$event $detail"
        }
    }

    private var now = 1_000_000L
    private val request = LlmRequest("指示", "[1] 発話", SummaryPrompts.SCHEMA, 500)

    private fun provider(nano: GeminiNano, log: Diagnostics = Diagnostics.NONE) = GeminiNanoLlmProvider(nano, log) { now }

    @Test
    fun aicoreStatusesAreTheSharedStates() = runBlocking {
        val expected = mapOf(
            FeatureState.AVAILABLE to LlmAvailability.Available("nano-v3"),
            FeatureState.DOWNLOADABLE to LlmAvailability.NeedsDownload(null),
            FeatureState.DOWNLOADING to LlmAvailability.Downloading(null),
            FeatureState.UNAVAILABLE to LlmAvailability.Unsupported("gemini_nano_unavailable"),
        )
        for ((state, availability) in expected) assertEquals(availability, provider(FakeNano(state)).availability(), state.name)
        assertEquals("gemini-nano", provider(FakeNano()).id)
    }

    /** Most phones and every emulator: no AICore, or one that cannot run Gemini Nano. */
    @Test
    fun aPhoneWithoutAicoreIsUnsupported() = runBlocking {
        val incompatible = FakeNano(statusError = GeminiNanoException(GeminiError.AICORE_INCOMPATIBLE, -101))
        assertEquals(LlmAvailability.Unsupported("mlkit_aicore_incompatible"), provider(incompatible).availability())
        val binding = FakeNano(statusError = GeminiNanoException(GeminiError.OTHER, 601))
        assertEquals(LlmAvailability.Unsupported("mlkit_601"), provider(binding).availability())
        val broken = FakeNano(statusError = IllegalStateException("no service"))
        assertEquals(LlmAvailability.Unsupported("status_IllegalStateException"), provider(broken).availability())
    }

    @Test
    fun statusesAreCachedAndUnsupportedStaysForTheProcess() = runBlocking {
        val nano = FakeNano(FeatureState.AVAILABLE)
        val provider = provider(nano)
        provider.availability()
        now += GeminiNanoLlmProvider.STATUS_TTL_MS - 1
        provider.availability()
        assertEquals(1, nano.statusCalls)
        now += 1
        nano.state = FeatureState.DOWNLOADING
        assertEquals(LlmAvailability.Downloading(null), provider.availability())
        assertEquals(2, nano.statusCalls)
        now += GeminiNanoLlmProvider.DOWNLOADING_TTL_MS
        nano.state = FeatureState.UNAVAILABLE
        assertIs<LlmAvailability.Unsupported>(provider.availability())
        now += 365L * 24 * 3_600_000
        provider.availability()
        assertEquals(3, nano.statusCalls)
    }

    /** The download is the user's choice: availability never starts it, prepare does. */
    @Test
    fun theModelIsDownloadedOnlyByPrepare() = runBlocking {
        val nano = FakeNano(
            FeatureState.DOWNLOADABLE,
            downloadEvents = listOf(DownloadEvent.Started(1_000), DownloadEvent.Progress(250), DownloadEvent.Progress(1_000), DownloadEvent.Completed),
        )
        val provider = provider(nano)
        assertEquals(LlmAvailability.NeedsDownload(null), provider.availability())
        assertEquals(0, nano.downloads)
        val progress = mutableListOf<Float?>()
        assertEquals(LlmAvailability.Available("nano-v3"), provider.prepare { progress += it })
        assertEquals(1, nano.downloads)
        assertEquals(listOf(null, 0.25f, 1f, 1f), progress)
        // Ready: nothing more to download.
        provider.prepare()
        assertEquals(1, nano.downloads)
    }

    @Test
    fun aFailedDownloadIsLoggedAndTheModelStillNeedsIt() = runBlocking {
        val nano = FakeNano(FeatureState.DOWNLOADABLE, downloadEvents = listOf(DownloadEvent.Started(1_000), DownloadEvent.Failed(GeminiError.NOT_ENOUGH_DISK_SPACE)))
        val log = Log()
        assertEquals(LlmAvailability.NeedsDownload(null), provider(nano, log).prepare())
        assertEquals(listOf("summary_failed type=download error=mlkit_not_enough_disk_space"), log.lines)
    }

    @Test
    fun theContextIsAicoresLimitOrTheDocumentedOne() = runBlocking {
        assertEquals(4_096, provider(FakeNano()).contextTokens())
        assertEquals(4_000, provider(FakeNano(limit = { throw GeminiNanoException(GeminiError.NOT_AVAILABLE, 8) })).contextTokens())
        assertEquals(4_000, provider(FakeNano(limit = { 0 })).contextTokens())
        assertEquals(8, provider(FakeNano()).countTokens("要約です"))
        assertNull(provider(FakeNano()).countTokens("壊れる"))
    }

    /** No guided generation: one text with the instructions, the shape and the lines; the shared parser checks the JSON. */
    @Test
    fun theRequestIsOneTextWithTheSchema() = runBlocking {
        val fenced = "```json\n{\"summary\": \"会議の時間を決めた。\", \"highlights\": [{\"title\": \"十時から\", \"body\": \"会議は十時から。\", \"lines\": [1]}]}\n```"
        val nano = FakeNano(answer = { fenced })
        val result = provider(nano).generate(request)
        val text = nano.texts.single()
        assertTrue(text.startsWith("指示\n\n答えは次の形の JSON だけを書く"), text)
        assertTrue(SummaryPrompts.SCHEMA.exampleJson() in text, text)
        assertTrue(text.endsWith("[1] 発話"), text)
        assertEquals(500, nano.maxTokens.single())
        val json = assertIs<LlmResult.Ok>(result).json
        val draft = assertNotNull(parseSummaryOutput(json, setOf(1)))
        assertEquals("十時から", draft.highlights.single().title)
        assertEquals(LlmResult.Failed("no_output"), provider(FakeNano(answer = { null })).generate(request))
    }

    @Test
    fun mlKitErrorsAreTheSharedResults() = runBlocking {
        val expected = mapOf(
            GeminiNanoException(GeminiError.BUSY, 9, 2_000) to LlmResult.RateLimited(2_000),
            GeminiNanoException(GeminiError.QUOTA_EXCEEDED, 27) to LlmResult.RateLimited(null),
            GeminiNanoException(GeminiError.REQUEST_TOO_LARGE, 12) to LlmResult.ContextExceeded,
            GeminiNanoException(GeminiError.BLOCKED_BY_POLICY, 15) to LlmResult.Refused,
            GeminiNanoException(GeminiError.NOT_AVAILABLE, 8) to LlmResult.Failed("mlkit_not_available"),
            GeminiNanoException(GeminiError.OTHER, 606) to LlmResult.Failed("mlkit_606"),
        )
        for ((error, result) in expected) {
            assertEquals(result, provider(FakeNano(answer = { throw error })).generate(request), error.error.name)
        }
        assertEquals(LlmResult.Failed("mlkit_IllegalStateException"), provider(FakeNano(answer = { throw IllegalStateException("x") })).generate(request))
    }

    /** ML Kit refuses generation once the app is not in the foreground: the run stops like closing the screen. */
    @Test
    fun backgroundUseStopsTheRun() {
        val nano = FakeNano(answer = { throw GeminiNanoException(GeminiError.BACKGROUND_USE_BLOCKED, 30) })
        val stop = assertFailsWith<CancellationException> { runBlocking { provider(nano).generate(request) } }
        assertIs<LeftForegroundException>(stop)
    }

    @Test
    fun mlKitCodesAreMappedByTheirConstants() {
        val errors = mapOf(
            GenAiException.ErrorCode.BUSY to GeminiError.BUSY,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to GeminiError.QUOTA_EXCEEDED,
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED to GeminiError.BACKGROUND_USE_BLOCKED,
            GenAiException.ErrorCode.REQUEST_TOO_LARGE to GeminiError.REQUEST_TOO_LARGE,
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR to GeminiError.BLOCKED_BY_POLICY,
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR to GeminiError.BLOCKED_BY_POLICY,
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR to GeminiError.BLOCKED_BY_POLICY,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE to GeminiError.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE to GeminiError.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE to GeminiError.NOT_ENOUGH_DISK_SPACE,
            GenAiException.ErrorCode.UNKNOWN to GeminiError.OTHER,
            601 to GeminiError.OTHER,
        )
        for ((code, error) in errors) assertEquals(error, MlKitGeminiNano.errorOf(code), "code $code")
        val states = mapOf(
            FeatureStatus.AVAILABLE to FeatureState.AVAILABLE,
            FeatureStatus.DOWNLOADABLE to FeatureState.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING to FeatureState.DOWNLOADING,
            FeatureStatus.UNAVAILABLE to FeatureState.UNAVAILABLE,
            42 to FeatureState.UNAVAILABLE,
        )
        for ((status, state) in states) assertEquals(state, MlKitGeminiNano.featureStateOf(status), "status $status")
    }
}
