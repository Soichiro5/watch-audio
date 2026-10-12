import Foundation

/// When the watch stops and resumes recording and when it drops a chunk's audio as silent. Swift
/// mirror of `RecorderRules` in shared/protocol (RecorderRules.kt); both sides check their values
/// against shared/protocol/testdata/recorder-rules.json, so the Apple Watch and Wear OS apps apply
/// the same rules (#137, docs/architecture/02-architecture.md §6.1). Not sent on the wire.
///
/// The resend wait without an ACK is transport-specific and deliberately not here
/// (`ChunkSendTracker.resendAfter`, Wear OS `Outbox.ACK_TIMEOUT_MS`).
struct RecorderRules: Codable, Equatable, Sendable {
    /// While not charging, recording pauses when the battery level falls below this percentage.
    var lowBatteryStopPercent: Int
    /// A pause for low battery ends only at or above this percentage, so readings that hover
    /// around `lowBatteryStopPercent` do not create a stream of tiny chunks.
    var lowBatteryResumePercent: Int
    /// A level sample (once per tick) counts as sound when its peak is at or above this many
    /// dBFS. A chunk in which no sample was sound is silent: only its manifest is sent.
    var silencePeakDbfs: Int
    /// A chunk is only declared silent if the level samples covered at least this percentage of
    /// its duration (a recorder that stopped metering must not make speech disappear).
    var silenceMinCoveragePercent: Int

    /// Stop below 10 % (so the last chunk can still be finalized and sent), resume at 15 %;
    /// silence is a peak below -40 dBFS in every sample of at least 80 % of the chunk.
    static let current = RecorderRules(
        lowBatteryStopPercent: 10,
        lowBatteryResumePercent: 15,
        silencePeakDbfs: -40,
        silenceMinCoveragePercent: 80
    )
}
