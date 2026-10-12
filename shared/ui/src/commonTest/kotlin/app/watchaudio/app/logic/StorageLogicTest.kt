package app.watchaudio.app.logic

import app.watchaudio.core.pipeline.AudioRetention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

class StorageLogicTest {
    @Test
    fun bytesSwitchToGigabytes() {
        assertEquals("0.0 MB", formatBytes(0))
        assertEquals("1023.0 MB", formatBytes(1023L shl 20))
        assertEquals("1.0 GB", formatBytes(1L shl 30))
        assertEquals("12.5 GB", formatBytes((25L shl 30) / 2))
    }

    @Test
    fun smallBackupsReadInKilobytes() {
        assertEquals("1 KB", formatBackupSize(0))
        assertEquals("1 KB", formatBackupSize(1))
        assertEquals("39 KB", formatBackupSize(39_055))
        assertEquals("0.1 MB", formatBackupSize(100L shl 10))
        assertEquals("1.0 GB", formatBackupSize(1L shl 30))
    }

    @Test
    fun backupDatesAreLocalDays() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val oct12 = 1_791_730_800_000L // 2026-10-12T00:00+09:00
        assertEquals("2026年10月12日(月)", formatBackupDates(oct12, oct12 + 3_600_000, tokyo))
        assertEquals("2026年10月11日(日)〜2026年10月12日(月)", formatBackupDates(oct12 - 1, oct12, tokyo))
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
