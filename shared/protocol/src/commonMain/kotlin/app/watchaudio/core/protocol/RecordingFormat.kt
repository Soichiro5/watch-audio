package app.watchaudio.core.protocol

import kotlinx.serialization.Serializable

/**
 * How a watch records and cuts audio: encoding, sample rate, channels, target bitrate and chunk
 * length. Every [AudioFile] the watches send describes audio in [CURRENT].
 *
 * [CURRENT] is the single source for the Kotlin side (Wear OS recorder, harness). The watchOS
 * app mirrors it as `RecordingFormat.current` (apps/ios/SharedSwift/RecordingFormat.swift);
 * both sides check their values against `testdata/recording-format.json`. Not sent on the wire
 * itself: the manifest carries the format of each file in [AudioFile].
 */
@Serializable
data class RecordingFormat(
    /** Encoding and container of the chunk files. */
    val codec: AudioCodec,
    /** Sample rate of the encoded audio. */
    val sampleRateHz: Int,
    /** Number of channels (1 = mono). */
    val channels: Int,
    /** Target encoder bitrate in bits per second. */
    val bitrateBps: Int,
    /** Length of a regular chunk before the watch rotates to a new file. */
    val chunkDurationMs: Long,
) {
    init {
        require(sampleRateHz > 0 && channels > 0 && bitrateBps > 0 && chunkDurationMs > 0) { "invalid format: $this" }
    }

    /** Describes a chunk file recorded in this format. */
    fun audioFile(fileName: String, sizeBytes: Long, sha256: String) = AudioFile(
        fileName = fileName,
        codec = codec,
        sampleRateHz = sampleRateHz,
        channels = channels,
        bitrateBps = bitrateBps,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )

    companion object {
        /** What both watch apps record today: AAC-LC 16 kHz mono 24 kbps (.m4a), 5-minute chunks. */
        val CURRENT = RecordingFormat(
            codec = AudioCodec.AAC,
            sampleRateHz = 16_000,
            channels = 1,
            bitrateBps = 24_000,
            chunkDurationMs = 300_000,
        )
    }
}
