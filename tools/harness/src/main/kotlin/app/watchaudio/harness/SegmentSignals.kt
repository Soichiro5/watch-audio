package app.watchaudio.harness

import app.watchaudio.core.pipeline.AudioPart
import app.watchaudio.core.pipeline.TimedText
import java.io.File

/**
 * The signals of one recognized utterance that a [app.watchaudio.core.pipeline.SegmentFilter] may
 * use, plus [maxProbability] as a diagnostic, for analysing false positives (#105).
 */
data class SegmentSignals(
    val durationMs: Long,
    /** [TimedText.speechProbability]. */
    val meanProbability: Float?,
    /** Highest Silero probability over the utterance's windows. */
    val maxProbability: Float,
    /** [TimedText.levelAboveNoiseDb]. */
    val levelAboveNoiseDb: Float?,
)

/**
 * Collects [SegmentSignals] of everything [SherpaTranscriber] returns, keyed by absolute time and
 * text, so that they can be joined with the stored segments afterwards. [chunkStarts] maps an
 * archived audio file's path to its chunk's start (epoch ms); set it before each recording.
 */
class SignalLog {
    var chunkStarts: Map<String, Long> = emptyMap()
    private val records = mutableMapOf<Triple<Long, Long, String>, SegmentSignals>()

    fun clear() = records.clear()

    /** Signals of the stored segment `[startAt, endAt)` with [text], if this log saw it. */
    fun find(startAt: Long, endAt: Long, text: String): SegmentSignals? = records[Triple(startAt, endAt, text)]

    /** Records [results] of one transcription of [parts], whose Silero [probabilities] are per [windowSize] samples. */
    fun record(parts: List<AudioPart>, probabilities: FloatArray, windowSize: Int, results: List<TimedText>) {
        val samplesPerMs = SAMPLE_RATE / 1000
        for (r in results) {
            val firstWindow = (r.startMs * samplesPerMs / windowSize).toInt()
            val endWindow = minOf(probabilities.size.toLong(), (r.endMs * samplesPerMs + windowSize - 1) / windowSize).toInt()
            val maxProbability = (firstWindow until endWindow).maxOfOrNull { probabilities[it] } ?: 0f
            val startAt = absolute(parts, r.startMs) ?: continue
            val endAt = maxOf(startAt, absolute(parts, r.endMs) ?: continue)
            records[Triple(startAt, endAt, r.text)] =
                SegmentSignals(r.endMs - r.startMs, r.speechProbability, maxProbability, r.levelAboveNoiseDb)
        }
    }

    /** Same mapping as `JoinedTranscription.assign`: offset into the concatenation → epoch ms. */
    private fun absolute(parts: List<AudioPart>, offsetMs: Long): Long? {
        var partStart = 0L
        for ((index, part) in parts.withIndex()) {
            if (offsetMs < partStart + part.durationMs || index == parts.lastIndex) {
                val chunkStart = chunkStarts[part.file.toString()] ?: return null
                return chunkStart + part.fromMs + (offsetMs - partStart)
            }
            partStart += part.durationMs
        }
        return null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000L

        const val TSV_HEADER = "startMs\tendMs\tdurationMs\tchars\tmeanProb\tmaxProb\tlevelDb\toutside\ttext"

        /** One line of `segments.tsv` (see [TSV_HEADER]); times relative to the recording. */
        fun tsvLine(h: Hypothesis, signals: SegmentSignals?, outside: Boolean): String = listOf(
            h.startMs, h.endMs, h.endMs - h.startMs, CerText.normalize(h.text).length,
            signals?.meanProbability?.let { "%.3f".format(it) } ?: "",
            signals?.maxProbability?.let { "%.3f".format(it) } ?: "",
            signals?.levelAboveNoiseDb?.let { "%.1f".format(it) } ?: "",
            if (outside) 1 else 0, h.text.replace('\t', ' '),
        ).joinToString("\t")

        /** Reads a `segments.tsv` written by [TestSetEvaluator]; signals are null where unknown. */
        fun readTsv(file: File): List<Pair<Hypothesis, SegmentSignals?>> =
            file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t')
                val h = Hypothesis(f[0].toLong(), f[1].toLong(), f[8])
                val signals = f[5].toFloatOrNull()?.let { max ->
                    SegmentSignals(f[2].toLong(), f[4].toFloatOrNull(), max, f[6].toFloatOrNull())
                }
                h to signals
            }
    }
}
