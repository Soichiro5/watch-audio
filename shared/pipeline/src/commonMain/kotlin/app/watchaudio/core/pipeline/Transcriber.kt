package app.watchaudio.core.pipeline

import kotlinx.io.files.Path

/**
 * On-device speech-to-text for archived chunks. Implementations:
 * - Android: sherpa-onnx (VAD + offline recognizer)
 * - iOS: SpeechAnalyzer / SpeechTranscriber (implemented in Swift and injected)
 * - JVM (tests and the Linux harness): sherpa-onnx Java API
 *
 * Nothing leaves the device: implementations must not call network services.
 */
interface Transcriber {
    /** Stored with each segment, e.g. `sherpa-zipformer-ja-reazonspeech`. */
    val engineId: String

    /**
     * Transcribes the concatenation of [parts], in order, as one continuous recording. The
     * pipeline passes a single whole chunk, or a chunk joined with the adjoining audio of its
     * neighbours so that an utterance crossing a chunk boundary is recognised in one piece.
     *
     * Each part must contribute exactly `toMs - fromMs` of audio (cut from the decoded file, and
     * padded with silence if the file is shorter), so that times map back to the parts by their
     * declared lengths.
     *
     * @return utterances with times relative to the start of the concatenation.
     */
    suspend fun transcribe(parts: List<AudioPart>): List<TimedText>
}

/** The range `[fromMs, toMs)` of an archived audio file, in ms from the start of the file. */
data class AudioPart(val file: Path, val fromMs: Long, val toMs: Long) {
    init {
        require(fromMs in 0..toMs) { "invalid range $fromMs..$toMs" }
    }

    val durationMs: Long get() = toMs - fromMs
}

/**
 * One recognized utterance, times in ms. Besides the stored fields ([text], [lang]) it carries
 * optional signals for a [SegmentFilter], null when the transcriber cannot tell:
 *
 * - [confidence]: the recognizer's own confidence in the text, 0..1;
 * - [speechProbability]: the mean VAD speech probability (0..1) over the utterance's audio
 *   (sherpa-onnx path: Silero, see [transcribeSpeech]);
 * - [levelAboveNoiseDb]: how far the utterance's audio rises above the background noise, in dB
 *   ([levelAboveNoiseFloorDb]).
 *
 * None of them is stored in the database.
 */
data class TimedText(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val lang: String? = null,
    val confidence: Float? = null,
    val speechProbability: Float? = null,
    val levelAboveNoiseDb: Float? = null,
)
