package app.watchaudio.harness

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.protocol.AckStatus
import java.io.File
import kotlinx.io.files.Path

/**
 * Runs each recording of a test set (tools/test-audio) through the device-free pipeline —
 * [WatchSimulator] (5 min AAC 24 kbps chunks, silent chunks dropped) → [Ingestor] →
 * [TranscriptionPipeline] with [SherpaTranscriber] — and scores the stored transcript.
 *
 * As a diagnostic it also transcribes every ground-truth utterance on its own (cut from the
 * same AAC-coded audio, without VAD), which separates segmentation problems from acoustic ones.
 */
class TestSetEvaluator(private val models: File, private val workDir: File, private val chunkSeconds: Int = 300) {

    /** Outcome for one recording; times in seconds. */
    data class Result(
        val recording: TestRecording,
        val score: RecordingScore,
        /** CER when each ground-truth utterance is recognized on its own (oracle segmentation). */
        val oracleCer: Double,
        val chunks: Int,
        val silentChunks: Int,
        val transcribeSeconds: Double,
    ) {
        val audioSeconds get() = recording.durationMs / 1000.0
        val rtf get() = transcribeSeconds / audioSeconds
    }

    /**
     * Evaluates the recordings of [manifestFile] (all, or those in [only]) and writes
     * `<workDir>/<id>/report.md` and `<workDir>/<id>/row.md` for each.
     */
    suspend fun evaluate(manifestFile: File, only: Set<String> = emptySet()): List<Result> {
        val manifest = TestSetManifest.load(manifestFile)
        val recordings = manifest.recordings.filter { only.isEmpty() || it.id in only }
        require(recordings.isNotEmpty()) { "no recordings $only in $manifestFile" }
        return SherpaTranscriber(models).use { transcriber ->
            recordings.map { recording ->
                evaluate(File(manifestFile.absoluteFile.parentFile, recording.audio), recording, transcriber).also {
                    File(workDir, "${recording.id}/row.md").writeText(markdownRow(it))
                    println(
                        "%s: CER %.1f%% (oracle segmentation %.1f%%), missed %d/%d, %d chunks (%d silent), ASR %.0fs (RTF %.3f)".format(
                            recording.id, it.score.cer * 100, it.oracleCer * 100, it.score.missed, it.score.utterances,
                            it.chunks, it.silentChunks, it.transcribeSeconds, it.rtf,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun evaluate(audio: File, recording: TestRecording, transcriber: SherpaTranscriber): Result {
        val dir = File(workDir, recording.id).apply { deleteRecursively(); mkdirs() }
        val start = START_EPOCH_MS
        val samples = Ffmpeg.decode(audio)
        val outbox = File(dir, "outbox")
        val chunks = WatchSimulator(outbox, chunkSeconds = chunkSeconds).record(samples, start, sessionId = recording.id)
        val oracle = oracleHypotheses(samples, recording, transcriber, dir)
        return LocalStore.open(File(dir, LocalStore.FILE_NAME).path).use { store ->
            val audioRoot = Path(File(dir, "audio").path)
            val ingestor = Ingestor(store, audioRoot)
            chunks.forEach { check(ingestor.ingest(it.manifest, it.file).status == AckStatus.STORED) }
            outbox.deleteRecursively()

            val started = System.nanoTime()
            val run = TranscriptionPipeline(store, audioRoot, transcriber).processPending()
            val transcribeSeconds = (System.nanoTime() - started) / 1e9
            check(run.failed == 0) { "${run.failed} chunks failed to transcribe" }

            val hypotheses = store.read { segmentsBetween(0, Long.MAX_VALUE) }
                .map { Hypothesis(it.startAt - start, it.endAt - start, it.text) }
            val silent = store.read { chunksBetween(0, Long.MAX_VALUE) }.count { it.state == ChunkState.NO_AUDIO }
            val score = scoreRecording(recording.segments, hypotheses)
            val oracleScore = scoreRecording(recording.segments, oracle)
            File(dir, "report.md").writeText(detailReport(recording, score, oracle))
            Result(recording, score, oracleScore.cer, chunks.size, silent, transcribeSeconds)
        }
    }

    /** Each ground-truth utterance (± the pipeline's segment padding) recognized alone. */
    private fun oracleHypotheses(samples: FloatArray, recording: TestRecording, transcriber: SherpaTranscriber, dir: File): List<Hypothesis> {
        val coded = File(dir, "oracle.m4a")
        Ffmpeg.encodeAac(samples, coded, BITRATE_BPS)
        val audio = Ffmpeg.decode(coded)
        coded.delete()
        val perMs = AsrSettings.SAMPLE_RATE / 1000
        val padMs = (AsrSettings.SEGMENT_PAD_SECONDS * 1000).toLong()
        return recording.segments.map { s ->
            val from = ((s.startMs - padMs) * perMs).toInt().coerceIn(0, audio.size)
            val to = ((s.endMs + padMs) * perMs).toInt().coerceIn(from, audio.size)
            Hypothesis(s.startMs, s.endMs, transcriber.recognize(audio.copyOfRange(from, to)))
        }
    }

    private fun detailReport(recording: TestRecording, score: RecordingScore, oracle: List<Hypothesis>) = buildString {
        appendLine("# ${recording.id}: ${recording.description}")
        appendLine()
        score.groups.forEach { g ->
            appendLine("## ${g.id} (errors ${g.errors})")
            appendLine("- REF: ${g.reference}")
            appendLine("- HYP: ${g.hypothesis}")
            appendLine("- ORACLE: ${oracle.zip(recording.segments).filter { it.second.group == g.id }.joinToString(" ") { it.first.text }}")
        }
        appendLine()
        appendLine("## outside any conversation (${score.outsideChars} chars)")
        score.outside.forEach { appendLine("- %.1fs-%.1fs %s".format(it.startMs / 1000.0, it.endMs / 1000.0, it.text)) }
    }

    companion object {
        const val START_EPOCH_MS = 1_791_622_800_000L
        private const val BITRATE_BPS = 24_000

        private val OTHERS = setOf("partner", "phone")

        const val TABLE_HEADER =
            "| 条件 | 音声 (分) | 発話数 | CER 全体 | CER 装着者 | CER 相手 | CER（正解区間で切った場合） | 検出漏れ | 発話外の誤認識 (文字) | チャンク (無音) | 文字起こし時間 (s) | RTF |\n" +
                "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"

        /** One Markdown table row (Japanese headers in [TABLE_HEADER], for docs/test-audio.md). */
        fun markdownRow(r: Result): String {
            val s = r.score
            return "| %s | %.1f | %d | %s | %s | %s | %s | %s (%d) | %d | %d (%d) | %.0f | %.3f |\n".format(
                r.recording.id, r.audioSeconds / 60, s.utterances, percent(s.cer), percent(s.cerOf(setOf("wearer"))),
                percent(s.cerOf(OTHERS)), percent(r.oracleCer), percent(s.missRate), s.missed, s.outsideChars,
                r.chunks, r.silentChunks, r.transcribeSeconds, r.rtf,
            )
        }

        /**
         * The table over every recording of [manifestFile] evaluated so far in [workDir] (so that
         * recordings can be evaluated in separate runs), in manifest order.
         */
        fun markdownTable(manifestFile: File, workDir: File): String =
            TABLE_HEADER + TestSetManifest.load(manifestFile).recordings
                .mapNotNull { File(workDir, "${it.id}/row.md").takeIf(File::exists)?.readText() }
                .joinToString("")

        private fun percent(value: Double?) = if (value == null) "-" else "%.1f%%".format(value * 100)
    }
}
