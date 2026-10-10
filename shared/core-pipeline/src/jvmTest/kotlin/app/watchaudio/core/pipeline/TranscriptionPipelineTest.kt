package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class TranscriptionPipelineTest {
    private val dir = Files.createTempDirectory("pipeline").toFile()
    private val store = LocalStore.open(File(dir, "db.sqlite").path)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun addChunk(id: String, start: Long) = store.transaction {
        insertChunk(
            ChunkManifest(
                chunkId = id, sessionId = "s", deviceId = "w", source = SourcePlatform.WEAROS,
                startedAtEpochMs = start, durationMs = 300_000, timeZone = "UTC",
                audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1, "00"),
                endReason = ChunkEndReason.ROTATION,
            ),
            "$id.m4a",
            0,
        )
    }

    private class FakeTranscriber(val failFor: Set<String> = emptySet()) : Transcriber {
        override val engineId = "fake"
        val calls = mutableListOf<String>()

        override suspend fun transcribe(audioFile: Path): List<TimedText> {
            val id = audioFile.name.removeSuffix(".m4a")
            calls += id
            if (id in failFor) error("decoder exploded")
            return listOf(TimedText(1_000, 2_500, "こんにちは $id", "ja"))
        }
    }

    @Test
    fun transcribesOldestFirstWithAbsoluteTimes() = runTest {
        addChunk("late", 600_000)
        addChunk("early", 0)
        val fake = FakeTranscriber()
        val result = TranscriptionPipeline(store, Path(dir.path), fake).processPending()

        assertEquals(TranscriptionPipeline.RunResult(transcribed = 2, failed = 0), result)
        assertEquals(listOf("early", "late"), fake.calls)
        val segment = store.read { segmentsBetween(600_000, 900_000) }.single()
        assertEquals(601_000, segment.startAt)
        assertEquals("こんにちは late", segment.text)
        assertEquals("fake", segment.engine)
    }

    @Test
    fun failureIsRecordedAndRetriedOnNextRunOnly() = runTest {
        addChunk("bad", 0)
        addChunk("good", 300_000)
        val fake = FakeTranscriber(failFor = setOf("bad"))
        val pipeline = TranscriptionPipeline(store, Path(dir.path), fake, maxAttempts = 2)

        assertEquals(TranscriptionPipeline.RunResult(transcribed = 1, failed = 1), pipeline.processPending())
        assertEquals(ChunkState.FAILED, store.read { chunk("bad") }!!.state)
        assertEquals("decoder exploded", store.read { chunk("bad") }!!.lastError)

        pipeline.processPending() // second attempt
        pipeline.processPending() // attempts exhausted: not tried again
        assertEquals(listOf("bad", "good", "bad"), fake.calls)
    }

    @Test
    fun stopsWhenAskedAndResumesLater() = runTest {
        addChunk("a", 0)
        addChunk("b", 300_000)
        val fake = FakeTranscriber()
        val pipeline = TranscriptionPipeline(store, Path(dir.path), fake)
        var budget = 1
        pipeline.processPending { budget-- > 0 }
        assertEquals(listOf("a"), fake.calls)
        pipeline.processPending()
        assertEquals(listOf("a", "b"), fake.calls)
    }
}
