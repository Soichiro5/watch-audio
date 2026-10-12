package app.watchaudio.wear.recording

import app.watchaudio.core.protocol.RecorderRules
import kotlin.math.ceil
import kotlin.math.pow

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
        /** Full scale of `MediaRecorder.getMaxAmplitude()` (16-bit PCM). */
        const val FULL_SCALE_AMPLITUDE = 32_767

        /**
         * Peak amplitude at or above which a poll counts as sound: the shared
         * [RecorderRules.silencePeakDbfs] (-40 dBFS → 328/32767), the same rule as the watchOS
         * recorder (#137). Normal speech near the watch peaks far above this (typically > -20
         * dBFS) and even distant speech in a quiet room stays above it, while a quiet bedroom's
         * noise floor sits below. Deliberately low: dropping a chunk loses its audio forever,
         * keeping one costs ~0.9 MB. Tune on real devices (docs/platforms/wearos.md).
         */
        val SILENCE_PEAK_THRESHOLD: Int = amplitudeAt(RecorderRules.CURRENT.silencePeakDbfs)

        const val POLL_INTERVAL_MS = 1_000L

        /** Fraction of the expected polls that must have happened to trust the decision. */
        val MIN_COVERAGE: Double = RecorderRules.CURRENT.silenceMinCoveragePercent / 100.0

        /** Very short chunks (stopped right after start) are never declared silent. */
        const val MIN_SAMPLES = 10

        /** Smallest amplitude whose level is at least [dbfs] relative to [FULL_SCALE_AMPLITUDE]. */
        fun amplitudeAt(dbfs: Int): Int = ceil(FULL_SCALE_AMPLITUDE * 10.0.pow(dbfs / 20.0)).toInt()
    }
}
