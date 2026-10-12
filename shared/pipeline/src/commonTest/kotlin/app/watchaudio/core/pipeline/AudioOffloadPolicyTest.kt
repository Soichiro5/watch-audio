package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.pipeline.AudioRetentionPolicy.DAY_MS
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #156: only transcribed audio that is in a saved backup may leave the phone. */
class AudioOffloadPolicyTest {
    private val now = 1_791_622_800_000L // 2026-10-10T09:00:00Z

    private fun chunk(
        endedDaysAgo: Int,
        state: ChunkState = ChunkState.TRANSCRIBED,
        audio: Boolean = true,
        purgedAt: Long? = null,
        backedUpAt: Long? = now - DAY_MS,
    ): ChunkRecord {
        val end = now - endedDaysAgo * DAY_MS
        return ChunkRecord(
            id = "c$endedDaysAgo",
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WEAROS,
            startedAt = end - 300_000,
            endedAt = end,
            timeZone = "Asia/Tokyo",
            audioPath = if (audio) "c.m4a" else null,
            endReason = ChunkEndReason.ROTATION,
            state = state,
            attempts = 0,
            lastError = null,
            sizeBytes = if (audio) 1_000 else null,
            audioPurgedAt = purgedAt,
            audioBackedUpAt = backedUpAt,
        )
    }

    private val all = AudioOffloadPolicy.cutoff(OffloadAge.ALL, now)

    @Test
    fun audioThatIsNotInABackupIsNeverOffloaded() {
        assertFalse(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 365, backedUpAt = null), all))
        assertTrue(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 365), all))
    }

    @Test
    fun untranscribedAudioIsNeverOffloaded() {
        listOf(ChunkState.RECEIVED, ChunkState.TRANSCRIBING, ChunkState.FAILED, ChunkState.NO_AUDIO).forEach { state ->
            assertFalse(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 365, state = state), all), "$state")
        }
    }

    @Test
    fun audioAlreadyGoneOrSilentIsSkipped() {
        assertFalse(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 10, purgedAt = now), all))
        assertFalse(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 10, audio = false), all))
    }

    @Test
    fun theAgeLimitKeepsRecentAudio() {
        assertEquals(Long.MAX_VALUE, all)
        // Even a chunk whose watch clock ran ahead is covered by "all".
        assertTrue(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = -1), all))
        val week = AudioOffloadPolicy.cutoff(OffloadAge.DAYS_7, now)
        assertEquals(now - 7 * DAY_MS, week)
        assertTrue(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 7), week))
        assertFalse(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 6), week))
        assertFalse(AudioOffloadPolicy.canOffload(chunk(endedDaysAgo = 29), AudioOffloadPolicy.cutoff(OffloadAge.DAYS_30, now)))
    }
}
