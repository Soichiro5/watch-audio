package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.pipeline.AudioRetentionPolicy.DAY_MS
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioRetentionPolicyTest {
    private val now = 1_791_622_800_000L // 2026-10-10T09:00:00Z

    private fun chunk(
        endedDaysAgo: Int,
        state: ChunkState = ChunkState.TRANSCRIBED,
        audio: Boolean = true,
        purgedAt: Long? = null,
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
        )
    }

    @Test
    fun unlimitedIsTheDefaultAndNeverDeletes() {
        assertEquals(AudioRetention.UNLIMITED, AudioRetention.DEFAULT)
        assertNull(AudioRetentionPolicy.cutoff(AudioRetention.UNLIMITED, now))
        assertFalse(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 10_000), AudioRetention.UNLIMITED, now))
    }

    @Test
    fun deletesTranscribedAudioOlderThanTheRetention() {
        assertTrue(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 8), AudioRetention.DAYS_7, now))
        assertTrue(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 7), AudioRetention.DAYS_7, now)) // exactly at the cutoff
        assertTrue(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 91), AudioRetention.DAYS_90, now))
    }

    @Test
    fun neverDeletesRecentAudio() {
        assertFalse(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 6), AudioRetention.DAYS_7, now))
        assertFalse(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 29), AudioRetention.DAYS_30, now))
        assertFalse(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 0), AudioRetention.DAYS_7, now))
    }

    @Test
    fun neverDeletesUntranscribedAudio() {
        for (state in ChunkState.entries - ChunkState.TRANSCRIBED) {
            assertFalse(
                AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 365, state = state), AudioRetention.DAYS_7, now),
                "$state must keep its audio",
            )
        }
    }

    @Test
    fun ignoresChunksWithoutAudioOrAlreadyPurged() {
        assertFalse(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 365, audio = false), AudioRetention.DAYS_7, now))
        assertFalse(AudioRetentionPolicy.shouldPurge(chunk(endedDaysAgo = 365, purgedAt = 1), AudioRetention.DAYS_7, now))
    }

    @Test
    fun parsesStoredSettingWithFallback() {
        assertEquals(AudioRetention.DAYS_30, AudioRetention.fromSetting("DAYS_30"))
        assertEquals(AudioRetention.UNLIMITED, AudioRetention.fromSetting(null))
        assertEquals(AudioRetention.UNLIMITED, AudioRetention.fromSetting("garbage"))
    }

    @Test
    fun shorterMeansAudioIsDeletedSooner() {
        assertTrue(AudioRetention.DAYS_7.isShorterThan(AudioRetention.UNLIMITED))
        assertTrue(AudioRetention.DAYS_7.isShorterThan(AudioRetention.DAYS_30))
        assertFalse(AudioRetention.DAYS_90.isShorterThan(AudioRetention.DAYS_30))
        assertFalse(AudioRetention.UNLIMITED.isShorterThan(AudioRetention.DAYS_7))
        assertFalse(AudioRetention.DAYS_30.isShorterThan(AudioRetention.DAYS_30))
    }
}
