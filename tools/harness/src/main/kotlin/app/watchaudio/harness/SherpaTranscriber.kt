package app.watchaudio.harness

import app.watchaudio.core.pipeline.AsrModelFile
import app.watchaudio.core.pipeline.AsrModelSpec
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.Recognition
import app.watchaudio.core.pipeline.RecognizerKind
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import app.watchaudio.core.pipeline.VadSettings
import app.watchaudio.core.pipeline.concatenateParts
import app.watchaudio.core.pipeline.speechProbabilities
import app.watchaudio.core.pipeline.transcribeSpeech
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
 * sherpa-onnx on the JVM: Silero VAD finds the utterances, then the ASR model [spec] (by default
 * the Japanese ReazonSpeech Zipformer, int8) transcribes each one. Everything after the VAD
 * probabilities is the shared [transcribeSpeech], as in the Android app, so with the same
 * [spec] and [vadSettings] results here predict on-device accuracy (not speed).
 *
 * Models are read from [modelsDir] in the layout of `tools/harness/download-models.sh`.
 */
class SherpaTranscriber(
    modelsDir: File,
    numThreads: Int = 2,
    val spec: AsrModelSpec = AsrModelSpec.DEFAULT,
    val vadSettings: VadSettings = VadSettings.DEFAULT,
) : Transcriber, AutoCloseable {
    override val engineId = spec.engineId

    private val vadModel = File(modelsDir, AsrModelSpec.VAD_FILE).path
    private val recognizer: OfflineRecognizer

    init {
        fun file(role: AsrModelFile) = File(modelsDir, spec.path(role)).path
        require(File(file(AsrModelFile.TOKENS)).exists()) {
            "ASR model not found in ${File(modelsDir, spec.id)} (run tools/harness/download-models.sh)"
        }
        val model = when (spec.kind) {
            RecognizerKind.TRANSDUCER -> OfflineModelConfig.builder()
                .setTransducer(
                    OfflineTransducerModelConfig.builder()
                        .setEncoder(file(AsrModelFile.ENCODER))
                        .setDecoder(file(AsrModelFile.DECODER))
                        .setJoiner(file(AsrModelFile.JOINER))
                        .build(),
                )
                .setTokens(file(AsrModelFile.TOKENS))
                .setNumThreads(numThreads)
                .build()
        }
        recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder().setOfflineModelConfig(model).setDecodingMethod("greedy_search").build(),
        )
    }

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = withContext(Dispatchers.Default) {
        val samples = concatenateParts(parts) { Ffmpeg.decode(File(it.file.toString())) }
        transcribeSpeech(samples, vadProbabilities(samples), vadSettings, spec.language) { Recognition(recognize(it)) }
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

    /** Silero VAD speech probabilities of the whole input (see [speechProbabilities]). */
    private suspend fun vadProbabilities(samples: FloatArray): FloatArray {
        val config = VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder().setModel(vadModel).setWindowSize(vadSettings.windowSize).build(),
            )
            .setSampleRate(AsrSettings.SAMPLE_RATE)
            .setNumThreads(1)
            .build()
        val vad = Vad(config)
        try {
            return speechProbabilities(samples, vadSettings.windowSize, vad::compute)
        } finally {
            vad.release()
        }
    }

    override fun close() = recognizer.release()
}
