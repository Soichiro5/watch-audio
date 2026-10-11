package app.watchaudio.core.pipeline

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechSegmentationTest {
    /** Each sample holds its own index, so re-cut ranges are easy to check. */
    private val audio = FloatArray(10_000) { it.toFloat() }

    @Test
    fun padsBothSidesWithRealAudio() {
        val spans = padSpeechSpans(listOf(1_000 to 500), audio, padSamples = 100)
        assertEquals(1, spans.size)
        assertEquals(900, spans[0].startSample)
        assertEquals(700, spans[0].samples.size)
        assertContentEquals(audio.copyOfRange(900, 1_600), spans[0].samples)
    }

    @Test
    fun clampsToAudioBounds() {
        val spans = padSpeechSpans(listOf(50 to 100, 9_950 to 50), audio, padSamples = 100)
        assertEquals(listOf(0 to 250, 9_850 to 150), spans.map { it.startSample to it.samples.size })
    }

    @Test
    fun mergesSegmentsThatOverlapAfterPadding() {
        // 1000..1500 and 1600..2000 touch once padded by 100; 5000.. stays separate.
        val spans = padSpeechSpans(listOf(1_600 to 400, 1_000 to 500, 5_000 to 100), audio, padSamples = 100)
        assertEquals(listOf(900 to 1_200, 4_900 to 300), spans.map { it.startSample to it.samples.size })
        assertContentEquals(audio.copyOfRange(900, 2_100), spans[0].samples)
    }

    @Test
    fun noSegmentsMeansNoSpans() {
        assertTrue(padSpeechSpans(emptyList(), audio).isEmpty())
    }

    @Test
    fun defaultPadAndTimesFollowSettings() {
        val start = 2 * AsrSettings.SAMPLE_RATE // 2 s
        val spans = padSpeechSpans(listOf(start to AsrSettings.SAMPLE_RATE), FloatArray(5 * AsrSettings.SAMPLE_RATE))
        val padMs = (VadSettings.DEFAULT.segmentPadSeconds * 1000).toLong()
        assertEquals(2_000 - padMs, spans.single().startMs)
        assertEquals(3_000 + padMs, spans.single().endMs)
    }

    private val rate = AsrSettings.SAMPLE_RATE
    private val maxSamples = (VadSettings.DEFAULT.maxSegmentSeconds * rate).toInt()

    /** Noise standing in for speech, with silence at [silences] (start to length in samples). */
    private fun speech(seconds: Int, vararg silences: Pair<Int, Int>): FloatArray {
        val random = Random(seconds)
        val audio = FloatArray(seconds * rate) { random.nextFloat() * 0.2f - 0.1f }
        for ((start, length) in silences) audio.fill(0f, start, start + length)
        return audio
    }

    private fun assertSpansWithin(spans: List<SpeechSpan>, limit: Int, size: Int) {
        assertTrue(spans.all { it.samples.size in 1..limit }, "span longer than $limit: ${spans.map { it.samples.size }}")
        assertTrue(spans.zipWithNext().all { (a, b) -> a.startSample + a.samples.size <= b.startSample }, "spans overlap")
        assertTrue(spans.all { it.startSample >= 0 && it.startSample + it.samples.size <= size })
    }

    @Test
    fun vadSplitSegmentsAreNotMergedAgainPastTheCap() {
        // The VAD cuts 60 s of continuous speech at its max speech length: the pieces touch, so
        // padding makes them overlap. They must stay apart and within the cap (#68).
        val audio = speech(60)
        val vad = listOf(0 to 20 * rate, 20 * rate to 20 * rate, 40 * rate to 20 * rate)
        val spans = padSpeechSpans(vad, audio)
        assertSpansWithin(spans, maxSamples, audio.size)
        assertEquals(0, spans.first().startSample)
        assertEquals(audio.size, spans.last().startSample + spans.last().samples.size)
        // No audio is lost or recognized twice.
        assertEquals(audio.size, spans.sumOf { it.samples.size })
    }

    @Test
    fun overlappingPaddingIsCutAtTheQuietestPoint() {
        // Two segments 0.2 s apart that do not fit the cap together: the cut lands in the gap.
        val gapStart = 15 * rate
        val audio = speech(30, gapStart to rate / 5)
        val vad = listOf(0 to gapStart, gapStart + rate / 5 to 15 * rate - rate / 5)
        val spans = padSpeechSpans(vad, audio, minPauseSamples = 0)
        assertEquals(2, spans.size)
        val cut = spans[1].startSample
        assertEquals(cut, spans[0].startSample + spans[0].samples.size)
        assertTrue(cut in gapStart..gapStart + rate / 5, "cut at $cut")
    }

    @Test
    fun longSegmentIsSplitAtItsQuietestPointsWithinTheCap() {
        // 45 s without a pause long enough for the pause rule, but with short dips at 12 s and 30 s.
        val dips = arrayOf(12 * rate to rate / 10, 30 * rate to rate / 10)
        val audio = speech(45, *dips)
        val spans = padSpeechSpans(listOf(0 to audio.size), audio, minPauseSamples = 0)
        assertSpansWithin(spans, maxSamples, audio.size)
        assertEquals(3, spans.size)
        val cuts = spans.drop(1).map { it.startSample }
        cuts.zip(dips).forEach { (cut, dip) -> assertTrue(cut in dip.first..dip.first + dip.second, "cut $cut, dip $dip") }
    }

    @Test
    fun capHoldsForAnySegmentation() {
        val audio = speech(120, 30 * rate to rate, 70 * rate to rate / 3)
        val limit = 7 * rate
        repeat(20) { seed ->
            val random = Random(seed)
            val vad = List(random.nextInt(1, 12)) {
                val start = random.nextInt(-rate, audio.size)
                start to random.nextInt(0, 40 * rate)
            }
            val spans = padSpeechSpans(vad, audio, padSamples = random.nextInt(0, rate), maxSamples = limit)
            assertSpansWithin(spans, limit, audio.size)
        }
    }

    @Test
    fun turnTakingPauseInsideOneVadSegmentSplitsIt() {
        // Two utterances 0.35 s apart that the VAD returned as one segment (as after reverberant
        // speech): the cut goes in the middle of the pause.
        val pause = 3 * rate to (0.35 * rate).toInt()
        val audio = speech(6, pause)
        val spans = padSpeechSpans(listOf(rate / 2 to 5 * rate), audio)
        assertEquals(2, spans.size)
        assertTrue(abs(pause.first + pause.second / 2 - spans[1].startSample) <= 160, "cut at ${spans[1].startSample}")
    }

    @Test
    fun shortPausesAndEdgeSilenceDoNotSplit() {
        val shortPause = 3 * rate to (VadSettings.DEFAULT.minPauseSeconds * rate).toInt() - 320
        val audio = speech(6, shortPause, 0 to rate, 5 * rate to rate)
        val spans = padSpeechSpans(listOf(rate to 4 * rate), audio)
        assertEquals(1, spans.size)
    }

    @Test
    fun quietPausesAreRelativeToTheSegmentLevel() {
        // A pause 40 dB below quiet speech still splits it; the same speech without a pause does not.
        val pause = 2 * rate to rate / 2
        val audio = speech(5, pause).also { a -> for (i in a.indices) a[i] *= 0.01f }
        for (i in pause.first until pause.first + pause.second) audio[i] = audio[i + rate] * 0.01f
        assertEquals(2, padSpeechSpans(listOf(0 to audio.size), audio).size)
        assertEquals(1, padSpeechSpans(listOf(0 to audio.size), speech(5)).size)
    }

    /**
     * Steady background noise (amplitude 0.01) with "speech" 20 dB louder at [speechRanges]
     * (start to length in samples): the gaps between utterances hold noise, not silence.
     */
    private fun noisy(seconds: Int, vararg speechRanges: Pair<Int, Int>): FloatArray {
        val random = Random(seconds + 1)
        val audio = FloatArray(seconds * rate) { random.nextFloat() * 0.02f - 0.01f }
        for ((start, length) in speechRanges) for (i in start until start + length) audio[i] *= 10f
        return audio
    }

    @Test
    fun turnTakingPauseFilledWithNoiseSplitsAtTheNoiseFloor() {
        // Two utterances 0.35 s apart in noise (#71): the gap is only 20 dB below the speech, so
        // only the noise floor rule finds it.
        val gap = (0.35 * rate).toInt()
        val audio = noisy(8, 2 * rate to 2 * rate, 4 * rate + gap to 2 * rate)
        val vad = listOf(2 * rate to 4 * rate + gap)
        val spans = padSpeechSpans(vad, audio)
        assertEquals(2, spans.size)
        assertTrue(abs(4 * rate + gap / 2 - spans[1].startSample) <= 160, "cut at ${spans[1].startSample}")
        assertEquals(1, padSpeechSpans(vad, audio, pauseFloorDb = Float.NEGATIVE_INFINITY).size)
    }

    @Test
    fun noisyPausesMustLastLongerThanSilentOnes() {
        // A gap between the two minimums splits when it is silent, not when it holds noise.
        val gap = ((VadSettings.DEFAULT.minPauseSeconds + VadSettings.DEFAULT.minNoisyPauseSeconds) / 2 * rate).toInt()
        val noisy = noisy(8, 2 * rate to 2 * rate, 4 * rate + gap to 2 * rate)
        val vad = listOf(2 * rate to 4 * rate + gap)
        assertEquals(1, padSpeechSpans(vad, noisy).size)
        val silent = noisy.copyOf().also { it.fill(0f, 4 * rate, 4 * rate + gap) }
        assertEquals(2, padSpeechSpans(vad, silent).size)
    }

    @Test
    fun noiseAloneAndShortDipsInNoiseDoNotSplit() {
        val shortGap = (VadSettings.DEFAULT.minPauseSeconds * rate).toInt() - 480
        val audio = noisy(8, 2 * rate to 2 * rate, 4 * rate + shortGap to 2 * rate)
        assertEquals(1, padSpeechSpans(listOf(2 * rate to 4 * rate + shortGap), audio).size)
        // A VAD false alarm on noise only: every frame is near the floor, so there is no pause inside.
        assertEquals(1, padSpeechSpans(listOf(rate to 5 * rate), noisy(8)).size)
    }

    @Test
    fun noiseFloorIsTakenFromTheQuietFramesAroundTheSegment() {
        // A segment that is all speech still finds the noise in the seconds around it.
        val audio = noisy(12, 4 * rate to 4 * rate)
        val floor = noiseFloor(audio, 4 * rate, 8 * rate)
        val noiseEnergy = 0.01 * 0.01 / 3 // mean square of uniform noise in [-0.01, 0.01]
        assertTrue(floor in noiseEnergy * 0.5..noiseEnergy * 1.5, "floor $floor")
    }

    @Test
    fun levelAboveNoiseFloorIsTheLoudFramesOverTheFloorAround() {
        val audio = noisy(12, 4 * rate to 2 * rate)
        // Speech 20 dB above the noise around it.
        val speech = levelAboveNoiseFloorDb(audio, 4 * rate, 6 * rate)!!
        assertTrue(speech in 19f..23f, "speech $speech dB")
        // Noise alone barely rises above its own floor.
        val noise = levelAboveNoiseFloorDb(audio, 8 * rate, 10 * rate)!!
        assertTrue(noise in 0f..3f, "noise $noise dB")
        // Ranges are clamped to the audio; less than one 10 ms frame has no level.
        assertTrue(levelAboveNoiseFloorDb(audio, 11 * rate, 20 * rate)!! in 0f..3f)
        assertNull(levelAboveNoiseFloorDb(audio, rate, rate + 100))
        assertNull(levelAboveNoiseFloorDb(audio, 13 * rate, 14 * rate))
        // Digital silence everywhere stays finite.
        assertEquals(0f, levelAboveNoiseFloorDb(FloatArray(rate), 0, rate))
    }
}
