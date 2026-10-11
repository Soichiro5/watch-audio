package app.watchaudio.harness

import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.SpeechSpan
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import app.watchaudio.core.pipeline.concatenateParts
import app.watchaudio.core.pipeline.padSpeechSpans
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * sherpa-onnx on the JVM: Silero VAD splits the chunk into utterances, then the Japanese
 * ReazonSpeech Zipformer (int8) transcribes each one. Same models and [AsrSettings] as the
 * Android app, so results here predict on-device accuracy (not speed).
 */
class SherpaTranscriber(modelsDir: File, numThreads: Int = 2) : Transcriber, AutoCloseable {
    override val engineId = AsrSettings.JA_ENGINE_ID

    private val vadModel = File(modelsDir, "silero_vad.onnx").path
    private val recognizer: OfflineRecognizer

    init {
        val dir = File(modelsDir, AsrSettings.JA_MODEL_NAME)
        require(File(dir, "tokens.txt").exists()) { "ASR model not found in $dir (run tools/harness/download-models.sh)" }
        val model = OfflineModelConfig.builder()
            .setTransducer(
                OfflineTransducerModelConfig.builder()
                    .setEncoder(File(dir, "encoder-epoch-99-avg-1.int8.onnx").path)
                    .setDecoder(File(dir, "decoder-epoch-99-avg-1.int8.onnx").path)
                    .setJoiner(File(dir, "joiner-epoch-99-avg-1.int8.onnx").path)
                    .build(),
            )
            .setTokens(File(dir, "tokens.txt").path)
            .setNumThreads(numThreads)
            .build()
        recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder().setOfflineModelConfig(model).setDecodingMethod("greedy_search").build(),
        )
    }

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = withContext(Dispatchers.Default) {
        val samples = concatenateParts(parts) { Ffmpeg.decode(File(it.file.toString())) }
        speechSpans(samples).mapNotNull { span ->
            val text = recognize(span.samples)
            if (text.isEmpty()) null else TimedText(span.startMs, span.endMs, text, lang = "ja")
        }
    }

    /** Recognizes [samples] (16 kHz) as one utterance, without VAD. */
    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrSettings.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Runs Silero VAD over the whole input and pads the detected utterances. */
    private fun speechSpans(samples: FloatArray): List<SpeechSpan> {
        val config = VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel(vadModel)
                    .setThreshold(AsrSettings.VAD_THRESHOLD)
                    .setMinSilenceDuration(AsrSettings.VAD_MIN_SILENCE_SECONDS)
                    .setMinSpeechDuration(AsrSettings.VAD_MIN_SPEECH_SECONDS)
                    .setMaxSpeechDuration(AsrSettings.VAD_MAX_SPEECH_SECONDS)
                    .setWindowSize(AsrSettings.VAD_WINDOW_SIZE)
                    .build(),
            )
            .setSampleRate(AsrSettings.SAMPLE_RATE)
            .setNumThreads(1)
            .build()
        val vad = Vad(config)
        val segments = mutableListOf<Pair<Int, Int>>()
        fun drain() {
            while (!vad.empty()) {
                val front = vad.front()
                segments += front.start to front.samples.size
                vad.pop()
            }
        }
        try {
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + AsrSettings.VAD_WINDOW_SIZE, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))
                drain()
                offset = end
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return padSpeechSpans(segments, samples)
    }

    override fun close() = recognizer.release()
}
