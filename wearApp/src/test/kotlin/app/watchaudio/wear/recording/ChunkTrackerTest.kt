package app.watchaudio.wear.recording

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ChunkTrackerTest {
    private val first = ChunkJournal(
        chunkId = "c1",
        sessionId = "s1",
        deviceId = "d1",
        startedAtEpochMs = 1_000_000,
        timeZone = "Asia/Tokyo",
        retryProbe = true,
    )

    @Test
    fun `marks are offsets from the start of the current chunk`() {
        val tracker = ChunkTracker(first, startElapsedMs = 50_000)
        assertEquals(12_000, tracker.mark(62_000))
        tracker.rotate("c2", atElapsedMs = 350_000, timeZone = "Asia/Tokyo")
        assertEquals(5_000, tracker.mark(355_000))
        assertEquals(listOf(5_000L), tracker.snapshot().markOffsetsMs)
    }

    @Test
    fun `rotation tiles the timeline and starts fresh statistics in the same session`() {
        val tracker = ChunkTracker(first, startElapsedMs = 50_000)
        tracker.mark(60_000)
        tracker.addAmplitude(9_000)

        val closed = tracker.rotate("c2", atElapsedMs = 350_000, timeZone = "Europe/Paris")

        assertEquals(300_000, closed.durationMs)
        assertEquals("c1", closed.journal.chunkId)
        assertEquals(listOf(10_000L), closed.journal.markOffsetsMs)
        assertEquals(1, closed.journal.amplitudeSamples)
        assertEquals(9_000, closed.journal.amplitudePeak)
        assertEquals(1_300_000, closed.journal.lastAliveAtEpochMs)

        val next = tracker.snapshot()
        assertEquals("c2", tracker.chunkId)
        assertEquals("s1", next.sessionId)
        assertEquals("d1", next.deviceId)
        assertEquals(1_300_000, next.startedAtEpochMs)
        assertEquals("Europe/Paris", next.timeZone)
        assertEquals(emptyList(), next.markOffsetsMs)
        assertEquals(0, next.amplitudeSamples)
        assertFalse(next.retryProbe, "only the first chunk of a retry is a probe")
    }

    @Test
    fun `end closes the chunk with its duration and silence statistics`() {
        val tracker = ChunkTracker(first, startElapsedMs = 0)
        repeat(20) { tracker.addAmplitude(10) }
        val heartbeat = tracker.heartbeat(nowEpochMs = 1_015_000)
        assertEquals(1_015_000, heartbeat.lastAliveAtEpochMs)

        val (journal, duration) = tracker.end(20_000)

        assertEquals(20_000, duration)
        assertEquals(1_020_000, journal.lastAliveAtEpochMs)
        assertTrue(journal.retryProbe)
        assertTrue(journal.silenceDetector().isSilent(duration))
    }
}
