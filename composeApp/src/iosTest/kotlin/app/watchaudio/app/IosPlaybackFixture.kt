package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atDate
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

/** A chunk recorded "yesterday" and stored through the production [Ingestor]. */
data class SeededChunk(val chunkId: String, val startedAt: Long, val audioPath: String)

/**
 * The real iOS [IosAppServices] (AVAudioPlayer) over a temporary database and audio archive, as
 * [IosAppGraph] builds them in the app. The graph itself is not used: it registers a
 * BGTaskScheduler task and needs a UIApplication, which a test process does not have.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlaybackFixture {
    private val dir = TempDir("ios-playback")
    val store: LocalStore = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    val audioRoot: Path = dir.resolve("audio").also { SystemFileSystem.createDirectories(it) }
    val services = IosAppServices(
        store = store,
        audioRoot = audioRoot.toString(),
        storage = StorageManager(store, audioRoot, IosStorageEnvironment(dir.path.toString())),
        status = MutableStateFlow(ProcessingStatus(asrModel = ModelState.Ready)),
        onRequestTranscription = {},
        onPrepareModel = {},
        onGapAlertSettingsChanged = {},
    )

    fun close() {
        services.stopPlayback()
        store.close()
        dir.deleteRecursively()
    }

    /**
     * Encodes [durationMs] of AAC audio (16 kHz mono, like the watch), ingests it as a chunk that
     * started yesterday at 10:00 (device time zone) and stores [segments] (offset in the chunk,
     * text) as its transcript; with null segments the chunk stays queued for transcription.
     */
    fun seedYesterdayChunk(durationMs: Long, segments: List<Pair<Long, String>>?): SeededChunk = runBlocking {
        val zone = TimeZone.currentSystemDefault()
        val yesterday = Clock.System.todayIn(zone).minus(1, DateTimeUnit.DAY)
        val startedAt = LocalTime(10, 0).atDate(yesterday).toInstant(zone).toEpochMilliseconds()
        val id = "sim-" + Random.nextLong().toULong().toString(16)
        val file = dir.resolve("$id.m4a")
        writeM4a(file.toString(), durationMs)
        val size = assertNotNull(SystemFileSystem.metadataOrNull(file)).size
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "simulator-test",
            deviceId = "test",
            source = SourcePlatform.WATCHOS,
            startedAtEpochMs = startedAt,
            durationMs = durationMs,
            timeZone = zone.id,
            audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 32_000, size, sha256Of(file)),
            endReason = ChunkEndReason.ROTATION,
        )
        assertEquals(AckStatus.STORED, Ingestor(store, audioRoot).ingest(manifest, file).status)
        if (segments != null) store.transaction {
            saveTranscript(id, segments.map { (offset, text) -> NewSegment(startedAt + offset, startedAt + offset + 4_000, text, "ja") }, "test")
        }
        val audioPath = assertNotNull(store.read { chunk(id) }?.audioPath)
        SeededChunk(id, startedAt, audioPath)
    }

    /**
     * Waits until the player is playing at or after [requestedMs], then checks that the position
     * keeps advancing in real time. Prints a `PLAYBACK_CHECK` line (kept in the test report).
     */
    fun assertPlaysFrom(requestedMs: Long, check: String) {
        val first = waitFor(15_000, "playback at >= $requestedMs ms") {
            services.playingPositionMs()?.takeIf { it >= requestedMs }
        }
        pumpMainRunLoop(2_000)
        val later = services.playingPositionMs()
        val summary = "PLAYBACK_CHECK check=$check requested_ms=$requestedMs first_ms=$first after_2s_ms=$later"
        println(summary)
        check(first < requestedMs + 5_000) { "playback started at $first ms, requested $requestedMs ms" }
        check(later != null && later - first >= 1_000) { "playback did not advance: $first -> $later ms ($summary)" }
    }

    companion object {
        /**
         * Lets main-queue work run (view models use `Dispatchers.Main`, which the test thread
         * would otherwise block) for [millis] ms.
         */
        fun pumpMainRunLoop(millis: Long) {
            val deadline = TimeSource.Monotonic.markNow() + millis.milliseconds
            while (deadline.hasNotPassedNow()) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.02))
            }
        }

        fun <T : Any> waitFor(timeoutMs: Long, what: String, probe: () -> T?): T {
            val deadline = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds
            while (true) {
                probe()?.let { return it }
                check(deadline.hasNotPassedNow()) { "timed out waiting for $what" }
                pumpMainRunLoop(50)
            }
        }

        /** [durationMs] of a quiet two-tone signal as AAC-LC, 16 kHz, mono, in an MPEG-4 file. */
        fun writeM4a(path: String, durationMs: Long, sampleRate: Double = 16_000.0) {
            val settings = mapOf<Any?, Any?>(
                AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatMPEG4AAC),
                AVSampleRateKey to NSNumber(double = sampleRate),
                AVNumberOfChannelsKey to NSNumber(int = 1),
                AVEncoderBitRateKey to NSNumber(int = 32_000),
            )
            val file = AVAudioFile(
                forWriting = NSURL.fileURLWithPath(path),
                settings = settings,
                commonFormat = AVAudioPCMFormatFloat32,
                interleaved = false,
                error = null,
            )
            val capacity = 4_096
            val buffer = checkNotNull(AVAudioPCMBuffer(pCMFormat = file.processingFormat, frameCapacity = capacity.toUInt()))
            val total = (sampleRate * durationMs / 1000).toLong()
            var written = 0L
            while (written < total) {
                val frames = minOf(capacity.toLong(), total - written).toInt()
                val samples = checkNotNull(checkNotNull(buffer.floatChannelData)[0])
                for (i in 0 until frames) {
                    val t = (written + i) / sampleRate
                    val tone = if (t.toLong() % 2L == 0L) 440.0 else 660.0
                    samples[i] = (sin(2 * PI * tone * t) * 0.12).toFloat()
                }
                buffer.frameLength = frames.toUInt()
                check(file.writeFromBuffer(buffer, error = null)) { "cannot write $path" }
                written += frames
            }
            file.close()
        }
    }
}
