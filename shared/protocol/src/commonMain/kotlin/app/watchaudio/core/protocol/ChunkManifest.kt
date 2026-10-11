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
    /** Version of this format; the phone answers a newer version with [AckStatus.REJECTED]. */
    val schemaVersion: Int = SCHEMA_VERSION,
    /** UUID generated on the watch; the idempotency key for ingest and ACKs. */
    val chunkId: String,
    /** One recording session = from "start" until the recorder stops for any reason. */
    val sessionId: String,
    /** Stable per app install on the watch. */
    val deviceId: String,
    /** Kind of device that recorded the chunk. */
    val source: SourcePlatform,
    /** Start of the recorded span (epoch ms, wall clock on the watch). */
    val startedAtEpochMs: Long,
    /** Length of the recorded span; a silent chunk still has its full duration. */
    val durationMs: Long,
    /** IANA time zone on the watch when the chunk started, e.g. `Asia/Tokyo`. */
    val timeZone: String,
    /** The audio file that follows the manifest, or null for a silent chunk (see above). */
    val audio: AudioFile? = null,
    /** Offsets (ms from [startedAtEpochMs]) where the wearer tapped "Mark". */
    val markOffsetsMs: List<Long> = emptyList(),
    /** Why this chunk was closed. Anything other than [ChunkEndReason.ROTATION] starts a gap. */
    val endReason: ChunkEndReason,
) {
    /** End of the recorded span (epoch ms); not serialized. */
    val endedAtEpochMs: Long get() = startedAtEpochMs + durationMs

    init {
        require(chunkId.isNotBlank()) { "chunkId must not be blank" }
        require(durationMs >= 0) { "durationMs must not be negative" }
        require(markOffsetsMs.all { it in 0..durationMs }) { "marks must lie within the chunk" }
    }

    companion object {
        /** Current version of the manifest and ACK format. */
        const val SCHEMA_VERSION = 1
    }
}

/**
 * The encoded audio of a [ChunkManifest]. The phone checks [sizeBytes] and [sha256] against the
 * received bytes before storing them (a mismatch is answered with [AckStatus.CORRUPT]).
 */
@Serializable
data class AudioFile(
    /** File name on the sending device, e.g. `<chunkId>.m4a`. */
    val fileName: String,
    /** Encoding and container; decides the file extension on the phone. */
    val codec: AudioCodec,
    /** Sample rate of the encoded audio, e.g. 16000. */
    val sampleRateHz: Int,
    /** Number of channels (1 = mono). */
    val channels: Int,
    /** Target encoder bitrate in bits per second. */
    val bitrateBps: Int,
    /** Exact size of the file in bytes. */
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the file contents. */
    val sha256: String,
)

/** Kind of device that recorded a chunk. Wire values are the lowercase names. */
@Serializable
enum class SourcePlatform {
    /** Apple Watch app. */
    @SerialName("watchos") WATCHOS,
    /** Wear OS app. */
    @SerialName("wearos") WEAROS,
    /** iPhone (reserved; the phones do not record, see docs/architecture/02 §9.1). */
    @SerialName("ios") IOS,
    /** Android phone (reserved; the phones do not record, see docs/architecture/02 §9.1). */
    @SerialName("android") ANDROID,
}

/**
 * Audio encoding of an [AudioFile]. Wire values are the lowercase names; [fileExtension] is the
 * extension of files in this encoding (on the watches and in the phone's archive).
 */
@Serializable
enum class AudioCodec(val fileExtension: String) {
    /** AAC-LC in an MPEG-4 (.m4a) container. */
    @SerialName("aac") AAC("m4a"),
    /** Opus in an Ogg container. */
    @SerialName("opus") OPUS("ogg"),
}

/**
 * Why the watch closed a chunk. [startsGap] is true when recording stopped there, so the time
 * until the next chunk is a gap in the timeline rather than a seamless continuation.
 */
@Serializable
enum class ChunkEndReason(val startsGap: Boolean) {
    /** Regular rotation; the next chunk continues seamlessly. */
    @SerialName("rotation") ROTATION(startsGap = false),
    /** The wearer tapped Mark; recording continues in a new chunk. */
    @SerialName("mark") MARK(startsGap = false),
    /** The wearer tapped Stop. */
    @SerialName("user_stop") USER_STOP(startsGap = true),
    /** Phone call, Siri/Assistant or another app took the microphone. */
    @SerialName("interruption") INTERRUPTION(startsGap = true),
    /** The watch was put on the charger. */
    @SerialName("charging") CHARGING(startsGap = true),
    /** The watch was taken off the wrist. */
    @SerialName("off_body") OFF_BODY(startsGap = true),
    /** The watch battery fell below the recording threshold. */
    @SerialName("low_battery") LOW_BATTERY(startsGap = true),
    /** Free storage on the watch fell below the recording threshold. */
    @SerialName("low_storage") LOW_STORAGE(startsGap = true),
    /** The app/service was terminated; the chunk was finalized on next launch. */
    @SerialName("terminated") TERMINATED(startsGap = true),
    /** The recorder failed (e.g. the microphone or encoder could not start). */
    @SerialName("error") ERROR(startsGap = true),
}
