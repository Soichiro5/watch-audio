package app.watchaudio.core.protocol

import kotlinx.serialization.Serializable

/**
 * When a watch stops and resumes recording and when it drops a chunk's audio as silent: the
 * rules both watch apps must apply the same way, so the product does not behave differently
 * depending on the watch model (#137, docs/architecture/02-architecture.md §6.1).
 *
 * [CURRENT] is the single source for the Kotlin side (Wear OS recorder). The watchOS app mirrors
 * it as `RecorderRules.current` (apps/ios/SharedSwift/RecorderRules.swift); both sides check their
 * values against `testdata/recorder-rules.json`, like [RecordingFormat]. Not sent on the wire.
 *
 * Deliberately not here: the resend wait without an ACK, which depends on the transport
 * (Wear OS `Outbox.ACK_TIMEOUT_MS`, watchOS `ChunkSendTracker.resendAfter`).
 */
@Serializable
data class RecorderRules(
    /** While not charging, recording pauses when the battery level falls below this percentage. */
    val lowBatteryStopPercent: Int,
    /** A pause for low battery ends only at or above this percentage, so readings that hover
     *  around [lowBatteryStopPercent] do not create a stream of tiny chunks. */
    val lowBatteryResumePercent: Int,
    /**
     * A level sample (about once per second) counts as sound when its peak is at or above this
     * many dBFS. A chunk in which no sample was sound is silent: its audio is dropped and only the
     * manifest is sent.
     */
    val silencePeakDbfs: Int,
    /**
     * A chunk is only declared silent if the level samples covered at least this percentage of
     * its duration (a stalled sampler must not turn speech into "silence").
     */
    val silenceMinCoveragePercent: Int,
) {
    init {
        require(lowBatteryStopPercent in 0..lowBatteryResumePercent && lowBatteryResumePercent <= 100) { "invalid battery rules: $this" }
        require(silencePeakDbfs < 0 && silenceMinCoveragePercent in 1..100) { "invalid silence rules: $this" }
    }

    companion object {
        /**
         * Stop below 10 % (so the last chunk can still be finalized and sent), resume at 15 %;
         * silence is a peak below -40 dBFS in every sample of at least 80 % of the chunk.
         */
        val CURRENT = RecorderRules(
            lowBatteryStopPercent = 10,
            lowBatteryResumePercent = 15,
            silencePeakDbfs = -40,
            silenceMinCoveragePercent = 80,
        )
    }
}
