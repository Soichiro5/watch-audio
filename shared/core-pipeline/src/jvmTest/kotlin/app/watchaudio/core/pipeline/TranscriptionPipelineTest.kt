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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class TranscriptionPipelineTest {
    private val dir = Files.createTempDirectory("pipeline").toFile()
    private val store = LocalStore.open(File(dir, "db.sqlite").path)

    /** Chunk start times by id, so fake transcribers can place parts on the absolute timeline. */
    private val starts = mutableMapOf<String, Long>()

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private suspend fun addChunk(
        id: String,
        start: Long,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        session: String = "s",
    ) = store.transaction {
        starts[id] = start
        insertChunk(
            ChunkManifest(
                chunkId = id, sessionId = session, deviceId = "w", source = SourcePlatform.WEAROS,
                startedAtEpochMs = start, durationMs = 300_000, timeZone = "UTC",
                audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1, "00"),
                endReason = endReason,
            ),
            "$id.m4a",
            0,
        )
    }

    private val Path.chunkId get() = name.removeSuffix(".m4a")

    private class FakeTranscriber(val failFor: Set<String> = emptySet()) : Transcriber {
        override val engineId = "fake"
        val calls = mutableListOf<String>()

        override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
            val id = parts.single().file.name.removeSuffix(".m4a")
            calls += id
            if (id in failFor) error("decoder exploded")
            return listOf(TimedText(1_000, 2_500, "こんにちは $id", "ja"))
        }
    }

    /**
     * Simulates speech at absolute times: "recognises" every utterance heard in the given parts,
     * marking the ones the audio cuts off with `…` on the clipped side.
     */
    private inner class SpeechWorld(vararg val utterances: Triple<Long, Long, String>) : Transcriber {
        override val engineId = "world"
        val calls = mutableListOf<List<AudioPart>>()
        var failJoined = false

        override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
            calls += parts
            if (failJoined && parts.size > 1) error("neighbour audio unreadable")
            val results = mutableListOf<TimedText>()
            var offset = 0L
            val heard = mutableSetOf<String>()
            for (part in parts) {
                val from = starts.getValue(part.file.chunkId) + part.fromMs
                val to = from + part.durationMs
                for ((start, end, text) in utterances) {
                    if (end <= from || start >= to || text in heard) continue
                    heard += text
                    // Contiguous parts: the utterance may continue into the following parts.
                    var coveredTo = to
                    for (next in parts.drop(parts.indexOf(part) + 1)) {
                        if (coveredTo >= end) break
                        coveredTo = starts.getValue(next.file.chunkId) + next.fromMs + next.durationMs
                    }
                    val clippedStart = start < from
                    val clippedEnd = end > coveredTo
                    val label = (if (clippedStart) "…" else "") + text + (if (clippedEnd) "…" else "")
                    results += TimedText(offset + maxOf(start, from) - from, offset + minOf(end, coveredTo) - from, label, "ja")
                }
                offset += part.durationMs
            }
            return results
        }
    }

    private suspend fun transcript() = store.read { segmentsBetween(0, Long.MAX_VALUE) }
        .map { Triple(it.chunkId, it.startAt to it.endAt, it.text) }

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

    @Test
    fun cancellationPutsTheChunkBackInTheQueue() = runTest {
        addChunk("a", 0)
        val blocking = object : Transcriber {
            override val engineId = "blocking"
            override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = kotlinx.coroutines.awaitCancellation()
        }
        val job = launch { TranscriptionPipeline(store, Path(dir.path), blocking).processPending() }
        testScheduler.advanceUntilIdle()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            while (store.read { chunk("a") }!!.state != ChunkState.TRANSCRIBING) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        job.join()
        assertEquals(ChunkState.RECEIVED, store.read { chunk("a") }!!.state)
    }

    private val crossing = SpeechWorld(
        Triple(1_000L, 2_500L, "おはよう"),
        Triple(298_000L, 303_000L, "境界をまたぐ発話"),
        Triple(310_000L, 312_000L, "その後"),
    )

    private val joinedTranscript = listOf(
        Triple("a", 1_000L to 2_500L, "おはよう"),
        Triple("a", 298_000L to 303_000L, "境界をまたぐ発話"),
        Triple("b", 310_000L to 312_000L, "その後"),
    )

    @Test
    fun utteranceAcrossTheBoundaryIsTranscribedOnceInOnePiece() = runTest {
        addChunk("a", 0)
        addChunk("b", 300_000)
        TranscriptionPipeline(store, Path(dir.path), crossing).processPending()

        assertEquals(joinedTranscript, transcript())
        // b was transcribed with a's tail, from just before the cut utterance.
        assertEquals(
            listOf(AudioPart(Path(dir.path, "a.m4a"), 297_500, 300_000), AudioPart(Path(dir.path, "b.m4a"), 0, 300_000)),
            crossing.calls.last(),
        )
    }

    @Test
    fun laterChunkArrivingFirstIsRepairedWhenTheEarlierOneArrives() = runTest {
        val pipeline = TranscriptionPipeline(store, Path(dir.path), crossing)
        addChunk("b", 300_000)
        pipeline.processPending()
        assertEquals(listOf(Triple("b", 300_000L to 303_000L, "…境界をまたぐ発話"), Triple("b", 310_000L to 312_000L, "その後")), transcript())

        addChunk("a", 0)
        pipeline.processPending()
        assertEquals(joinedTranscript, transcript())
        assertEquals(
            listOf(AudioPart(Path(dir.path, "a.m4a"), 0, 300_000), AudioPart(Path(dir.path, "b.m4a"), 0, 3_500)),
            crossing.calls.last(),
        )
    }

    @Test
    fun missingMiddleChunkJoinsBothNeighbours() = runTest {
        val world = SpeechWorld(
            Triple(299_000L, 301_000L, "前の境界"),
            Triple(400_000L, 401_000L, "真ん中"),
            Triple(598_000L, 603_000L, "後の境界"),
        )
        val pipeline = TranscriptionPipeline(store, Path(dir.path), world)
        addChunk("a", 0)
        addChunk("c", 600_000)
        pipeline.processPending()
        addChunk("b", 300_000)
        pipeline.processPending()

        assertEquals(
            listOf(
                Triple("a", 299_000L to 301_000L, "前の境界"),
                Triple("b", 400_000L to 401_000L, "真ん中"),
                Triple("b", 598_000L to 603_000L, "後の境界"),
            ),
            transcript(),
        )
        assertEquals(3, world.calls.last().size)
    }

    @Test
    fun noJoinAcrossAGapOrAnotherSession() = runTest {
        addChunk("a", 0, endReason = ChunkEndReason.USER_STOP)
        addChunk("b", 300_000)
        addChunk("c", 600_000, session = "other")
        val world = SpeechWorld(
            Triple(298_000L, 303_000L, "停止をまたぐ"),
            Triple(598_000L, 603_000L, "別セッション"),
        )
        TranscriptionPipeline(store, Path(dir.path), world).processPending()

        assertEquals(
            listOf(
                Triple("a", 298_000L to 300_000L, "停止をまたぐ…"),
                Triple("b", 300_000L to 303_000L, "…停止をまたぐ"),
                Triple("b", 598_000L to 600_000L, "別セッション…"),
                Triple("c", 600_000L to 603_000L, "…別セッション"),
            ),
            transcript(),
        )
        assertEquals(listOf(1, 1, 1), world.calls.map { it.size })
    }

    @Test
    fun failingJoinFallsBackToTheChunkAlone() = runTest {
        addChunk("a", 0)
        addChunk("b", 300_000)
        crossing.failJoined = true
        val result = TranscriptionPipeline(store, Path(dir.path), crossing).processPending()

        assertEquals(TranscriptionPipeline.RunResult(transcribed = 2, failed = 0), result)
        assertEquals(listOf(1, 2, 1), crossing.calls.map { it.size })
        assertEquals("…境界をまたぐ発話", transcript().first { it.first == "b" }.third)
    }

    @Test
    fun retranscribingAJoinedChunkDoesNotDuplicate() = runTest {
        addChunk("a", 0)
        addChunk("b", 300_000)
        val pipeline = TranscriptionPipeline(store, Path(dir.path), crossing)
        pipeline.processPending()
        store.transaction {
            markTranscribing("b")
            markFailed("b", "retry")
            requeueFailed(listOf("b"))
        }
        pipeline.processPending()
        assertEquals(joinedTranscript, transcript())
    }
}
