package app.watchaudio.app.licenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenSourceNoticesTest {
    private val all = OpenSourceNotices.all

    @Test
    fun everyEntryNamesItsAuthorLicenseAndSource() {
        assertTrue(all.isNotEmpty())
        for (notice in all) {
            assertTrue(notice.name.isNotBlank() && notice.author.isNotBlank(), "$notice")
            assertTrue(notice.url.startsWith("https://"), "${notice.name}: ${notice.url}")
            assertTrue(notice.apps.isNotEmpty(), notice.name)
            notice.includedIn?.let { assertTrue(it.isNotBlank(), notice.name) }
            notice.downloads.forEach { assertTrue(it.startsWith("https://"), "${notice.name}: $it") }
        }
        assertEquals(all.size, all.map { it.name }.distinct().size, "names are unique")
    }

    @Test
    fun everyLicenseHasATitleAndAUrl() {
        for (license in License.entries) {
            assertTrue(license.title.isNotBlank(), "$license")
            assertTrue(license.url.startsWith("https://"), "$license: ${license.url}")
            license.text?.let { assertTrue(it.length > 500, "$license text is the full text") }
        }
        // The full texts the issue asks for, each shown once.
        listOf(License.APACHE_2_0, License.MIT).forEach { assertTrue(it.text != null, "$it") }
        assertTrue(License.APACHE_2_0.text!!.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(License.MIT.text!!.startsWith("Permission is hereby granted, free of charge"))
    }

    /** MIT and BSD ask for the copyright notice to accompany the binaries. */
    @Test
    fun noticeLicensesCarryTheCopyrightLine() {
        val needsCopyright = setOf(License.MIT, License.BSD_2_CLAUSE, License.BSD_3_CLAUSE)
        for (notice in all.filter { it.license in needsCopyright }) {
            assertTrue(notice.copyright?.startsWith("Copyright") == true, "${notice.name}: ${notice.copyright}")
        }
    }

    /** The FunASR Model License (2.2) asks for the source, the author and the model name. */
    @Test
    fun theEnglishModelIsAttributedToFunAsrAndAlibaba() {
        val model = OpenSourceNotices.SENSE_VOICE
        assertEquals("SenseVoice Small", model.name)
        assertEquals("FunASR / Alibaba Group", model.author)
        assertEquals(License.FUNASR_MODEL_1_1, model.license)
        assertEquals("https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE", model.license.url)
        assertEquals(NoticeKind.MODEL, model.kind)
        assertTrue(model in all)
    }

    @Test
    fun eachAppListsWhatItShips() {
        val android = OpenSourceNotices.forApps(setOf(NoticeApp.ANDROID, NoticeApp.WEAR_OS)).map { it.name }
        val ios = OpenSourceNotices.forApps(setOf(NoticeApp.IOS))
        // Android: on-device ASR, its models and the watch app's libraries; no iOS renderer.
        assertTrue(listOf("sherpa-onnx", "ONNX Runtime", "SQLite", "AndroidX (Jetpack)", "Protocol Buffers").all { it in android }, "$android")
        assertTrue(OpenSourceNotices.SENSE_VOICE.name in android)
        assertTrue("Skia" !in android)
        // iOS transcribes with the OS (no downloaded models) and renders Compose with Skiko.
        assertTrue(ios.none { it.kind == NoticeKind.MODEL })
        assertTrue(listOf("Compose Multiplatform", "Skiko", "Skia", "SQLite").all { name -> ios.any { it.name == name } })
        assertTrue(ios.none { it.name == "sherpa-onnx" })
        assertEquals(all, OpenSourceNotices.forApps(NoticeApp.entries.toSet()))
    }

    @Test
    fun licensesAreListedOnceInTheirOrder() {
        val licenses = OpenSourceNotices.licensesOf(all)
        assertEquals(licenses.distinct(), licenses)
        assertEquals(licenses.sortedBy { it.ordinal }, licenses)
        assertEquals(all.map { it.license }.toSet(), licenses.toSet())
    }
}
