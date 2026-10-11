package app.watchaudio.app.logic

import app.watchaudio.core.pipeline.AudioRetention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageLogicTest {
    @Test
    fun bytesSwitchToGigabytes() {
        assertEquals("0.0 MB", formatBytes(0))
        assertEquals("1023.0 MB", formatBytes(1023L shl 20))
        assertEquals("1.0 GB", formatBytes(1L shl 30))
        assertEquals("12.5 GB", formatBytes((25L shl 30) / 2))
    }

    @Test
    fun onlyShorterRetentionNeedsConfirmation() {
        assertTrue(retentionChangeNeedsConfirmation(AudioRetention.UNLIMITED, AudioRetention.DAYS_90))
        assertTrue(retentionChangeNeedsConfirmation(AudioRetention.DAYS_30, AudioRetention.DAYS_7))
        assertFalse(retentionChangeNeedsConfirmation(AudioRetention.DAYS_7, AudioRetention.DAYS_30))
        assertFalse(retentionChangeNeedsConfirmation(AudioRetention.DAYS_7, AudioRetention.UNLIMITED))
        assertFalse(retentionChangeNeedsConfirmation(AudioRetention.DAYS_30, AudioRetention.DAYS_30))
    }

    @Test
    fun deleteAllNeedsTheTypedWord() {
        assertFalse(isDeleteAllConfirmed(null))
        assertFalse(isDeleteAllConfirmed(""))
        assertFalse(isDeleteAllConfirmed("さくじょ"))
        assertFalse(isDeleteAllConfirmed("削"))
        assertTrue(isDeleteAllConfirmed("削除"))
        assertTrue(isDeleteAllConfirmed(" 削除 "))
    }
}
