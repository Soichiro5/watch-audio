package app.watchaudio.harness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The test-set scoring (CER, missed utterances) on hand-made data; no models needed. */
class ScoringTest {
    @Test
    fun normalizationDropsPunctuationAndUnifiesNumbers() {
        assertEquals("お会計は3480円になります", CerText.normalize("お会計は、3480円になります。"))
        assertEquals("お会計は3480円になります", CerText.normalize("お会計は三千四百八十円になります"))
        assertEquals("12800円", CerText.normalize("1万2800円"))
        assertEquals("12800円", CerText.normalize("一万二千八百円"))
        assertEquals("120000円", CerText.normalize("12万円"))
        assertEquals("10時", CerText.normalize("十時"))
        assertEquals("090の1234", CerText.normalize("〇九〇の一二三四"))
        assertEquals("ok", CerText.normalize("ＯＫ！"))
    }

    @Test
    fun alignmentAttributesErrorsToTheSpeaker() {
        val ref = "あいう".map { it to "wearer" } + "えお".map { it to "partner" }
        assertEquals(emptyMap(), alignErrors(ref, "あいうえお"))
        // substitution in the wearer's part, deletion in the partner's part
        assertEquals(mapOf("wearer" to 1, "partner" to 1), alignErrors(ref, "あかうえ"))
        // insertion after the partner's last character
        assertEquals(mapOf("partner" to 2), alignErrors(ref, "あいうえおかき"))
        assertEquals(mapOf("wearer" to 3, "partner" to 2), alignErrors(ref, ""))
    }

    @Test
    fun scoresGroupsMissesAndTextOutsideConversations() {
        val truth = listOf(
            TruthSegment(1_000, 2_000, "c1:A", "wearer", "c1", "おはよう。"),
            TruthSegment(2_300, 3_000, "c1:B", "partner", "c1", "うん。"),
            TruthSegment(10_000, 12_000, "c2:A", "wearer", "c2", "3時に行きます。"),
            TruthSegment(12_500, 13_000, "c2:B", "phone", "c2", "はい。"),
        )
        val hypotheses = listOf(
            // VAD merged the first two turns into one segment
            Hypothesis(800, 3_200, "おはよううん"),
            Hypothesis(9_800, 12_200, "三時に行きます"),
            // nothing for "はい" (missed); noise transcribed between the conversations
            Hypothesis(6_000, 6_500, "あ"),
        )
        val score = scoreRecording(truth, hypotheses)
        assertEquals(4, score.utterances)
        assertEquals(mapOf("phone" to 1), score.missedByRole)
        assertEquals(1, score.outsideChars)
        // reference: おはよう うん 3時に行きます はい = 4 + 2 + 7 + 2 = 15 chars; errors: はい (2) + outside (1)
        assertEquals(15, score.refChars)
        assertEquals(3, score.errors)
        assertEquals(0.0, score.cerOf(setOf("wearer")))
        assertEquals(1.0, score.cerOf(setOf("phone")))
        assertEquals(listOf(0, 2), score.groups.map { it.errors })
    }

    @Test
    fun englishGroupsAreScoredByWordsAndLanguagesAreChecked() {
        assertEquals(listOf("its", "a", "well", "known", "fact"), WerText.words("It's a well-known fact!"))
        assertEquals(listOf("well", "known", "20"), WerText.words("Well-known: 20."))
        val truth = listOf(
            TruthSegment(1_000, 3_000, "en", "wearer", "m0", "The cat sat down.", lang = "en"),
            TruthSegment(4_000, 5_000, "m1:A", "partner", "m1", "はい。"),
            TruthSegment(6_000, 8_000, "en", "partner", "m2", "Good morning.", lang = "en"),
        )
        val hypotheses = listOf(
            Hypothesis(900, 3_100, "The cat sat.", "en"),
            Hypothesis(3_900, 5_100, "はい", "ja"),
            // English recognized by the Japanese model
            Hypothesis(5_900, 8_100, "グッドモーニング", "ja"),
        )
        val score = scoreRecording(truth, hypotheses)
        // words: the cat sat down (4) + good morning (2); errors: "down" deleted, "good morning" -> one wrong word
        assertEquals(mapOf("wearer" to 4, "partner" to 2), score.refWordsByRole)
        assertEquals(3.0 / 6, score.wer)
        assertEquals(0.0, score.cer)
        assertEquals(2, score.refChars)
        assertEquals(mapOf("en" to 2, "ja" to 1), score.languageChecked)
        assertEquals(mapOf("en" to 1), score.languageErrors)
        assertEquals(null, scoreRecording(truth.filter { it.lang == "en" }, hypotheses).cerOrNull)
    }

    @Test
    fun committedFixtureManifestParsesAndAPerfectTranscriptScoresZero() {
        val manifest = TestSetManifest.load(File(System.getProperty("watchaudio.fixtures"), "manifest.json"))
        assertTrue(manifest.recordings.size >= 2)
        manifest.recordings.forEach { r ->
            assertTrue(r.segments.isNotEmpty())
            assertTrue(r.segments.all { it.endMs > it.startMs && it.endMs <= r.durationMs })
            // A perfect transcript scores 0 % CER and no misses.
            val perfect = scoreRecording(r.segments, r.segments.map { Hypothesis(it.startMs, it.endMs, it.text) })
            assertEquals(0, perfect.errors)
            assertEquals(0, perfect.missed)
        }
    }
}
