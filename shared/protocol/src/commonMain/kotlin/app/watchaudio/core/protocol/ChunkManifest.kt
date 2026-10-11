package app.watchaudio.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes one recorded chunk (normally ~5 minutes) sent from a watch to the phone.
 *
 * This is the wire contract between the watch apps and the phone: the Wear OS app uses this
 * class directly and the watchOS app mirrors it with `Codable` (see `testdata/`). Times are
 * epoch milliseconds so both sides can encode them without date-format differences.
 *
 * A chunk with [audio] == null covers a span that was confidently silent: the watch kept the
 * time range on the timeline but did not store audio for it.
 */
@Serializable
data class ChunkManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** UUID generated on the watch; the idempotency key for ingest and ACKs. */
    val chunkId: String,
    /** One recording session = from "start" until the recorder stops for any reason. */
    val sessionId: String,
    /** Stable per app install on the watch. */
    val deviceId: String,
    val source: SourcePlatform,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    /** IANA time zone on the watch when the chunk started, e.g. `Asia/Tokyo`. */
    val timeZone: String,
    val audio: AudioFile? = null,
    /** Offsets (ms from [startedAtEpochMs]) where the wearer tapped "Mark". */
    val markOffsetsMs: List<Long> = emptyList(),
    /** Why this chunk was closed. Anything other than [ChunkEndReason.ROTATION] starts a gap. */
    val endReason: ChunkEndReason,
) {
    val endedAtEpochMs: Long get() = startedAtEpochMs + durationMs

    init {
        require(chunkId.isNotBlank()) { "chunkId must not be blank" }
        require(durationMs >= 0) { "durationMs must not be negative" }
        require(markOffsetsMs.all { it in 0..durationMs }) { "marks must lie within the chunk" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class AudioFile(
    /** File name on the sending device, e.g. `<chunkId>.m4a`. */
    val fileName: String,
    val codec: AudioCodec,
    val sampleRateHz: Int,
    val channels: Int,
    val bitrateBps: Int,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the file contents. */
    val sha256: String,
)

@Serializable
enum class SourcePlatform {
    @SerialName("watchos") WATCHOS,
    @SerialName("wearos") WEAROS,
    @SerialName("ios") IOS,
    @SerialName("android") ANDROID,
}

@Serializable
enum class AudioCodec {
    /** AAC-LC in an MPEG-4 (.m4a) container. */
    @SerialName("aac") AAC,
    /** Opus in an Ogg container. */
    @SerialName("opus") OPUS,
}

@Serializable
enum class ChunkEndReason(val startsGap: Boolean) {
    /** Regular rotation; the next chunk continues seamlessly. */
    @SerialName("rotation") ROTATION(startsGap = false),
    /** The wearer tapped Mark; recording continues in a new chunk. */
    @SerialName("mark") MARK(startsGap = false),
    @SerialName("user_stop") USER_STOP(startsGap = true),
    /** Phone call, Siri/Assistant or another app took the microphone. */
    @SerialName("interruption") INTERRUPTION(startsGap = true),
    @SerialName("charging") CHARGING(startsGap = true),
    @SerialName("off_body") OFF_BODY(startsGap = true),
    @SerialName("low_battery") LOW_BATTERY(startsGap = true),
    @SerialName("low_storage") LOW_STORAGE(startsGap = true),
    /** The app/service was terminated; the chunk was finalized on next launch. */
    @SerialName("terminated") TERMINATED(startsGap = true),
    @SerialName("error") ERROR(startsGap = true),
}
