package app.watchaudio.harness

import app.watchaudio.core.pipeline.TimedText
import app.watchaudio.core.pipeline.Transcriber
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
import kotlinx.io.files.Path

/**
 * sherpa-onnx on the JVM: Silero VAD splits the chunk into utterances, then the Japanese
 * ReazonSpeech Zipformer (int8) transcribes each one. Same models and parameters as the
 * Android app, so results here predict on-device accuracy (not speed).
 */
class SherpaTranscriber(modelsDir: File, numThreads: Int = 2) : Transcriber, AutoCloseable {
    override val engineId = "sherpa-zipformer-ja-reazonspeech-int8"

    private val vadModel = File(modelsDir, "silero_vad.onnx").path
    private val numThreads = numThreads
    private val recognizer: OfflineRecognizer

    init {
        val dir = File(modelsDir, MODEL_NAME)
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

    override suspend fun transcribe(audioFile: Path): List<TimedText> = withContext(Dispatchers.Default) {
        val samples = Ffmpeg.decode(File(audioFile.toString()))
        speechSegments(samples).mapNotNull { (start, segment) ->
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(segment, Ffmpeg.SAMPLE_RATE)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    TimedText(
                        startMs = start * 1000L / Ffmpeg.SAMPLE_RATE,
                        endMs = (start + segment.size) * 1000L / Ffmpeg.SAMPLE_RATE,
                        text = text,
                        lang = "ja",
                    )
                }
            } finally {
                stream.release()
            }
        }
    }

    /** Runs Silero VAD over the whole chunk; returns (start sample, samples) per utterance. */
    private fun speechSegments(samples: FloatArray): List<Pair<Int, FloatArray>> {
        val config = VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel(vadModel)
                    // Tuned in the M0-5 spike: 0.3 + 0.3 s padding detected 23/23 utterances (0.5: 22/23)
                    // and lowered CER; onsets are otherwise clipped.
                    .setThreshold(VAD_THRESHOLD)
                    .setMinSilenceDuration(0.5f)
                    .setMinSpeechDuration(0.25f)
                    .setMaxSpeechDuration(20f)
                    .build(),
            )
            .setSampleRate(Ffmpeg.SAMPLE_RATE)
            .setNumThreads(1)
            .build()
        val vad = Vad(config)
        val segments = mutableListOf<Pair<Int, FloatArray>>()
        fun drain() {
            while (!vad.empty()) {
                val front = vad.front()
                segments += front.start to front.samples
                vad.pop()
            }
        }
        try {
            val window = 512
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + window, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))
                drain()
                offset = end
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return pad(segments, samples)
    }

    /** Re-cuts each segment from the input with [PAD_SECONDS] of context, merging overlaps. */
    private fun pad(segments: List<Pair<Int, FloatArray>>, samples: FloatArray): List<Pair<Int, FloatArray>> {
        val pad = (PAD_SECONDS * Ffmpeg.SAMPLE_RATE).toInt()
        val ranges = mutableListOf<IntRange>()
        for ((start, segment) in segments) {
            val from = maxOf(0, start - pad)
            val to = minOf(samples.size, start + segment.size + pad)
            val last = ranges.lastOrNull()
            if (last != null && from <= last.last) ranges[ranges.lastIndex] = last.first..maxOf(last.last, to) else ranges += from..to
        }
        return ranges.map { it.first to samples.copyOfRange(it.first, it.last) }
    }

    override fun close() = recognizer.release()

    companion object {
        const val MODEL_NAME = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
        const val VAD_THRESHOLD = 0.3f
        const val PAD_SECONDS = 0.3f
    }
}
