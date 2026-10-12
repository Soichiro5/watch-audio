package app.watchaudio.android.asr

import app.watchaudio.android.AndroidAppServices
import app.watchaudio.app.licenses.License
import app.watchaudio.app.licenses.NoticeKind
import app.watchaudio.app.licenses.OpenSourceNotices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The licenses screen (#167) names every model the app downloads, the English one included. */
class ModelNoticesTest {
    private val models = OpenSourceNotices.forApps(AndroidAppServices.NOTICE_APPS).filter { it.kind == NoticeKind.MODEL }

    @Test
    fun everyDownloadedFileHasALicenseNotice() {
        for (source in ModelManifest.files + ModelManifest.englishFiles + ModelManifest.embeddingFiles) {
            assertTrue(
                models.any { notice -> notice.downloads.any { source.url.startsWith(it) } },
                "no notice on the licenses screen for ${source.url}",
            )
        }
    }

    @Test
    fun everyModelNoticeIsDownloaded() {
        val urls = (ModelManifest.files + ModelManifest.englishFiles + ModelManifest.embeddingFiles).map { it.url }
        for (notice in models) {
            assertTrue(notice.downloads.isNotEmpty(), notice.name)
            notice.downloads.forEach { prefix -> assertTrue(urls.any { it.startsWith(prefix) }, "${notice.name}: $prefix is not in ModelManifest") }
        }
    }

    @Test
    fun theEmbeddingModelIsRuriV3UnderApache() {
        val notice = OpenSourceNotices.RURI_V3
        assertEquals(License.APACHE_2_0, notice.license)
        assertTrue(ModelManifest.embeddingFiles.all { file -> notice.downloads.any { file.url.startsWith(it) } })
    }

    @Test
    fun theEnglishModelIsSenseVoiceUnderTheFunAsrLicense() {
        val notice = OpenSourceNotices.SENSE_VOICE
        assertEquals(License.FUNASR_MODEL_1_1, notice.license)
        assertTrue(ModelManifest.englishFiles.all { file -> notice.downloads.any { file.url.startsWith(it) } })
    }
}
