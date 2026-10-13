package app.watchaudio.core.agent

import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.AgentPairing
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testManifest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The tools against a real database: scope, amount limits and what each tool returns. */
class AgentToolsTest {
    private val dir = TempDir("agent-tools")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val clock = FixedClock(NOW)
    private val tools = AgentTools(store, TimeZone.UTC, clock = clock)
    private val pairing = AgentPairing(1, "laptop", "fp", null, scopeDays = 30, allowAudio = false, createdAt = 0)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun searchStaysWithinTheScope() = runTest {
        addChunk("old", NOW - 40 * DAY, "予算の話をした（四十日前）")
        addChunk("recent", NOW - 2 * DAY, "予算の話をした（二日前）")
        val result = tools.call(pairing, "search_transcripts", args("query" to "予算"))
        assertFalse(result.isError)
        assertEquals(1, result.itemCount)
        assertTrue("二日前" in result.text())
        assertFalse("四十日前" in result.text())
        assertTrue(AgentTools.NOTICE in result.text())
        assertEquals("予算", result.query)

        // Asking for older days only clamps to the scope; asking only outside it is refused.
        val clamped = tools.call(pairing, "search_transcripts", args("query" to "予算", "from" to "2020-01-01"))
        assertEquals(1, clamped.itemCount)
        val outside = tools.call(pairing, "search_transcripts", args("query" to "予算", "to" to day(NOW - 35 * DAY)))
        assertTrue(outside.isError)
        assertEquals(AgentAccessOutcome.DENIED, outside.outcome)

        // A wider scope reaches the older record.
        val wide = tools.call(pairing.copy(scopeDays = 60), "search_transcripts", args("query" to "予算"))
        assertEquals(2, wide.itemCount)
    }

    @Test
    fun searchRespectsAmountLimits() = runTest {
        val long = "会議の記録 " + "あ".repeat(490)
        addChunk("a", NOW - DAY, *Array(60) { long })
        val tooMany = tools.call(pairing, "search_transcripts", args("query" to "会議", "limit" to 51))
        assertTrue(tooMany.isError)
        assertEquals(AgentAccessOutcome.DENIED, tooMany.outcome)

        val result = tools.call(pairing, "search_transcripts", args("query" to "会議", "limit" to 50))
        assertFalse(result.isError)
        // 50 hits of ~550 characters do not fit in one response: the rest is counted, not sent.
        assertTrue(result.itemCount in 1 until 50, "itemCount ${result.itemCount}")
        assertTrue(result.text().length <= AgentLimits.MAX_RESPONSE_CHARS + 200)
        assertTrue("more not shown" in result.text())

        val defaulted = tools.call(pairing, "search_transcripts", args("query" to "会議 "))
        assertTrue(defaulted.itemCount <= AgentLimits.DEFAULT_SEARCH_RESULTS)
    }

    @Test
    fun invalidArgumentsAreRefused() = runTest {
        for ((tool, arguments) in listOf(
            "search_transcripts" to args(),
            "search_transcripts" to args("query" to "x", "from" to "yesterday"),
            "search_transcripts" to args("query" to "x".repeat(201)),
            "get_day_timeline" to args(),
            "get_context" to args("utterance_id" to "abc"),
            "get_context" to args("utterance_id" to 1, "before" to 21),
            "no_such_tool" to args(),
        )) {
            val result = tools.call(pairing, tool, arguments)
            assertTrue(result.isError, "$tool $arguments")
            assertEquals(AgentAccessOutcome.DENIED, result.outcome, "$tool $arguments")
        }
    }

    @Test
    fun contextStopsAtTheScope() = runTest {
        // One utterance per minute; the first three are older than the scope.
        val start = NOW - 30 * DAY - 3 * MINUTE
        store.transaction {
            insertChunk(testManifest("c", start, durationMs = 20 * MINUTE), "c.m4a", 0)
            saveTranscript("c", (0 until 20).map { NewSegment(start + it * MINUTE, start + it * MINUTE + 30_000, "発話 $it", "ja") }, "test")
        }
        val ids = store.read { segmentsBetween(start, start + 20 * MINUTE) }.map { it.id }
        val result = tools.call(pairing, "get_context", args("utterance_id" to ids[5], "before" to 5, "after" to 2))
        assertFalse(result.isError)
        // Utterances 0-2 are out of scope: only 3 and 4 come before 5.
        assertEquals(5, result.itemCount)
        assertTrue("> utterance ${ids[5]}" in result.text())
        assertFalse("発話 2" in result.text())

        // An out-of-scope id gets the same answer as an id that does not exist.
        val outside = tools.call(pairing, "get_context", args("utterance_id" to ids[1]))
        val missing = tools.call(pairing, "get_context", args("utterance_id" to 999_999))
        assertTrue(outside.isError && missing.isError)
        assertEquals(outside.text().replace(ids[1].toString(), "#"), missing.text().replace("999999", "#"))
        assertEquals(AgentAccessOutcome.DENIED, outside.outcome)
    }

