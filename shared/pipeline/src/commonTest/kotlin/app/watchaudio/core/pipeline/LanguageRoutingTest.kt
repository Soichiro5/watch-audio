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
    fun japaneseTextOfTheUsualDensityNeverRunsTheMultilingualModel() {
        var detections = 0
        // 13 characters in 4 s: 3.25 per second.
        val result = routing.route(4f, { japanese }) { detections++; Recognition("Merge this PR.", lang = "en") }
        assertEquals(japanese, result)
        assertEquals(0, detections)
    }

    @Test
    fun englishReplacesTheJapaneseModelsFewCharacters() {
        // The Japanese model writes a lone 「はい」 for a 6 s English sentence.
        val english = Recognition("Let's merge this pull request.", lang = "en")
        assertEquals(english, routing.route(6f, { Recognition("はい") }) { english })
        // Short spans are checked too: 「はい」 in 2 s is 1 character per second.
        assertEquals(english, routing.route(2f, { Recognition("はい") }) { english })
    }

    @Test
    fun aShortJapaneseReplyStaysJapaneseUnlessHeardAsEnglish() {
        val reply = Recognition("はい")
        for (lang in listOf("ja", "zh", "ko", "yue", null)) {
            assertEquals(reply, routing.route(2f, { reply }) { Recognition("はい。", lang = lang) }, "lang=$lang")
        }
    }

    @Test
    fun theDensityLimitDecidesWhenToAsk() {
        assertTrue(routing.suspectsOtherLanguage(Recognition(""), 0.5f))
        assertTrue(routing.suspectsOtherLanguage(Recognition("はい。"), 2f))
        assertFalse(routing.suspectsOtherLanguage(Recognition("はい。"), 1f))
        val always = LanguageRouting(maxPrimaryCharsPerSecond = Float.POSITIVE_INFINITY)
        assertTrue(always.suspectsOtherLanguage(japanese, 4f))
    }

    @Test
    fun katakanaGibberishIsCheckedEvenWhenDense() {
        // English the Japanese model spelled in katakana: 11 characters in 4.8 s.
        assertTrue(routing.suspectsOtherLanguage(Recognition("JVキャンストレーション"), 4.8f))
        assertTrue(routing.suspectsOtherLanguage(Recognition("ｷｬﾝｾﾙ"), 1f))
        // Ordinary Japanese with a loanword is not.
        assertFalse(routing.suspectsOtherLanguage(Recognition("ズームのリンクを送ります"), 2f))
        assertFalse(LanguageRouting(minForeignScriptShare = 1.1f).suspectsOtherLanguage(Recognition("キャンセル"), 1f))
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
