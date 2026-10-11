package app.watchaudio.android.asr

import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.SpeechSpan
import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
import app.watchaudio.core.pipeline.concatenateParts
import app.watchaudio.core.pipeline.padSpeechSpans
import app.watchaudio.core.pipeline.speechSegments
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
 * On-device transcription with sherpa-onnx: Silero VAD splits the chunk into utterances and the
 * Japanese ReazonSpeech Zipformer (int8) transcribes each one. Multi-part input (a chunk joined
 * with its neighbours' boundary audio) is decoded file by file and concatenated. Mirrors the
 * JVM harness's `SherpaTranscriber` (same models and [AsrSettings]), so harness results predict
 * this one.
 *
 * The recognizer is loaded on first use and reused across chunks until [release].
 */
class AndroidSherpaTranscriber(private val models: ModelFiles, private val numThreads: Int = 2) : Transcriber {
    override val engineId = AsrSettings.JA_ENGINE_ID

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null

    override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!models.isComplete()) throw ModelNotReadyException()
            val context = coroutineContext
            val samples = concatenateParts(parts) { AudioDecoder.decode(File(it.file.toString())) { context.ensureActive() } }
            val recognizer = recognizer ?: createRecognizer().also { recognizer = it }
            speechSpans(samples).mapNotNull { span ->
                ensureActive()
                recognize(recognizer, span)
            }
        }
    }

    /** Frees the native recognizer (the int8 encoder alone is ~150 MB); the next [transcribe] reloads it. */
    suspend fun release() = mutex.withLock {
        recognizer?.release()
        recognizer = null
    }

    private fun recognize(recognizer: OfflineRecognizer, span: SpeechSpan): TimedText? {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(span.samples, AsrSettings.SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            return if (text.isEmpty()) null else TimedText(span.startMs, span.endMs, text, lang = "ja")
        } finally {
            stream.release()
        }
    }

    private fun createRecognizer() = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.encoder.path,
                    decoder = models.decoder.path,
                    joiner = models.joiner.path,
                ),
                tokens = models.tokens.path,
                numThreads = numThreads,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    /**
     * Runs Silero VAD over the whole input, finds speech from its probabilities
     * ([speechSegments]) and cuts the recognizer inputs ([padSpeechSpans]).
     */
    private suspend fun speechSpans(samples: FloatArray): List<SpeechSpan> {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = models.vad.path, windowSize = AsrSettings.VAD_WINDOW_SIZE),
                sampleRate = AsrSettings.SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        val window = AsrSettings.VAD_WINDOW_SIZE
        val probabilities = FloatArray(samples.size / window)
        try {
            for (index in probabilities.indices) {
                if (index % 1024 == 0) coroutineContext.ensureActive()
                probabilities[index] = vad.compute(samples.copyOfRange(index * window, (index + 1) * window))
            }
        } finally {
            vad.release()
        }
        return padSpeechSpans(speechSegments(probabilities), samples)
    }
}
