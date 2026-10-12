package app.watchaudio.core.export

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The JSON files of an export (#155): `export.json` ([ExportIndex]) and one `json/<date>.json`
// ([ExportDay]) per day. They are meant to be read back (the encrypted backup, #40, reuses them),
// so the format is versioned and only grows: a reader ignores unknown keys, and a change that an
// older reader would misread raises [EXPORT_FORMAT_VERSION]. Times are epoch ms; chunks are
// known by the watch's UUID (database keys never leave the device). Spec: docs/architecture/02 §6.6.

/** Value of `format` in every export JSON file. */
const val EXPORT_FORMAT = "watch-audio-export"

/** Current version of the export format. */
const val EXPORT_FORMAT_VERSION = 1

/** `export.json`: what the archive holds. */
@Serializable
data class ExportIndex(
    val format: String = EXPORT_FORMAT,
    val formatVersion: Int = EXPORT_FORMAT_VERSION,
    /** When the export was made (epoch ms). */
    val exportedAt: Long,
    /** IANA time zone that decided which day each chunk belongs to. */
    val timeZone: String,
    /** First and last day the user asked for; null when unbounded ("all"). */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Whether the user chose to include audio files. */
    val includesAudio: Boolean,
    /** The exported days that have at least one chunk, oldest first. */
    val days: List<ExportIndexDay>,
)

/** One day in [ExportIndex]; paths are relative to the archive's root folder. */
@Serializable
data class ExportIndexDay(
    val date: LocalDate,
    val json: String,
    val markdown: String,
    val chunks: Int,
    val segments: Int,
    val marks: Int,
    val gaps: Int,
    val audioFiles: Int,
)

/**
 * `json/<date>.json`: the chunks that started on [date] in [timeZone], with their transcript
 * segments and marks, and the gaps of that day as the timeline shows them.
 */
@Serializable
data class ExportDay(
    val format: String = EXPORT_FORMAT,
    val formatVersion: Int = EXPORT_FORMAT_VERSION,
    val date: LocalDate,
    val timeZone: String,
    /** The day as `[dayStart, dayEnd)` (23 or 25 hours on DST changes). */
    val dayStart: Long,
    val dayEnd: Long,
    /** Oldest first. */
    val chunks: List<ExportChunk>,
    /** Transcript of [chunks], by start time. A segment may end after [dayEnd]. */
    val segments: List<ExportSegment>,
    /** Taps on "Mark" during [chunks], by time. */
    val marks: List<ExportMark>,
    /** Spans of the day with no recording, by start time, clipped to the day and the export time. */
    val gaps: List<ExportGap>,
)

/** A recorded chunk (normally 5 minutes) as the watch described it. */
@Serializable
data class ExportChunk(
    /** UUID assigned by the watch; segments and marks refer to it. */
    val id: String,
    val sessionId: String,
    val deviceId: String,
    val source: SourcePlatform,
    val startedAt: Long,
    val endedAt: Long,
    /** IANA time zone on the watch when the chunk started. */
    val timeZone: String,
    /** Why the watch closed the chunk; a reason that starts a gap explains the gap after it. */
    val endReason: ChunkEndReason,
    val transcription: ExportTranscription,
    /** The recorded audio; null for a confidently silent chunk, which has no audio. */
    val audio: ExportAudio? = null,
)

/** The audio of an [ExportChunk]. */
@Serializable
data class ExportAudio(
    val codec: AudioCodec? = null,
    /** Size as received from the watch. */
    val sizeBytes: Long? = null,
    /** Lowercase hex SHA-256 as received from the watch. */
    val sha256: String? = null,
    /**
     * When the audio was deleted from the phone (the transcript is kept), or null: by the
     * retention setting, or by the offload of backed-up audio (#156, then [backedUpAt] is set).
     */
    val purgedAt: Long? = null,
    /** Path of the audio file in the archive, or null when it was not included. */
    val file: String? = null,
    /**
     * When the audio was last written into an encrypted backup the user saved (that backup's
     * creation time, #156), or null. Restoring that backup brings the audio back.
     */
    val backedUpAt: Long? = null,
)

/** Where a chunk's transcription stands. */
@Serializable
enum class ExportTranscription {
    /** Waiting for (or in) transcription; its segments are not in the export yet. */
    @SerialName("pending") PENDING,

    /** Transcribed; its segments are complete (possibly none, when nothing was said). */
    @SerialName("done") DONE,

    /** Transcription failed; it can be retried in the app. */
    @SerialName("failed") FAILED,

    /** A silent chunk: there is nothing to transcribe. */
    @SerialName("none") NONE,
}

/** One recognized utterance. */
@Serializable
data class ExportSegment(
    /** The [ExportChunk.id] the utterance starts in. */
    val chunkId: String,
    val startAt: Long,
    val endAt: Long,
    val text: String,
    /** Language reported by the recognizer, or null if unknown. */
    val lang: String? = null,
    /** The transcriber that produced it (e.g. the speech model). */
    val engine: String,
)

/** The wearer tapped "Mark" at [at] during chunk [chunkId]. */
@Serializable
data class ExportMark(val chunkId: String, val at: Long)

/** A span without recording. */
@Serializable
data class ExportGap(
    val start: Long,
    val end: Long,
    val cause: ExportGapCause,
    /** Why the recorder stopped, for [ExportGapCause.STOPPED]. */
    val endReason: ChunkEndReason? = null,
)

/** Why a span has no recording. */
@Serializable
enum class ExportGapCause {
    /** The recorder stopped and said why ([ExportGap.endReason]). */
    @SerialName("stopped") STOPPED,

    /** The recorder was running, but no chunk for this span reached the phone. */
    @SerialName("missing") MISSING,

    /** Shortly before the export: the chunk was probably still being recorded or sent. */
    @SerialName("awaiting") AWAITING,
}

/** JSON settings of the export files: readable, and tolerant of fields added by newer versions. */
val ExportJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

fun ExportIndex.toJson(): String = ExportJson.encodeToString(ExportIndex.serializer(), this)

fun ExportDay.toJson(): String = ExportJson.encodeToString(ExportDay.serializer(), this)

/**
 * Reads `export.json`.
 * @throws IllegalArgumentException if [json] is not an export index this version can read
 *   (malformed JSON throws [kotlinx.serialization.SerializationException], a subclass).
 */
fun decodeExportIndex(json: String): ExportIndex =
    ExportJson.decodeFromString(ExportIndex.serializer(), json).also { checkFormat(it.format, it.formatVersion) }

/**
 * Reads a `json/<date>.json` day file.
 * @throws IllegalArgumentException if [json] is not an export day this version can read.
 */
fun decodeExportDay(json: String): ExportDay =
    ExportJson.decodeFromString(ExportDay.serializer(), json).also { checkFormat(it.format, it.formatVersion) }

private fun checkFormat(format: String, version: Int) {
    require(format == EXPORT_FORMAT) { "Not a $EXPORT_FORMAT file: $format" }
    require(version in 1..EXPORT_FORMAT_VERSION) { "Unsupported $EXPORT_FORMAT version $version" }
}
