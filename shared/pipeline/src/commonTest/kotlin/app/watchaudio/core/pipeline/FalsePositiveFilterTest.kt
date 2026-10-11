package app.watchaudio.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FalsePositiveFilterTest {
    private val filter = FalsePositiveFilter.DEFAULT

    private fun text(text: String, probability: Float? = 0.6f, level: Float? = 20f) =
        TimedText(0, 1_000, text, "ja", speechProbability = probability, levelAboveNoiseDb = level)

    @Test
    fun defaultsAreTheRulesChosenOnTheTestAudioSet() {
        assertEquals(FalsePositiveFilter(minLevelAboveNoiseDb = 7f, shortTextMaxChars = 1, shortTextMinSpeechProbability = 0.2f), filter)
    }

    @Test
    fun speechAtTheWatchIsKept() {
        assertTrue(filter.keep(text("会議を始めます")))
        // Short answers that sounded like speech, even quietly.
        assertTrue(filter.keep(text("うん", probability = 0.25f, level = 12f)))
        assertTrue(filter.keep(text("え", probability = 0.2f, level = 7f)))
    }

    @Test
    fun textsBarelyAboveTheNoiseFloorAreDropped() {
        // Distant chatter or air conditioning heard as words, whatever their length (#105).
        assertFalse(filter.keep(text("うん", probability = 0.9f, level = 4.5f)))
        assertFalse(filter.keep(text("ありがとうございます", probability = 0.3f, level = 5.5f)))
        assertFalse(filter.keep(text("何", level = 1f)))
        assertTrue(filter.isNearNoiseFloor(text("何", level = 6.9f)))
        assertFalse(filter.isNearNoiseFloor(text("何", level = 7f)))
    }

    @Test
    fun aLoneSoundThatHardlySoundedLikeSpeechIsDropped() {
        // A rustling sleeve, loud but not speech-like.
        assertFalse(filter.keep(text("え", probability = 0.08f, level = 25f)))
        assertFalse(filter.keep(text("《あ》", probability = 0.19f)))
        assertTrue(filter.isLoneSound(text("あ。", probability = 0.1f)))
        // Two characters or more are left to the noise floor rule.
        assertTrue(filter.keep(text("うん", probability = 0.08f, level = 25f)))
        assertFalse(filter.isLoneSound(text("え", probability = 0.2f)))
    }

    @Test
    fun aMissingSignalIsNoEvidenceOfNoise() {
        // iOS (SpeechAnalyzer) reports neither signal.
        assertTrue(filter.keep(text("え", probability = null, level = null)))
        assertTrue(filter.keep(text("え", probability = null, level = 20f)))
        assertTrue(filter.keep(text("え", probability = 0.9f, level = null)))
        assertFalse(filter.keep(text("え", probability = 0.05f, level = null)))
        assertFalse(filter.keep(text("はい", probability = null, level = 3f)))
    }

    @Test
    fun textLengthCountsLettersAndDigitsOnly() {
        assertEquals(0, FalsePositiveFilter.textLength("、。 ！？"))
        assertEquals(1, FalsePositiveFilter.textLength("《え》"))
        assertEquals(2, FalsePositiveFilter.textLength("うん。"))
        assertEquals(5, FalsePositiveFilter.textLength("Zoom 3"))
    }

    @Test
    fun theRulesAreConfigurable() {
        val strict = FalsePositiveFilter(minLevelAboveNoiseDb = 10f, shortTextMaxChars = 2, shortTextMinSpeechProbability = 0.3f)
        assertFalse(strict.keep(text("うん", probability = 0.25f, level = 12f)))
        assertFalse(strict.keep(text("会議", probability = 0.9f, level = 9f)))
        assertTrue(strict.keep(text("会議です", probability = 0.25f, level = 12f)))
    }
}
