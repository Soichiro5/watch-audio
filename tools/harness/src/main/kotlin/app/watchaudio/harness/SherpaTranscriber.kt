package app.watchaudio.harness

import app.watchaudio.core.pipeline.AsrModelFile
import app.watchaudio.core.pipeline.AsrModelSpec
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.LanguageRouting
import app.watchaudio.core.pipeline.Recognition
import app.watchaudio.core.pipeline.RecognizerKind
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import app.watchaudio.core.pipeline.VadSettings
import app.watchaudio.core.pipeline.concatenateParts
import app.watchaudio.core.pipeline.senseVoiceLanguage
import app.watchaudio.core.pipeline.speechProbabilities
import app.watchaudio.core.pipeline.transcribeSpeech
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * sherpa-onnx on the JVM: Silero VAD finds the utterances, then the ASR model [spec] (by default
 * the Japanese ReazonSpeech Zipformer, int8) transcribes each one. With an [english] model
 * (SenseVoice, #81) each utterance is first recognized by it and routed with [routing], as in the
 * Android app with 「英語も文字起こしする」 on. Everything after the VAD probabilities is the shared
 * [transcribeSpeech], as in the Android app, so with the same [spec], [english] and [vadSettings]
 * results here predict on-device accuracy (not speed).
 *
 * Models are read from [modelsDir] in the layout of `tools/harness/download-models.sh`.
 */
class SherpaTranscriber(
    modelsDir: File,
    numThreads: Int = 2,
    val spec: AsrModelSpec = AsrModelSpec.DEFAULT,
    val vadSettings: VadSettings = VadSettings.DEFAULT,
    /** Hears every result with its audio, for analysis (see [SignalLog]). */
    private val signalLog: SignalLog? = null,
    /** The multilingual model routed in front of [spec] for English, or null for Japanese only. */
    val english: AsrModelSpec? = null,
    val routing: LanguageRouting = LanguageRouting.DEFAULT,
) : Transcriber, AutoCloseable {
    override val engineId = english?.let { LanguageRouting.engineId(spec, it) } ?: spec.engineId

    private val vadModel = File(modelsDir, AsrModelSpec.VAD_FILE).path
    private val recognizer = createRecognizer(modelsDir, spec, numThreads)
    private val detector = english?.let { it to createRecognizer(modelsDir, it, numThreads) }

    /** Time spent in each recognizer (by engine id) and spans each one's text was kept for. */
    val usage = RecognizerUsage()

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = withContext(Dispatchers.Default) {
        val samples = concatenateParts(parts) { Ffmpeg.decode(File(it.file.toString())) }
        val probabilities = vadProbabilities(samples)
        transcribeSpeech(samples, probabilities, vadSettings, spec.language, ::recognition)
            .also { signalLog?.record(parts, probabilities, vadSettings.windowSize, it) }
    }

    /** Recognizes [samples] (16 kHz) as one utterance, without VAD, routed like [transcribe]. */
    fun recognition(samples: FloatArray): Recognition {
        val (detectorSpec, detectorRecognizer) = detector ?: return decode(recognizer, spec, samples).also { usage.kept(spec) }
        val detected = decode(detectorRecognizer, detectorSpec, samples)
        if (routing.keeps(detected)) return detected.also { usage.kept(detectorSpec) }
        return decode(recognizer, spec, samples).also { usage.kept(spec) }
    }

    /** The text of [recognition]. */
    fun recognize(samples: FloatArray): String = recognition(samples).text

    private fun decode(recognizer: OfflineRecognizer, spec: AsrModelSpec, samples: FloatArray): Recognition {
        val started = System.nanoTime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrSettings.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return when (spec.kind) {
                RecognizerKind.TRANSDUCER -> Recognition(result.text.trim())
                RecognizerKind.SENSE_VOICE -> Recognition(result.text.trim(), lang = senseVoiceLanguage(result.lang))
            }
        } finally {
            stream.release()
            usage.spent(spec, (System.nanoTime() - started) / 1e9)
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

    override fun close() {
        recognizer.release()
        detector?.second?.release()
    }

    companion object {
        /** Whether the files of [spec] are in [modelsDir]. */
        fun hasModel(modelsDir: File, spec: AsrModelSpec): Boolean =
            spec.kind.files.all { File(modelsDir, spec.path(it)).isFile }

        private fun createRecognizer(modelsDir: File, spec: AsrModelSpec, numThreads: Int): OfflineRecognizer {
            fun file(role: AsrModelFile) = File(modelsDir, spec.path(role)).path
            require(hasModel(modelsDir, spec)) {
                "ASR model not found in ${File(modelsDir, spec.id)} (run tools/harness/download-models.sh)"
            }
            val builder = OfflineModelConfig.builder().setTokens(file(AsrModelFile.TOKENS)).setNumThreads(numThreads)
            when (spec.kind) {
                RecognizerKind.TRANSDUCER -> builder.setTransducer(
                    OfflineTransducerModelConfig.builder()
                        .setEncoder(file(AsrModelFile.ENCODER))
                        .setDecoder(file(AsrModelFile.DECODER))
                        .setJoiner(file(AsrModelFile.JOINER))
                        .build(),
                )
                RecognizerKind.SENSE_VOICE -> builder.setSenseVoice(
                    // Empty language: detect it; inverse text normalization: punctuation and casing.
                    OfflineSenseVoiceModelConfig.builder()
                        .setModel(file(AsrModelFile.MODEL))
                        .setLanguage("")
                        .setInverseTextNormalization(true)
                        .build(),
                )
            }
            return OfflineRecognizer(
                OfflineRecognizerConfig.builder().setOfflineModelConfig(builder.build()).setDecodingMethod("greedy_search").build(),
            )
        }
    }
}

/** Where a [SherpaTranscriber]'s recognition time went, per engine id (seconds, spans kept). */
class RecognizerUsage {
    val seconds = linkedMapOf<String, Double>()
    val keptSpans = linkedMapOf<String, Int>()

    internal fun spent(spec: AsrModelSpec, value: Double) = seconds.merge(spec.engineId, value, Double::plus)

    internal fun kept(spec: AsrModelSpec) = keptSpans.merge(spec.engineId, 1, Int::plus)

    fun clear() {
        seconds.clear()
        keptSpans.clear()
    }

    override fun toString() = seconds.keys.union(keptSpans.keys).joinToString(", ") {
        "%s %.1fs (%d spans kept)".format(it, seconds[it] ?: 0.0, keptSpans[it] ?: 0)
    }
}
