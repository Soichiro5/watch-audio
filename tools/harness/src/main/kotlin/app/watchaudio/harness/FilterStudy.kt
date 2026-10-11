package app.watchaudio.harness

import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.TimedText
import java.io.File

/**
 * Compares drop rules for false positives (#105) without running the ASR again: re-scores the
 * stored segments of an `evaluate --filter off` run (`<workDir>/<id>/segments.tsv`) with each
 * candidate rule and prints, per recording, the CER (and its change), the characters recognized
 * outside any conversation and the number of segments dropped.
 *
 * Dropping a segment after the fact matches the pipeline's filter except at chunk boundaries,
 * where the pipeline filters before re-transcribed neighbours are replaced; measure the chosen
 * filter with `evaluate` itself.
 */
object FilterStudy {
    private class Candidate(val name: String, val drop: (Hypothesis, SegmentSignals) -> Boolean)

    private fun chars(h: Hypothesis) = FalsePositiveFilter.textLength(h.text)

    private fun mean(s: SegmentSignals) = s.meanProbability ?: 1f

    private fun level(s: SegmentSignals) = s.levelAboveNoiseDb ?: Float.POSITIVE_INFINITY

    private fun drops(filter: FalsePositiveFilter) = { h: Hypothesis, s: SegmentSignals ->
        !filter.keep(TimedText(h.startMs, h.endMs, h.text, speechProbability = s.meanProbability, levelAboveNoiseDb = s.levelAboveNoiseDb))
    }

    private val candidates: List<Candidate> = buildList {
        add(Candidate("keep all") { _, _ -> false })
        // Short spans with few characters (spans include 0.3 s of padding on each side).
        for ((n, ms) in listOf(1 to 1000, 2 to 700, 2 to 1000, 2 to 1500)) {
            add(Candidate("chars<=$n & duration<${ms}ms") { h, s -> chars(h) <= n && s.durationMs < ms })
        }
        // Few characters and a low mean / peak Silero probability.
        for ((n, p) in listOf(1 to 0.2f, 1 to 0.3f, 2 to 0.15f, 2 to 0.2f, 2 to 0.3f, 3 to 0.2f)) {
            add(Candidate("chars<=$n & meanP<$p") { h, s -> chars(h) <= n && mean(s) < p })
        }
        add(Candidate("chars<=2 & maxP<0.7") { h, s -> chars(h) <= 2 && s.maxProbability < 0.7f })
        add(Candidate("meanP<0.2") { _, s -> mean(s) < 0.2f })
        // Distance from the noise floor.
        for (db in listOf(5f, 6f, 7f, 8f, 10f)) add(Candidate("level<${db.toInt()}dB") { _, s -> level(s) < db })
        add(Candidate("chars<=2 & level<7dB") { h, s -> chars(h) <= 2 && level(s) < 7f })
        // Both.
        add(Candidate("level<7dB or chars<=2 & meanP<0.15") { h, s -> level(s) < 7f || chars(h) <= 2 && mean(s) < 0.15f })
        add(Candidate("level<7dB or chars<=1 & meanP<0.2 (FalsePositiveFilter.DEFAULT)", drops(FalsePositiveFilter.DEFAULT)))
    }

    fun run(manifestFile: File, workDir: File, only: Set<String>) {
        val recordings = TestSetManifest.load(manifestFile).recordings
            .filter { (only.isEmpty() || it.id in only) && File(workDir, "${it.id}/segments.tsv").exists() }
        require(recordings.isNotEmpty()) { "no segments.tsv in $workDir (run evaluate --filter off first)" }
        val results = recordings.map { recording ->
            val segments = SignalLog.readTsv(File(workDir, "${recording.id}/segments.tsv"))
            candidates.map { c ->
                val kept = segments.filterNot { (h, s) -> s != null && c.drop(h, s) }.map { it.first }
                val score = scoreRecording(recording.segments, kept)
                Triple(score.cer, score.outsideChars, segments.size - kept.size)
            }
        }
        println("| rule | " + recordings.joinToString(" | ") { "${it.id}: CER (change) / outside chars / dropped" } + " |")
        println("| --- |" + " --- |".repeat(recordings.size))
        candidates.forEachIndexed { i, c ->
            val cells = results.map { rows ->
                val (cer, outside, dropped) = rows[i]
                "%.1f%% (%+.2f) / %d / %d".format(cer * 100, (cer - rows[0].first) * 100, outside, dropped)
            }
            println("| ${c.name} | " + cells.joinToString(" | ") + " |")
        }
    }
}
