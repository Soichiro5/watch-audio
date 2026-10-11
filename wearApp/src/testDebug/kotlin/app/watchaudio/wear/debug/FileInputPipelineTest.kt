package app.watchaudio.wear.debug

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.wear.outbox.Outbox
import app.watchaudio.wear.recording.AudioFormat
import app.watchaudio.wear.recording.ChunkFinalizer
import app.watchaudio.wear.recording.ChunkJournal
import app.watchaudio.wear.recording.ChunkTracker
import app.watchaudio.wear.recording.RecordingDir
import app.watchaudio.wear.recording.SilenceDetector
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Drives known audio through the debug file input's chunking ([WavPcmSource] → [PcmChunker])
 * into the production bookkeeping ([ChunkTracker], [SilenceDetector], [ChunkFinalizer],
 * [Outbox]) the way `RecordingService` does, and checks what lands in the outbox. Only the AAC
 * encoder is replaced ([RawPcmSink]; MediaCodec needs a device).
 */
class FileInputPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rate = AudioFormat.SAMPLE_RATE_HZ

    /**
     * Tone until 4:58, quiet room until 10:00, tone until 12:30. The quiet part starts a little
     * before the rotation: the level poll right after a rotation still covers the end of the
     * previous chunk (as with `MediaRecorder.getMaxAmplitude()`).
     */
    private fun sample(index: Long): Short = if (index in 298L * rate until 600L * rate) TestAudio.quiet(index) else TestAudio.tone(index, rate)

    @Test
    fun `known audio yields 5-minute chunks, a silent manifest, marks and outbox entries`() {
        val wav = tmp.newFile("input.wav")
        TestAudio.writeWav(wav, rate, channels = 1, frames = 750L * rate) { frame, _ -> sample(frame) }

        val run = PipelineRun(tmp.newFolder("recording"), tmp.newFolder("outbox"))
        run.play(WavPcmSource(wav.inputStream()), marksAtMs = listOf(30_000, 420_000, 700_000))

        val entries = run.outbox.entries()
        assertEquals(3, entries.size, "750 s of audio = 300 s + 300 s + 150 s")
        val (first, silent, last) = entries.map { it.manifest }

        assertEquals(listOf(300_000L, 300_000, 150_000), entries.map { it.manifest.durationMs })
        assertEquals(listOf(ChunkEndReason.ROTATION, ChunkEndReason.ROTATION, ChunkEndReason.USER_STOP), entries.map { it.manifest.endReason })
        assertEquals(listOf(START, START + 300_000, START + 600_000), entries.map { it.manifest.startedAtEpochMs }, "chunks tile the timeline")
        assertEquals(setOf("session-1"), entries.map { it.manifest.sessionId }.toSet())
        assertTrue(entries.all { it.manifest.source == SourcePlatform.WEAROS && it.manifest.deviceId == "device-1" })

        // Speech-like chunks keep their audio, hashed as it lies in the outbox.
        for ((manifest, seconds) in listOf(first to 300L, last to 150L)) {
            val audio = assertNotNull(manifest.audio, manifest.chunkId)
            assertEquals(AudioCodec.AAC, audio.codec)
            assertEquals(seconds * rate * 2, audio.sizeBytes, "sample-exact chunk boundaries")
            val file = File(run.outbox.dir, audio.fileName)
            assertEquals(ChunkFinalizer.sha256Hex(file), audio.sha256)
        }
        assertEquals(pcmSha256(0, 300L * rate), first.audio!!.sha256, "first chunk holds exactly the first 5 minutes")
        assertEquals(pcmSha256(600L * rate, 750L * rate), last.audio!!.sha256, "last chunk holds 10:00–12:30")

        // The quiet chunk keeps its time range but no audio.
        assertNull(silent.audio)
        assertFalse(File(run.outbox.dir, ChunkJournal.audioFileName(silent.chunkId)).exists())

        assertEquals(listOf(30_000L), first.markOffsetsMs)
        assertEquals(listOf(120_000L), silent.markOffsetsMs)
        assertEquals(listOf(100_000L), last.markOffsetsMs)

        assertEquals(emptyList(), run.recordingDir.journals(), "nothing left in progress")
        assertEquals(3, run.outbox.count())
    }

    @Test
    fun `other sample rates and stereo are converted to 16 kHz mono`() {
        val wav = tmp.newFile("stereo48k.wav")
        // 6 s of tone in the left channel only, then 14 s of silence: one chunk of 20 s.
        TestAudio.writeWav(wav, 48_000, channels = 2, frames = 20L * 48_000) { frame, channel ->
            if (channel == 0 && frame < 6L * 48_000) TestAudio.tone(frame, 48_000, amplitude = 16_000) else 0
        }

        val run = PipelineRun(tmp.newFolder("recording"), tmp.newFolder("outbox"))
        run.play(WavPcmSource(wav.inputStream()))

        val manifest = run.outbox.entries().single().manifest
        assertEquals(20_000, manifest.durationMs)
        assertEquals(20L * rate * 2, manifest.audio?.sizeBytes, "resampled to 16 kHz mono")
    }

    @Test
    fun `a short quiet recording keeps its audio (too few polls to be sure)`() {
        val wav = tmp.newFile("short.wav")
        TestAudio.writeWav(wav, rate, channels = 1, frames = 5L * rate) { frame, _ -> TestAudio.quiet(frame) }

        val run = PipelineRun(tmp.newFolder("recording"), tmp.newFolder("outbox"))
        run.play(WavPcmSource(wav.inputStream()))

        val manifest = run.outbox.entries().single().manifest
        assertEquals(5_000, manifest.durationMs)
        assertNotNull(manifest.audio, "fewer than ${SilenceDetector.MIN_SAMPLES} polls: never declared silent")
    }

    private fun pcmSha256(fromSample: Long, toSample: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(2)
        for (i in fromSample until toSample) {
            val s = sample(i).toInt()
            bytes[0] = (s and 0xff).toByte()
            bytes[1] = ((s shr 8) and 0xff).toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * What `RecordingService` does with a [app.watchaudio.wear.recording.RecorderInput], minus
     * Android: ids per rotation, one level poll per second of audio, marks on the input's clock,
     * finalization of every closed chunk, and a user stop at the end of the file.
     */
    private class PipelineRun(recordingDirFile: File, outboxDir: File) {
        val recordingDir = RecordingDir(recordingDirFile)
        val outbox = Outbox(outboxDir) { 0L }
        private val finalizer = ChunkFinalizer(recordingDir, outbox)
        private var nextId = 1
        private var pendingNextId: String? = null
        private lateinit var tracker: ChunkTracker

        private val chunker = PcmChunker(
            RawPcmSink(),
            object : PcmChunker.Events {
                override fun nextFile(): File = "chunk-${nextId++}".let {
                    pendingNextId = it
                    recordingDir.audioFile(it)
                }

                override fun rotated(positionMs: Long) {
                    val closed = tracker.rotate(pendingNextId!!, positionMs, TIME_ZONE)
                    recordingDir.write(tracker.snapshot())
                    finalizer.finalize(closed.journal, closed.durationMs, ChunkEndReason.ROTATION, audioUsable = true)
                }
            },
        )

        fun play(source: MonoPcmSource, marksAtMs: List<Long> = emptyList()) {
            val firstId = "chunk-${nextId++}"
            chunker.start(recordingDir.audioFile(firstId))
            tracker = ChunkTracker(
                ChunkJournal(firstId, "session-1", "device-1", START, TIME_ZONE),
                startElapsedMs = 0,
            )
            recordingDir.write(tracker.snapshot())

            val marks = ArrayDeque(marksAtMs.sorted())
            var nextPollMs = SilenceDetector.POLL_INTERVAL_MS
            val buffer = ShortArray(AudioFormat.SAMPLE_RATE_HZ / 10)
            source.use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    assertTrue(chunker.write(buffer, n))
                    while (chunker.positionMs >= nextPollMs) {
                        tracker.addAmplitude(chunker.pollPeak())
                        nextPollMs += SilenceDetector.POLL_INTERVAL_MS
                    }
                    while (marks.isNotEmpty() && chunker.positionMs >= marks.first()) tracker.mark(marks.removeFirst())
                }
            }
            val closed = tracker.end(chunker.positionMs)
            val clean = chunker.finish()
            finalizer.finalize(closed.journal, closed.durationMs, ChunkEndReason.USER_STOP, audioUsable = clean)
        }
    }

    private companion object {
        const val START = 1_791_622_800_000
        const val TIME_ZONE = "Asia/Tokyo"
    }
}
