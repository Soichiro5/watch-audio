package app.watchaudio.app

import app.watchaudio.app.alert.GapAlertText
import app.watchaudio.app.logic.TOKYO
import app.watchaudio.app.logic.at
import app.watchaudio.core.pipeline.StatusNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #135: the status card's notes, failures and the notifications are worded in Japanese, in one place. */
class StatusNoticeTextTest {
    @Test
    fun everyNoticeIsJapanese() {
        ALL_NOTICES.forEach { assertJapanese(Strings.statusNotice(it), it.toString()) }
    }

    @Test
    fun everyVariantIsListed() {
        assertEquals(VARIANTS, ALL_NOTICES.map(::variant).toSet())
    }

    @Test
    fun failedChunksAreCounted() {
        assertTrue(Strings.statusNotice(StatusNotice.TranscriptionFailed(3)).startsWith("3件の"))
    }

    @Test
    fun failuresOnTheStatusTabAreJapanese() {
        listOf(
            Strings.modelFailed(ModelSetup.DOWNLOAD),
            Strings.modelFailed(ModelSetup.OS_MANAGED),
            Strings.STORAGE_ACTION_FAILED,
            Strings.DIAGNOSTICS_EXPORT_FAILED,
        ).forEach { assertJapanese(it, it) }
    }

    @Test
    fun notificationsAreJapanese() {
        listOf(
            Strings.MODEL_DOWNLOAD_CHANNEL,
            Strings.MODEL_DOWNLOAD_NOTIFICATION_TITLE,
            GapAlertText.channelName,
            GapAlertText.title,
            GapAlertText.message(at(14, 56), TOKYO),
        ).forEach { assertJapanese(it, it) }
    }

    @Test
    fun checkerRejectsEnglish() {
        assertFalse(isJapanese("The speech model will download on Wi-Fi"))
        assertFalse(isJapanese("保存できませんでした。Retrying later"))
        assertTrue(isJapanese("Wi-Fi に接続されたときにダウンロードします"))
    }

    private fun assertJapanese(text: String, label: String) {
        assertTrue(text.isNotBlank(), "$label: empty")
        assertTrue(isJapanese(text), "$label: not Japanese: $text")
    }

    /** Every sentence holds kana or kanji (names such as Watch or Wi-Fi may appear inside one). */
    private fun isJapanese(text: String): Boolean =
        text.split('。').filter { it.isNotBlank() }.let { sentences ->
            sentences.isNotEmpty() && sentences.all { sentence -> sentence.any(::isKanaOrKanji) }
        }

    private fun isKanaOrKanji(c: Char): Boolean =
        c in '぀'..'ヿ' || c in '一'..'鿿'

    private companion object {
        /** One of each [StatusNotice]; [variant]'s `when` stops compiling when a variant is added. */
        val ALL_NOTICES = listOf(
            StatusNotice.ModelWaitingForWifi,
            StatusNotice.IngestFailed,
            StatusNotice.GapAlertScheduleFailed,
            StatusNotice.TranscriptionFailed(2),
            StatusNotice.RetentionFailed,
        )

        val VARIANTS = setOf("wifi", "ingest", "alert", "transcription", "retention")

        fun variant(notice: StatusNotice): String = when (notice) {
            StatusNotice.ModelWaitingForWifi -> "wifi"
            StatusNotice.IngestFailed -> "ingest"
            StatusNotice.GapAlertScheduleFailed -> "alert"
            is StatusNotice.TranscriptionFailed -> "transcription"
            StatusNotice.RetentionFailed -> "retention"
        }
    }
}
