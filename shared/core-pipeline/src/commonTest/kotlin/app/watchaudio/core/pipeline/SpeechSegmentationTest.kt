package app.watchaudio.core.pipeline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        val padMs = (AsrSettings.SEGMENT_PAD_SECONDS * 1000).toLong()
        assertEquals(2_000 - padMs, spans.single().startMs)
        assertEquals(3_000 + padMs, spans.single().endMs)
    }
}
