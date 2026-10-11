package app.watchaudio.android.asr

import app.watchaudio.core.pipeline.AsrModelFile
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
 * predict this one; only creating the VAD and the recognizer is Android specific.
 *
 * The recognizer is loaded on first use and reused across chunks until [release].
 */
class AndroidSherpaTranscriber(
    private val models: ModelFiles,
    private val numThreads: Int = 2,
    private val vadSettings: VadSettings = VadSettings.DEFAULT,
) : Transcriber {
    override val engineId = models.spec.engineId

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!models.isComplete()) throw ModelNotReadyException()
            val context = coroutineContext
            val samples = concatenateParts(parts) { AudioDecoder.decode(File(it.file.toString())) { context.ensureActive() } }
            val recognizer = recognizer ?: createRecognizer().also { recognizer = it }
            transcribeSpeech(samples, vadProbabilities(samples), vadSettings, models.spec.language) { recognize(recognizer, it) }
        }
    }

    /** Frees the native recognizer (the int8 encoder alone is ~150 MB); the next [transcribe] reloads it. */
    suspend fun release() = mutex.withLock {
        recognizer?.release()
        recognizer = null
    }

    private fun recognize(recognizer: OfflineRecognizer, samples: FloatArray): Recognition {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrSettings.SAMPLE_RATE)
            recognizer.decode(stream)
            return Recognition(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val modelConfig = when (models.spec.kind) {
            RecognizerKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.asr(AsrModelFile.ENCODER).path,
                    decoder = models.asr(AsrModelFile.DECODER).path,
                    joiner = models.asr(AsrModelFile.JOINER).path,
                ),
                tokens = models.asr(AsrModelFile.TOKENS).path,
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
