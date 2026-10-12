package app.watchaudio.harness

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The test audio set written by `tools/test-audio/generate.py`: recordings (one per condition)
 * with ground-truth utterances. Times are ms from the start of the recording.
 */
@Serializable
data class TestSetManifest(
    val version: Int,
    val sampleRate: Int,
    val recordings: List<TestRecording>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(file: File): TestSetManifest = json.decodeFromString(serializer(), file.readText())
    }
}

@Serializable
data class TestRecording(
    val id: String,
    val description: String = "",
    /** Microphone signal (before the watch's AAC encoding), relative to the manifest. */
    val audio: String,
    val durationMs: Long,
    val segments: List<TruthSegment>,
)

/** One ground-truth utterance. [group] (a conversation) is the unit CER is aligned over. */
@Serializable
data class TruthSegment(
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    /** `wearer` (the person wearing the watch), `partner` (others in the room) or `phone`. */
    val role: String,
    val group: String,
    val text: String,
    /** BCP 47 language of [text]; English groups are scored by words (WER). */
    val lang: String = "ja",
)
