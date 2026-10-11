package app.watchaudio.core.pipeline

/**
 * Decides which transcribed utterances are stored. [TranscriptionPipeline] applies it to every
 * [Transcriber] result before saving, so that false positives (e.g. short texts recognized from
 * noise, #105) can be dropped on every platform in one place, from the signals [TimedText]
 * carries ([TimedText.speechProbability], [TimedText.confidence]).
 */
fun interface SegmentFilter {
    /** True to store [segment]. */
    fun keep(segment: TimedText): Boolean

    companion object {
        /** Stores everything the transcriber returns (the default). */
        val KEEP_ALL: SegmentFilter = SegmentFilter { true }
    }
}
