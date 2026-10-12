package app.watchaudio.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class SegmentRoutingTest {
    private val routing = SegmentRouting()
    private val chunk = Path("chunk.m4a")
    private val parts = listOf(AudioPart(chunk, 0, 20_000))

    private fun ja(startMs: Long, endMs: Long, text: String) = TimedText(startMs, endMs, text, "ja")

    private fun en(startMs: Long, endMs: Long, text: String, confidence: Float? = null) = TimedText(startMs, endMs, text, "en", confidence)

    private val morning = ja(500, 4_000, "おはようございます今日は午後から会議があります")
    private val station = ja(14_000, 17_000, "それでは駅前で待ち合わせをしましょう")

    @Test
    fun englishTheJapaneseModelWroteTooLittleForIsStoredAsEnglish() = runTest {
        // ja → en → ja: the Japanese model writes a lone 「はい」 for 7.5 s of English.
        val primary = listOf(
            ja(500, 4_000, "おはようございます今日は午後から会議があります"),
            ja(4_500, 12_000, "はい"),
            ja(12_500, 19_500, "それでは駅前で待ち合わせをしましょう"),
        )
        val heard = mutableListOf<List<AudioPart>>()
        val result = routing.transcribe(parts, { primary }) { input ->
            heard += input
            // The only region is 4.2 s..12.3 s (the segment and its 300 ms padding).
            listOf(en(400, 7_300, "Thank you for joining the meeting today.", 0.9f))
        }

        assertEquals(listOf("ja", "en", "ja"), result.map { it.lang })
        assertEquals("Thank you for joining the meeting today.", result[1].text)
        assertEquals(4_600L to 11_500L, result[1].startMs to result[1].endMs)
        assertEquals(listOf(AudioPart(chunk, 4_200, 12_300)), heard.single())
    }

    @Test
    fun theSecondPassReHearingJapaneseInThePaddingIsLeftOut() = runTest {
        val primary = listOf(morning, station)
        val result = routing.transcribe(parts, { primary }) {
            // The pause region 3.7 s..14.3 s: its first 300 ms are the end of 「おはよう…」, heard as English.
            listOf(
                en(0, 300, "Oh how are you", confidence = 0.6f),
                en(1_800, 9_000, "Let's review the schedule for next week.", confidence = 0.9f),
                en(10_400, 10_600, "Sore day", confidence = 0.6f),
            )
        }
        assertEquals(listOf("ja", "en", "ja"), result.map { it.lang })
        assertEquals("Let's review the schedule for next week.", result[1].text)
    }

    @Test
    fun englishTheJapaneseModelLeftUnwrittenIsHeardInThePause() = runTest {
        val primary = listOf(morning, station)
        val result = routing.transcribe(parts, { primary }) { input ->
            // The pause 4 s..14 s, padded: 3.7 s..14.3 s; then the end 17 s..20 s.
            assertEquals(
                listOf(
                    AudioPart(chunk, 3_700, 14_300),
                    AudioPart(chunk, SegmentRouting.SILENCE_FROM_MS, SegmentRouting.SILENCE_FROM_MS + 1_000),
                    AudioPart(chunk, 16_700, 20_000),
                ),
                input,
            )
            listOf(en(1_800, 9_000, "Let's review the schedule for next week."))
        }

        assertEquals(listOf("ja", "en", "ja"), result.map { it.lang })
        assertEquals(5_500L to 12_700L, result[1].startMs to result[1].endMs)
    }

    @Test
    fun englishWrittenInLatinLettersByTheJapaneseModelIsReplaced() = runTest {
        // Dense enough, but (almost) all Latin letters: the Japanese model's spelling of English.
        val primary = listOf(morning, ja(5_000, 8_000, "Thank you for joining"), station)
        val result = routing.transcribe(parts, { primary }) { listOf(en(300, 3_200, "Thank you for joining the meeting.")) }
        assertEquals(listOf("ja", "en", "ja"), result.map { it.lang })
        assertEquals("Thank you for joining the meeting.", result[1].text)
    }

    @Test
    fun japaneseOnlyAudioKeepsThePrimaryTextWhateverTheSecondPassHears() = runTest {
        val primary = listOf(morning, ja(5_000, 7_000, "はい"), station)
        val answers = listOf(
            emptyList(),
            // One or two words: a backchannel or noise heard as English.
            listOf(en(300, 1_500, "Hi.")),
            listOf(en(300, 1_500, "Okay so.")),
            // Words of low confidence: Japanese heard by the English model.
            listOf(en(300, 2_000, "Ohio go there my store", confidence = 0.2f)),
        )
        val strict = SegmentRouting(LanguageRouting(minConfidence = 0.5f))
        for (answer in answers) {
            assertEquals(primary, strict.transcribe(parts, { primary }) { answer }, "second pass: $answer")
        }
    }

    @Test
    fun denseJapaneseWithoutPausesNeverRunsTheSecondPass() = runTest {
        val primary = listOf(ja(0, 10_000, "おはようございます今日は午後から会議があるので資料を確認しておきます"), ja(10_500, 19_500, "それでは三時に駅前で待ち合わせをしましょう"))
        var calls = 0
        val result = routing.transcribe(parts, { primary }) { calls++; emptyList() }
        assertEquals(primary, result)
        assertEquals(0, calls)
    }

    @Test
    fun overlappingCandidatesFormOneRegion() {
        val primary = listOf(morning, ja(4_500, 6_000, "えー"), ja(6_200, 8_000, "うん"), station)
        val regions = routing.regions(primary, totalMs = 20_000)
        // 「えー」 and 「うん」 and the pause after them (8 s..14 s) are one region; the end is another.
        assertEquals(
            listOf(SegmentRouting.Region(4_200, 14_300, listOf(1, 2)), SegmentRouting.Region(16_700, 20_000, emptyList())),
            regions,
        )
    }

    @Test
    fun theSecondPassSegmentsGoToTheRegionTheyOverlapMost() {
        val regions = listOf(SegmentRouting.Region(1_000, 3_000, listOf(0)), SegmentRouting.Region(10_000, 14_000, emptyList()))
        // Second-pass input: region 1 at 0..2 s, silence 2..3 s, region 2 at 3..7 s.
        val heard = listOf(
            en(100, 2_400, "first region words"),
            en(2_100, 2_900, "in the silence"),
            en(2_800, 6_500, "second region words"),
        )
        val assigned = routing.assign(heard, regions)
        assertEquals(listOf(1_100L to 3_000L), assigned[0].map { it.startMs to it.endMs })
        assertEquals(listOf(10_000L to 13_500L), assigned[1].map { it.startMs to it.endMs })
    }

    @Test
    fun slicesFollowTheDeclaredLengthsOfTheParts() {
        val previous = Path("previous.m4a")
        val joined = listOf(AudioPart(previous, 55_000, 60_000), AudioPart(chunk, 0, 60_000))
        assertEquals(listOf(AudioPart(previous, 58_000, 60_000), AudioPart(chunk, 0, 1_500)), sliceParts(joined, 3_000, 6_500))
        assertEquals(listOf(AudioPart(chunk, 5_000, 6_000)), sliceParts(joined, 10_000, 11_000))
        assertTrue(sliceParts(joined, 70_000, 80_000).isEmpty())
    }

    @Test
    fun theEngineIdNamesBothModels() {
        assertEquals("apple-speechtranscriber-ja+apple-speechtranscriber-en", LanguageRouting.engineId("apple-speechtranscriber-ja", "apple-speechtranscriber-en"))
    }
}
