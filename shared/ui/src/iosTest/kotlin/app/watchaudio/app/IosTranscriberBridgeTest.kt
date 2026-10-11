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

    /** Stands in for the Swift implementation; answers synchronously like a finished task. */
    private class SwiftTranscriberStub(
        private val segments: List<IosTimedText>?,
        private val error: String? = null,
    ) : IosSpeechTranscriber {
        val requests = mutableListOf<List<IosAudioPart>>()

        override val engineId = "apple-speechtranscriber-ja"
        override val language = "ja"

        override fun prepareModel(callback: (String?) -> Unit) = callback(null)

        override fun transcribe(parts: List<IosAudioPart>, callback: (List<IosTimedText>?, String?) -> Unit) {
            requests += parts
            callback(segments, error)
        }

        override fun cancel() {}
    }

    @Test
    fun swiftSegmentsAreStoredWithAbsoluteTimes() = runBlocking {
        val chunk = fixture.seedYesterdayChunk(durationMs = 20_000, segments = null)
        val swift = SwiftTranscriberStub(
            listOf(IosTimedText(1_200, 3_400, "おはようございます"), IosTimedText(5_000, 7_500, "会議を始めます")),
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
}
