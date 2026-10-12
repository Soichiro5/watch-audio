package app.watchaudio.android.asr

import app.watchaudio.core.pipeline.AsrModelFile
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The speech models are not on the device yet; see [ModelManager]. */
class ModelNotReadyException : IllegalStateException("speech model is not downloaded")

/**
 * On-device transcription with sherpa-onnx: Silero VAD finds the utterances and the ASR model of
 * [ModelFiles.spec] (the Japanese ReazonSpeech Zipformer, int8) transcribes each one. Multi-part
 * input (a chunk joined with its neighbours' boundary audio) is decoded file by file and
 * concatenated. Everything after the VAD probabilities is the shared [transcribeSpeech], also used
 * by the JVM harness's `SherpaTranscriber` (same models and [VadSettings]), so harness results
 * predict this one; only creating the VAD and the recognizers is Android specific.
 *
 * While [englishEnabled] (「英語も文字起こしする」, #81) and the [english] model is present, each
 * utterance is first recognized by it and routed with [routing]: English text is kept, everything
 * else goes to the Japanese model. [engineId] then names both models.
 *
 * Recognizers are loaded on first use and reused across chunks until [release]; the English one
 * is released as soon as a run no longer uses it.
 */
class AndroidSherpaTranscriber(
    private val models: ModelFiles,
    private val english: ModelFiles? = null,
    private val englishEnabled: suspend () -> Boolean = { false },
    private val numThreads: Int = 2,
    private val vadSettings: VadSettings = VadSettings.DEFAULT,
    private val routing: LanguageRouting = LanguageRouting.DEFAULT,
) : Transcriber {
    /** The models of the latest [transcribe] (Japanese alone, or with English routed in front). */
    @Volatile
    override var engineId = models.spec.engineId
        private set

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var englishRecognizer: OfflineRecognizer? = null

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!models.isComplete()) throw ModelNotReadyException()
            val withEnglish = english?.takeIf { it.hasAsr() && englishEnabled() }
            if (withEnglish == null) {
                englishRecognizer?.release()
                englishRecognizer = null
            }
            val context = coroutineContext
            val samples = concatenateParts(parts) { AudioDecoder.decode(File(it.file.toString())) { context.ensureActive() } }
            val recognizer = recognizer ?: createRecognizer(models).also { recognizer = it }
            val detector = withEnglish?.let { englishRecognizer ?: createRecognizer(it).also { r -> englishRecognizer = r } }
            engineId = withEnglish?.let { LanguageRouting.engineId(models.spec, it.spec) } ?: models.spec.engineId
            transcribeSpeech(samples, vadProbabilities(samples), vadSettings, models.spec.language) { span ->
                if (detector == null || withEnglish == null) {
                    recognize(recognizer, models, span)
                } else {
                    routing.route(recognize(detector, withEnglish, span)) { recognize(recognizer, models, span) }
                }
            }
        }
    }

    /** Frees the native recognizers (the int8 encoder alone is ~150 MB); the next [transcribe] reloads them. */
    suspend fun release() = mutex.withLock {
        recognizer?.release()
        recognizer = null
        englishRecognizer?.release()
        englishRecognizer = null
    }

    private fun recognize(recognizer: OfflineRecognizer, files: ModelFiles, samples: FloatArray): Recognition {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrSettings.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return when (files.spec.kind) {
                RecognizerKind.TRANSDUCER -> Recognition(result.text)
                RecognizerKind.SENSE_VOICE -> Recognition(result.text, lang = senseVoiceLanguage(result.lang))
            }
        } finally {
            stream.release()
        }
    }

    private fun createRecognizer(files: ModelFiles): OfflineRecognizer {
        val modelConfig = when (files.spec.kind) {
            RecognizerKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = files.asr(AsrModelFile.ENCODER).path,
                    decoder = files.asr(AsrModelFile.DECODER).path,
                    joiner = files.asr(AsrModelFile.JOINER).path,
                ),
                tokens = files.asr(AsrModelFile.TOKENS).path,
                numThreads = numThreads,
            )
            // Empty language: detect it; inverse text normalization: punctuation and casing.
            RecognizerKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = files.asr(AsrModelFile.MODEL).path,
                    language = "",
                    useInverseTextNormalization = true,
                ),
                tokens = files.asr(AsrModelFile.TOKENS).path,
                numThreads = numThreads,
            )
        }
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(modelConfig = modelConfig, decodingMethod = "greedy_search"),
        )
    }

    /** Silero VAD speech probabilities of the whole input (see [speechProbabilities]). */
    private suspend fun vadProbabilities(samples: FloatArray): FloatArray {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = models.vad.path, windowSize = vadSettings.windowSize),
                sampleRate = AsrSettings.SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        try {
            return speechProbabilities(samples, vadSettings.windowSize, vad::compute)
        } finally {
            vad.release()
        }
    }
}
