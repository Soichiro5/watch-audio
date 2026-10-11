package app.watchaudio.core.pipeline

/**
 * Decides which transcribed utterances are stored. [TranscriptionPipeline] applies it to every
 * [Transcriber] result before saving, so that false positives (e.g. short texts recognized from
 * noise, #105) can be dropped on every platform in one place, from the signals [TimedText]
 * carries ([TimedText.speechProbability], [TimedText.levelAboveNoiseDb], [TimedText.confidence]).
 */
fun interface SegmentFilter {
    /** True to store [segment]. */
    fun keep(segment: TimedText): Boolean

    companion object {
        /** Stores everything the transcriber returns. */
        val KEEP_ALL: SegmentFilter = SegmentFilter { true }
    }
}

/**
 * Drops texts recognized from sounds that are not a conversation at the watch (#105), with the
 * two rules chosen on the test audio set (docs/dev/test-audio.md):
 *
 * - **near the noise floor**: the utterance's audio rises less than [minLevelAboveNoiseDb] above
 *   the background ([TimedText.levelAboveNoiseDb]). Distant chatter, air conditioning and
 *   silence turn into 「うん」「え」「何」 this way, while speech at the watch, even a quiet
 *   partner in a café, stands further above the noise;
 * - **a lone sound**: a text of at most [shortTextMaxChars] letters or digits (e.g. 「え」「あ」)
 *   whose mean speech probability ([TimedText.speechProbability]) stays below
 *   [shortTextMinSpeechProbability], as for the rustle of a sleeve or a knock.
 *
 * A missing signal (null, e.g. on iOS, whose speech engine reports neither) is no evidence of
 * noise: the rule that needs it never drops anything.
 */
data class FalsePositiveFilter(
    val minLevelAboveNoiseDb: Float = 7f,
    val shortTextMaxChars: Int = 1,
    val shortTextMinSpeechProbability: Float = 0.2f,
) : SegmentFilter {
    override fun keep(segment: TimedText): Boolean = !isNearNoiseFloor(segment) && !isLoneSound(segment)

    /** True if [segment] rises less than [minLevelAboveNoiseDb] above the background noise. */
    fun isNearNoiseFloor(segment: TimedText): Boolean =
        segment.levelAboveNoiseDb.let { it != null && it < minLevelAboveNoiseDb }

    /** True if [segment] is a very short text that hardly sounded like speech. */
    fun isLoneSound(segment: TimedText): Boolean =
        segment.speechProbability.let { it != null && it < shortTextMinSpeechProbability } &&
            textLength(segment.text) <= shortTextMaxChars

    companion object {
        /** The filter the apps use. */
        val DEFAULT = FalsePositiveFilter()

        /** Letters and digits in [text] (kana and kanji included; punctuation and spaces not). */
        fun textLength(text: String): Int = text.count { it.isLetterOrDigit() }
    }
}