    @Test
    fun dayTimelineGroupsConversations() = runTest {
        val dayStart = NOW - NOW % DAY - DAY // yesterday 00:00 UTC
        store.transaction {
            insertChunk(testManifest("m", dayStart + 9 * HOUR, durationMs = 5 * MINUTE), "m.m4a", 0)
            saveTranscript(
                "m",
                listOf(
                    NewSegment(dayStart + 9 * HOUR, dayStart + 9 * HOUR + 10_000, "おはようございます", "ja"),
                    NewSegment(dayStart + 9 * HOUR + 4 * MINUTE, dayStart + 9 * HOUR + 5 * MINUTE, "今日の予定は", "ja"),
                ),
                "test",
            )
            insertChunk(testManifest("e", dayStart + 18 * HOUR, durationMs = 5 * MINUTE), "e.m4a", 0)
            saveTranscript("e", listOf(NewSegment(dayStart + 18 * HOUR, dayStart + 18 * HOUR + 10_000, "おつかれさま", "ja")), "test")
        }
        val result = tools.call(pairing, "get_day_timeline", args("date" to day(dayStart)))
        assertFalse(result.isError)
        assertEquals(2, result.itemCount)
        assertTrue("conversation 1 | 09:00–09:05" in result.text(), result.text())
        assertTrue("おはようございます 今日の予定は" in result.text())
        assertTrue("conversation 2 | 18:00" in result.text())

        val outside = tools.call(pairing, "get_day_timeline", args("date" to day(NOW - 31 * DAY)))
        assertTrue(outside.isError)
        assertEquals(AgentAccessOutcome.DENIED, outside.outcome)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun audioOnlyForPairingsThatAllowIt() = runTest {
        addChunk("a", NOW - DAY, "音声つきの発話")
        val id = store.read { segmentsBetween(0, Long.MAX_VALUE) }.single().id
        val withAudio = AgentTools(store, TimeZone.UTC, audio = { _, _, duration -> FloatArray((duration * 16).toInt()) { 0.25f } }, clock = clock)

        val denied = withAudio.call(pairing, "get_audio_clip", args("utterance_id" to id))
        assertTrue(denied.isError)
        assertEquals(AgentAccessOutcome.DENIED, denied.outcome)
        assertTrue(denied.content.none { it is ToolContent.Audio })

        val allowed = withAudio.call(pairing.copy(allowAudio = true), "get_audio_clip", args("utterance_id" to id))
        assertFalse(allowed.isError)
        val clip = allowed.content.filterIsInstance<ToolContent.Audio>().single()
        assertEquals("audio/wav", clip.mimeType)
        val wav = Base64.Default.decode(clip.data)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        // The utterance (10 s) plus a second on each side, 16-bit samples at 16 kHz.
        assertEquals(44 + 12_000 * 16 * 2, wav.size)

        // Without a decoder on the platform the tool says so instead of failing.
        val noDecoder = tools.call(pairing.copy(allowAudio = true), "get_audio_clip", args("utterance_id" to id))
        assertTrue(noDecoder.isError)
        assertEquals(AgentAccessOutcome.ERROR, noDecoder.outcome)
    }

    private suspend fun addChunk(id: String, startedAt: Long, vararg texts: String) {
        store.transaction {
            insertChunk(testManifest(id, startedAt), "$id.m4a", 0)
            saveTranscript(
                id,
                texts.mapIndexed { i, text -> NewSegment(startedAt + i * 1_000L + 1_000, startedAt + i * 1_000L + 11_000, text, "ja") },
                "test",
            )
        }
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        /** 2026-10-13T12:00:00Z. */
        const val NOW = 1_791_892_800_000L

        fun args(vararg pairs: Pair<String, Any>) = JsonObject(
            pairs.associate { (key, value) ->
                key to when (value) {
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            },
        )

        fun day(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString().take(10)

        fun ToolResult.text(): String = content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }
    }
}

class FixedClock(var nowMs: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}
