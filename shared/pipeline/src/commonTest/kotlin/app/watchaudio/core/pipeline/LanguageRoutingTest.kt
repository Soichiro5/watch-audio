package app.watchaudio.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageRoutingTest {
    private val routing = LanguageRouting.DEFAULT
    private val japanese = Recognition("このPRをマージしてください")

    @Test
    fun englishFromTheMultilingualModelIsKeptWithoutRunningTheJapaneseModel() {
        var primaryRuns = 0
        val english = Recognition("Let's merge this pull request.", lang = "en")
        val result = routing.route(english) { primaryRuns++; japanese }
        assertEquals(english, result)
        assertEquals(0, primaryRuns)
    }

    @Test
    fun everyOtherLanguageGoesToTheJapaneseModel() {
        for (lang in listOf("ja", "zh", "ko", "yue", null)) {
            assertEquals(japanese, routing.route(Recognition("こんにちは", lang = lang)) { japanese }, "lang=$lang")
        }
    }

    @Test
    fun englishTagsOnTextThatIsNotMostlyLatinAreNotTrusted() {
        assertFalse(routing.keeps(Recognition("はい、そうです", lang = "en")))
        assertFalse(routing.keeps(Recognition("", lang = "en")))
        assertFalse(routing.keeps(Recognition("123。", lang = "en")))
        assertTrue(routing.keeps(Recognition("OK, 3 o'clock.", lang = "en")))
        // Half Latin letters is enough at the default share.
        assertTrue(routing.keeps(Recognition("Zoomで", lang = "en")))
        assertFalse(LanguageRouting(minLatinShare = 0.9f).keeps(Recognition("Zoomで", lang = "en")))
    }

    @Test
    fun routedLanguagesCanBeWidened() {
        val chinese = Recognition("你好", lang = "zh")
        assertFalse(routing.keeps(chinese))
        assertFalse(LanguageRouting(languages = setOf("en", "zh")).keeps(chinese)) // not Latin
        assertTrue(LanguageRouting(languages = setOf("en", "zh"), minLatinShare = 0f).keeps(chinese))
    }

    @Test
    fun engineIdNamesBothModels() {
        assertEquals(
            "sherpa-zipformer-ja-reazonspeech-int8+sherpa-sensevoice-small-int8",
            LanguageRouting.engineId(AsrModelSpec.JA_REAZONSPEECH, AsrModelSpec.SENSE_VOICE),
        )
    }

    @Test
    fun senseVoiceTagsBecomeLanguageCodes() {
        assertEquals("en", senseVoiceLanguage("<|en|>"))
        assertEquals("ja", senseVoiceLanguage("<|ja|>"))
        assertEquals("yue", senseVoiceLanguage(" <|yue|> "))
        assertNull(senseVoiceLanguage("<|nospeech|>"))
        assertNull(senseVoiceLanguage(""))
        assertNull(senseVoiceLanguage(null))
    }
}
