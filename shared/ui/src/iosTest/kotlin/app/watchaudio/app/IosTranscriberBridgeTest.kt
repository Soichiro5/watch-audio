package app.watchaudio.app

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.pipeline.TranscriptionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The Kotlin side of the iOS transcriber bridge (#21) on the simulator: what the Swift
 * `SpeechAnalyzerTranscriber` returns through [IosSpeechTranscriber] is stored by the shared
 * [TranscriptionPipeline] with absolute start/end times and becomes searchable; a Swift error
 * marks the chunk failed. The Speech framework itself is exercised by the Swift `SpeechTests`.
 */
class IosTranscriberBridgeTest {
    private val fixture = IosPlaybackFixture()

    @AfterTest
    fun tearDown() = fixture.close()

    /**
     * Stands in for the Swift implementation; answers synchronously like a finished task: [segments]
     * (or [error]) in Japanese, [english] for the English pass (null: an error).
     */
    private class SwiftTranscriberStub(
        private val segments: List<IosTimedText>?,
        private val error: String? = null,
        private val english: (List<IosAudioPart>) -> List<IosTimedText>? = { emptyList() },
    ) : IosSpeechTranscriber {
        val requests = mutableListOf<List<IosAudioPart>>()
        val englishRequests = mutableListOf<List<IosAudioPart>>()

        override val language = "ja"

        override fun engineId(language: String) = "apple-speechtranscriber-$language"

        override fun prepareModel(language: String, callback: (String?) -> Unit) = callback(null)

        override fun releaseModel(language: String, callback: () -> Unit) = callback()

        override fun transcribe(parts: List<IosAudioPart>, language: String, callback: (List<IosTimedText>?, String?) -> Unit) {
            if (language == "en") {
                englishRequests += parts
                val heard = english(parts)
                callback(heard, if (heard == null) "the English model is not installed" else null)
            } else {
                requests += parts
                callback(segments, error)
            }
        }

        override fun cancel() {}
    }

    private fun ja(startMs: Long, endMs: Long, text: String) = IosTimedText(startMs, endMs, text, confidence = 0.9)

    @Test
    fun swiftSegmentsAreStoredWithAbsoluteTimes() = runBlocking {
        val chunk = fixture.seedYesterdayChunk(durationMs = 20_000, segments = null)
        val swift = SwiftTranscriberStub(
            listOf(ja(1_200, 3_400, "おはようございます"), ja(5_000, 7_500, "会議を始めます")),
        )
        val pipeline = TranscriptionPipeline(fixture.store, fixture.audioRoot, IosTranscriberAdapter(swift))

        val result = pipeline.processPending()

        assertEquals(1, result.transcribed)
        val part = swift.requests.single().single()
        assertTrue(SystemFileSystem.exists(Path(part.path)), "Swift gets the archived file: ${part.path}")
        assertEquals(0L to 20_000L, part.fromMs to part.toMs)
        val segments = fixture.store.read { segmentsBetween(chunk.startedAt, chunk.startedAt + 20_000) }
        assertEquals(
            listOf(chunk.startedAt + 1_200 to chunk.startedAt + 3_400, chunk.startedAt + 5_000 to chunk.startedAt + 7_500),
            segments.map { it.startAt to it.endAt },
        )
        assertEquals(listOf("おはようございます", "会議を始めます"), segments.map { it.text })
        assertTrue(segments.all { it.engine == "apple-speechtranscriber-ja" && it.lang == "ja" })
        assertEquals(ChunkState.TRANSCRIBED, fixture.store.read { chunk(chunk.chunkId) }?.state)
        assertEquals(listOf(chunk.chunkId), fixture.store.read { search("会議") }.map { it.segment.chunkId })
    }

    @Test
    fun aSwiftErrorMarksTheChunkFailed() = runBlocking {
        val chunk = fixture.seedYesterdayChunk(durationMs = 10_000, segments = null)
        val swift = SwiftTranscriberStub(segments = null, error = "SpeechTranscriber はこの端末で利用できません")
        val pipeline = TranscriptionPipeline(fixture.store, fixture.audioRoot, IosTranscriberAdapter(swift))

        val result = pipeline.processPending()

        assertEquals(1, result.failed)
        val stored = assertNotNull(fixture.store.read { chunk(chunk.chunkId) })
        assertEquals(ChunkState.FAILED, stored.state)
        assertTrue(stored.lastError.orEmpty().contains("SpeechTranscriber"), "${stored.lastError}")
    }

    /**
     * #170: with English on, a chunk of Japanese → English → Japanese is stored as `ja, en, ja`: the
     * Japanese model wrote a lone 「はい」 for the English sentence, so the English model hears that
     * stretch (and the pauses), and its sentence replaces 「はい」. The engine names both models.
     */
    @Test
    fun englishOnStoresTheEnglishSentenceBetweenJapaneseOnes() = runBlocking {
        val chunk = fixture.seedYesterdayChunk(durationMs = 20_000, segments = null)
        val swift = SwiftTranscriberStub(
            listOf(
                ja(500, 6_700, "おはようございます今日は午後から会議があるので資料を確認しておきます"),
                ja(8_300, 14_400, "はい"),
                ja(16_000, 19_600, "それでは三時に駅前で待ち合わせをしましょう"),
            ),
        ) {
            // One stretch: 「はい」 with the pauses around it (6.4 s..16.3 s); times are in the stretch.
            listOf(IosTimedText(1_900, 8_000, "Thank you for joining the meeting today.", confidence = 0.92))
        }
        val pipeline = TranscriptionPipeline(fixture.store, fixture.audioRoot, IosTranscriberAdapter(swift, english = { true }))

        assertEquals(1, pipeline.processPending().transcribed)

        val segments = fixture.store.read { segmentsBetween(chunk.startedAt, chunk.startedAt + 20_000) }
        assertEquals(listOf("ja", "en", "ja"), segments.map { it.lang })
        assertEquals("Thank you for joining the meeting today.", segments[1].text)
        assertEquals(chunk.startedAt + 8_300 to chunk.startedAt + 14_400, segments[1].startAt to segments[1].endAt)
        assertTrue(segments.all { it.engine == "apple-speechtranscriber-ja+apple-speechtranscriber-en" }, "${segments.map { it.engine }}")
        val stretch = swift.englishRequests.single().first()
        assertEquals(6_400L to 16_300L, stretch.fromMs to stretch.toMs)
        assertEquals(listOf(chunk.chunkId), fixture.store.read { search("meeting") }.map { it.segment.chunkId })
    }

    /** #170: Japanese-only audio is stored the same with English on and off. */
    @Test
    fun japaneseOnlyAudioIsStoredTheSameWithEnglishOnAndOff() = runBlocking {
        val japanese = listOf(
            ja(500, 6_700, "おはようございます今日は午後から会議があるので資料を確認しておきます"),
            ja(8_200, 11_900, "それでは三時に駅前で待ち合わせをしましょう"),
        )
        val stored = listOf(false, true).map { english ->
            val own = IosPlaybackFixture()
            try {
                val chunk = own.seedYesterdayChunk(durationMs = 15_000, segments = null)
                // The English model hears the pauses: silence, or a word or two.
                val swift = SwiftTranscriberStub(japanese) { listOf(IosTimedText(200, 900, "Hi.", confidence = 0.3)) }
                val pipeline = TranscriptionPipeline(own.store, own.audioRoot, IosTranscriberAdapter(swift, english = { english }))
                assertEquals(1, pipeline.processPending().transcribed)
                assertEquals(english, swift.englishRequests.isNotEmpty(), "the English model hears the pauses only when on")
                own.store.read { segmentsBetween(chunk.startedAt, chunk.startedAt + 15_000) }
                    .map { Triple(it.startAt - chunk.startedAt, it.text, it.lang) }
            } finally {
                own.close()
            }
        }
        assertEquals(stored[0], stored[1])
        assertEquals(listOf("ja", "ja"), stored[1].map { it.third })
    }

    /** #170: if the English pass fails (e.g. the OS removed the model), the chunk keeps its Japanese text. */
    @Test
    fun aFailedEnglishPassKeepsTheJapaneseText() = runBlocking {
        val chunk = fixture.seedYesterdayChunk(durationMs = 10_000, segments = null)
        val failures = mutableListOf<Exception>()
        val swift = SwiftTranscriberStub(listOf(ja(500, 7_000, "はい"))) { null }
        val pipeline = TranscriptionPipeline(
            fixture.store,
            fixture.audioRoot,
            IosTranscriberAdapter(swift, english = { true }, onEnglishFailed = { failures += it }),
        )

        assertEquals(1, pipeline.processPending().transcribed)
        val segments = fixture.store.read { segmentsBetween(chunk.startedAt, chunk.startedAt + 10_000) }
        assertEquals(listOf("はい" to "ja"), segments.map { it.text to it.lang })
        assertEquals("apple-speechtranscriber-ja", segments.single().engine)
        assertEquals(1, failures.size)
    }
}
