package app.watchaudio.android

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

/** A chunk recorded "yesterday" and stored through the production [AppGraph.ingestor]. */
data class SeededChunk(val chunkId: String, val startedAt: Long, val audioPath: String)

object PlaybackFixtures {
    const val TAG = "PlaybackCheck"

    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WatchAudioApp).graph

    /**
     * Encodes [durationMs] of AAC audio, ingests it as a chunk that started yesterday at 10:00
     * (device time zone) and stores [segments] (offset in the chunk, text) as its transcript.
     */
    fun seedYesterdayChunk(durationMs: Long, segments: List<Pair<Long, String>>): SeededChunk = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "it-" + UUID.randomUUID()
        val startedAt = ZonedDateTime.now(ZoneId.systemDefault()).minusDays(1)
            .truncatedTo(ChronoUnit.DAYS).withHour(10).toInstant().toEpochMilli()
        val file = File(context.cacheDir, "$id.m4a")
        TestAudio.writeM4a(file, durationMs)
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "instrumented-test",
            deviceId = "test",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = startedAt,
            durationMs = durationMs,
            timeZone = ZoneId.systemDefault().id,
            audio = AudioFile(file.name, AudioCodec.AAC, 16_000, 1, 32_000, file.length(), sha256Of(Path(file.path))),
            endReason = ChunkEndReason.ROTATION,
        )
        assertEquals(AckStatus.STORED, graph.ingestor.ingest(manifest, Path(file.path)).status)
        graph.store.transaction {
            saveTranscript(id, segments.map { (offset, text) -> NewSegment(startedAt + offset, startedAt + offset + 4_000, text, "ja") }, "test")
        }
        val audioPath = graph.store.read { chunk(id) }!!.audioPath!!
        SeededChunk(id, startedAt, audioPath)
    }

    /** [AndroidAppServices.playingPositionMs], read on the main thread. */
    fun playingPositionMs(): Int? {
        var position: Int? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { position = graph.services.playingPositionMs() }
        return position
    }

    fun stopPlayback() = InstrumentationRegistry.getInstrumentation().runOnMainSync { graph.services.stopPlayback() }

    /**
     * Waits until the player is playing at or after [requestedMs], then checks that the position
     * keeps advancing in real time. Logs a `PLAYBACK_CHECK` line (collected by emulator.yml).
     */
    fun assertPlaysFrom(requestedMs: Long, check: String) {
        val first = waitFor(15_000, "playback at >= $requestedMs ms") { playingPositionMs()?.takeIf { it >= requestedMs } }
        SystemClock.sleep(2_000)
        val later = playingPositionMs()
        val summary = "PLAYBACK_CHECK check=$check requested_ms=$requestedMs first_ms=$first after_2s_ms=$later"
        Log.i(TAG, summary)
        println(summary)
        check(first < requestedMs + 5_000) { "playback started at $first ms, requested $requestedMs ms" }
        check(later != null && later - first >= 1_000) { "playback did not advance: $first -> $later ms" }
    }

    fun <T : Any> waitFor(timeoutMs: Long, what: String, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what" }
            SystemClock.sleep(100)
        }
    }
}
