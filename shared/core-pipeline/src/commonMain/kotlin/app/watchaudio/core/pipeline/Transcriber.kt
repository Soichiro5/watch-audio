package app.watchaudio.core.pipeline

import kotlinx.io.files.Path

/**
 * On-device speech-to-text for one archived chunk. Implementations:
 * - Android: sherpa-onnx (VAD + offline recognizer)
 * - iOS: SpeechAnalyzer / SpeechTranscriber (implemented in Swift and injected)
 * - JVM (tests and the Linux harness): sherpa-onnx Java API
 *
 * Nothing leaves the device: implementations must not call network services.
 */
interface Transcriber {
    /** Stored with each segment, e.g. `sherpa-zipformer-ja-reazonspeech`. */
    val engineId: String

    /** Returns utterances with times relative to the start of [audioFile]. */
    suspend fun transcribe(audioFile: Path): List<TimedText>
}

data class TimedText(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val lang: String? = null,
)
