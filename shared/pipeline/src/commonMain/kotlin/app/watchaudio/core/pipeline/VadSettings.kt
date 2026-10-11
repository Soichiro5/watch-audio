package app.watchaudio.core.pipeline

/**
 * How speech is found and cut into recognizer inputs on the sherpa-onnx path (Android and the
 * JVM harness): the thresholds of [speechSegments] and the padding, length limit and pause
 * splitting of [padSpeechSpans]. The defaults are the values chosen on the test audio set (#68,
 * #71; see docs/dev/test-audio.md); the harness `evaluate` command can override any of them to
 * compare settings without recompiling.
 */
data class VadSettings(
    /** Silero window length in samples (32 ms at [AsrSettings.SAMPLE_RATE]). */
    val windowSize: Int = 512,

    /**
     * Speech starts where the Silero probability reaches this ([speechSegments]). The M0-5 spike
     * chose 0.3 on clean speech; 0.15 also hears a conversation partner in noise (#71: 8 dB
     * quieter than the wearer, at 2–7 dB SNR), which 0.3 misses or cuts short.
     */
    val threshold: Float = 0.15f,

    /** Speech ends where the probability stays below this for [minSilenceSeconds]. */
    val negThreshold: Float = 0.15f,

    /**
     * Long, so that a quiet voice whose probability dips is not cut; pauses and turn-taking
     * inside a segment are split by [padSpeechSpans] instead.
     */
    val minSilenceSeconds: Float = 2f,

    /** Shorter speech segments are dropped. */
    val minSpeechSeconds: Float = 0.25f,

    /** Windows a segment start is moved back by, as the probability rises late on word onsets. */
    val startBackWindows: Int = 2,

    /** Context added around each VAD segment; without it word onsets get clipped. */
    val segmentPadSeconds: Float = 0.3f,

    /**
     * Upper bound on one recognizer input, padding included. The ReazonSpeech Zipformer drops
     * whole sentences from long inputs (#68), so longer speech is split (see [padSpeechSpans]).
     */
    val maxSegmentSeconds: Float = 20f,

    /** A near-silent pause this long inside a VAD segment splits it (turn-taking gaps). */
    val minPauseSeconds: Float = 0.22f,

    /** How far below the segment's loud frames a pause must be, in dB. */
    val pauseDb: Float = 30f,

    /**
     * A pause may instead be this close to the noise floor, in dB (#71). In noise, turn-taking
     * gaps are filled with noise and never get [pauseDb] below the speech.
     */
    val pauseFloorDb: Float = 9f,

    /**
     * A pause found only near the noise floor must last this long; noise has more short dips
     * than silence does, and shorter pieces lose the context the recognizer needs.
     */
    val minNoisyPauseSeconds: Float = 0.3f,

    /**
     * However close to the noise floor, a pause must stay this far below the segment's loud
     * frames, in dB, so that speech as loud as the noise is never taken for a pause.
     */
    val pauseMinContrastDb: Float = 6f,

    /**
     * Pauses are compared with the loud frames within this distance (not of the whole segment),
     * so that a quiet speaker next to a loud one is not cut at every dip.
     */
    val pauseLoudWindowSeconds: Float = 1f,

    /** Percentile of the 10 ms frame energies around a segment taken as its noise floor. */
    val noiseFloorPercentile: Int = 10,

    /** Audio on each side of a segment that its noise floor is estimated over. */
    val noiseFloorContextSeconds: Float = 5f,
) {
    init {
        require(windowSize > 0) { "windowSize must be positive" }
        require(noiseFloorPercentile in 0..100) { "noiseFloorPercentile must be within 0..100" }
    }

    val padSamples: Int get() = samples(segmentPadSeconds)
    val maxSamples: Int get() = samples(maxSegmentSeconds)
    val minPauseSamples: Int get() = samples(minPauseSeconds)
    val minNoisyPauseSamples: Int get() = samples(minNoisyPauseSeconds)
    val pauseLoudWindowSamples: Int get() = samples(pauseLoudWindowSeconds)
    val noiseFloorContextSamples: Int get() = samples(noiseFloorContextSeconds)

    companion object {
        /** The settings the apps use. */
        val DEFAULT = VadSettings()

        private fun samples(seconds: Float): Int = (seconds * AsrSettings.SAMPLE_RATE).toInt()
    }
}
