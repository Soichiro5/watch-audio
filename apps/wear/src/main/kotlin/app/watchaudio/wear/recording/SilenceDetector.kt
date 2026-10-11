package app.watchaudio.wear.recording

/**
 * Decides whether a whole chunk was confidently silent from periodic
 * `MediaRecorder.getMaxAmplitude()` polls (the peak absolute sample, 0..32767, since the
 * previous poll).
 *
 * Errs on the side of keeping audio: a chunk is only silent when no poll reached
 * [SILENCE_PEAK_THRESHOLD] *and* the polls covered most of the chunk (a stalled poller must not
 * turn speech into "silence").
 */
class SilenceDetector(
    samples: Int = 0,
    loudSamples: Int = 0,
    peak: Int = 0,
) {
    var samples: Int = samples
        private set
    var loudSamples: Int = loudSamples
        private set
    var peak: Int = peak
        private set

    fun add(amplitude: Int) {
        samples++
        if (amplitude >= SILENCE_PEAK_THRESHOLD) loudSamples++
        if (amplitude > peak) peak = amplitude
    }

    fun isSilent(durationMs: Long, pollIntervalMs: Long = POLL_INTERVAL_MS): Boolean {
        if (loudSamples > 0) return false
        val expected = durationMs / pollIntervalMs
        val required = maxOf(MIN_SAMPLES.toLong(), (expected * MIN_COVERAGE).toLong())
        return samples >= required
    }

    companion object {
        /**
         * Peak amplitude below which a poll counts as silence: 300/32767 ≈ -40 dBFS peak. Normal
         * speech near the watch peaks far above this (typically > -20 dBFS) and even distant
         * speech in a quiet room stays above it, while a quiet bedroom's noise floor sits below.
         * Deliberately low: dropping a chunk loses its audio forever, keeping one costs ~0.9 MB.
         * Tune on real devices (docs/platforms/wearos.md).
         */
        const val SILENCE_PEAK_THRESHOLD = 300

        const val POLL_INTERVAL_MS = 1_000L

        /** Fraction of the expected polls that must have happened to trust the decision. */
        const val MIN_COVERAGE = 0.8

        /** Very short chunks (stopped right after start) are never declared silent. */
        const val MIN_SAMPLES = 10
    }
}
