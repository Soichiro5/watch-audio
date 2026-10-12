package app.watchaudio.harness

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.AsrModelSpec
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.LanguageRouting
import app.watchaudio.core.pipeline.SegmentFilter
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.pipeline.VadSettings
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.RecordingFormat
import java.io.File
import kotlinx.io.files.Path

/**
 * Runs each recording of a test set (tools/test-audio) through the device-free pipeline —
 * [WatchSimulator] (chunks in [format], by default the watches' 5 min AAC 24 kbps; silent chunks
 * dropped) → [Ingestor] →
 * [TranscriptionPipeline] with [SherpaTranscriber] — and scores the stored transcript.
 *
 * As a diagnostic it also transcribes every ground-truth utterance on its own (cut from the
 * same coded audio, without VAD), which separates segmentation problems from acoustic ones.
 *
 * Speech is found and cut with [vadSettings] and the results filtered with [segmentFilter] (the
 * app's settings and filter unless overridden). With an [english] model, each span is routed with
 * [routing] as with the app's 「英語も文字起こしする」 (#81). Each recording's stored segments are
 * listed with their signals in `<workDir>/<id>/segments.tsv` ([SignalLog]).
 */
class TestSetEvaluator(
    private val models: File,
    private val workDir: File,
    private val format: RecordingFormat = RecordingFormat.CURRENT,
    private val vadSettings: VadSettings = VadSettings.DEFAULT,
    private val segmentFilter: SegmentFilter = FalsePositiveFilter.DEFAULT,
    private val english: AsrModelSpec? = null,
    private val routing: LanguageRouting = LanguageRouting.DEFAULT,
) {
    private val signals = SignalLog()

    /** Outcome for one recording; times in seconds. */
    data class Result(
        val recording: TestRecording,
        val score: RecordingScore,
        /** CER when each ground-truth utterance is recognized on its own (oracle segmentation). */
        val oracleCer: Double,
        /** WER of the English groups with oracle segmentation, or null without English speech. */
        val oracleWer: Double? = null,
        val chunks: Int,
        val silentChunks: Int,
        val transcribeSeconds: Double,
        /** Median and longest stored segment, i.e. recognizer input (padding included), in seconds. */
        val medianSegmentSeconds: Double,
        val maxSegmentSeconds: Double,
        /** Size of the stored (non-silent) chunk files and the recording time they cover. */
        val audioBytes: Long = 0,
        val audioMs: Long = 0,
        /** Recognition time and kept spans per model, in the pipeline run (see [RecognizerUsage]). */
        val usage: String = "",
    ) {
        val audioSeconds get() = recording.durationMs / 1000.0
        val rtf get() = transcribeSeconds / audioSeconds

        /** Stored audio per hour of recording, in MB (10^6 bytes); null without stored audio. */
        val megabytesPerHour: Double? get() = if (audioMs == 0L) null else audioBytes / 1e6 * 3_600_000 / audioMs
    }

    /**
     * Evaluates the recordings of [manifestFile] (all, or those in [only]) and writes
     * `<workDir>/<id>/report.md` and `<workDir>/<id>/row.md` for each.
     */
    suspend fun evaluate(manifestFile: File, only: Set<String> = emptySet()): List<Result> {
        val manifest = TestSetManifest.load(manifestFile)
        val recordings = manifest.recordings.filter { only.isEmpty() || it.id in only }
        require(recordings.isNotEmpty()) { "no recordings $only in $manifestFile" }
        return SherpaTranscriber(models, vadSettings = vadSettings, signalLog = signals, english = english, routing = routing).use { transcriber ->
            recordings.map { recording ->
                evaluate(File(manifestFile.absoluteFile.parentFile, recording.audio), recording, transcriber).also {
                    File(workDir, "${recording.id}/row.md").writeText(markdownRow(it))
                    println(
                        "%s: CER %.1f%% (oracle segmentation %.1f%%), WER %s (oracle segmentation %s), language errors %s, missed %d/%d, segments median %.1fs max %.1fs, %d chunks (%d silent), audio %d bytes / %d ms (%.2f MB/h), ASR %.0fs (RTF %.3f; %s)".format(
                            recording.id, it.score.cer * 100, it.oracleCer * 100, percent(it.score.wer), percent(it.oracleWer), languageErrors(it.score),
                            it.score.missed, it.score.utterances,
                            it.medianSegmentSeconds, it.maxSegmentSeconds, it.chunks, it.silentChunks,
                            it.audioBytes, it.audioMs, it.megabytesPerHour ?: 0.0, it.transcribeSeconds, it.rtf, it.usage,
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
        val chunks = WatchSimulator(outbox, format).record(samples, start, sessionId = recording.id)
        val oracle = oracleHypotheses(samples, recording, transcriber, dir)
        return LocalStore.open(File(dir, LocalStore.FILE_NAME).path).use { store ->
            val audioRoot = Path(File(dir, "audio").path)
            val ingestor = Ingestor(store, audioRoot)
            chunks.forEach { check(ingestor.ingest(it.manifest, it.file).status == AckStatus.STORED) }
            outbox.deleteRecursively()
            signals.clear()
            signals.chunkStarts = store.read { chunksBetween(0, Long.MAX_VALUE) }
                .mapNotNull { c -> c.audioPath?.let { Path(audioRoot, it).toString() to c.startedAt } }.toMap()

            transcriber.usage.clear()
            val started = System.nanoTime()
            val run = TranscriptionPipeline(store, audioRoot, transcriber, segmentFilter = segmentFilter).processPending()
            val transcribeSeconds = (System.nanoTime() - started) / 1e9
            val usage = transcriber.usage.toString()
            check(run.failed == 0) { "${run.failed} chunks failed to transcribe" }

            val stored = store.read { segmentsBetween(0, Long.MAX_VALUE) }
            val hypotheses = stored.map { Hypothesis(it.startAt - start, it.endAt - start, it.text, it.lang) }
            val silent = store.read { chunksBetween(0, Long.MAX_VALUE) }.count { it.state == ChunkState.NO_AUDIO }
            val score = scoreRecording(recording.segments, hypotheses)
            val oracleScore = scoreRecording(recording.segments, oracle)
            File(dir, "report.md").writeText(detailReport(recording, score, oracle))
            val outside = score.outside.toSet()
            File(dir, "segments.tsv").writeText(
                SignalLog.TSV_HEADER + "\n" + stored.zip(hypotheses).joinToString("") { (segment, h) ->
                    SignalLog.tsvLine(h, signals.find(segment.startAt, segment.endAt, segment.text), h in outside) + "\n"
                },
            )
            val lengths = hypotheses.map { (it.endMs - it.startMs) / 1000.0 }.sorted().ifEmpty { listOf(0.0) }
            val withAudio = chunks.mapNotNull { c -> c.manifest.audio?.let { it.sizeBytes to c.manifest.durationMs } }
            Result(
                recording, score, oracleScore.cer, oracleScore.wer, chunks.size, silent, transcribeSeconds, lengths[lengths.size / 2], lengths.last(),
                audioBytes = withAudio.sumOf { it.first }, audioMs = withAudio.sumOf { it.second }, usage = usage,
            )
        }
    }

    /** Each ground-truth utterance (± the pipeline's segment padding) recognized alone. */
    private fun oracleHypotheses(samples: FloatArray, recording: TestRecording, transcriber: SherpaTranscriber, dir: File): List<Hypothesis> {
        val coded = File(dir, "oracle.${format.codec.fileExtension}")
        Ffmpeg.encode(samples, coded, format)
        val audio = Ffmpeg.decode(coded)
        coded.delete()
        val perMs = AsrSettings.SAMPLE_RATE / 1000
        val padMs = (vadSettings.segmentPadSeconds * 1000).toLong()
        return recording.segments.map { s ->
            val from = ((s.startMs - padMs) * perMs).toInt().coerceIn(0, audio.size)
            val to = ((s.endMs + padMs) * perMs).toInt().coerceIn(from, audio.size)
            val recognition = transcriber.recognition(audio.copyOfRange(from, to))
            Hypothesis(s.startMs, s.endMs, recognition.text, recognition.lang ?: transcriber.spec.language)
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

        private val OTHERS = setOf("partner", "phone")

        const val TABLE_HEADER =
            "| 条件 | 音声 (分) | 発話数 | CER 全体 | CER 装着者 | CER 相手 | CER（正解区間で切った場合） | WER 英語（正解区間で切った場合） | 言語の誤り (発話) | 検出漏れ | 発話外の誤認識 (文字) | チャンク (無音) | 文字起こし時間 (s) | RTF |\n" +
                "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"

        /** One Markdown table row (Japanese headers in [TABLE_HEADER], for docs/dev/test-audio.md). */
        fun markdownRow(r: Result): String {
            val s = r.score
            val wer = s.wer?.let { "${percent(it)} (${percent(r.oracleWer)})" } ?: "-"
            return "| %s | %.1f | %d | %s | %s | %s | %s | %s | %s | %s (%d) | %d | %d (%d) | %.0f | %.3f |\n".format(
                r.recording.id, r.audioSeconds / 60, s.utterances, percent(s.cerOrNull), percent(s.cerOf(setOf("wearer"))),
                percent(s.cerOf(OTHERS)), percent(r.oracleCer.takeIf { s.refChars > 0 }), wer, languageErrors(s),
                percent(s.missRate), s.missed, s.outsideChars, r.chunks, r.silentChunks, r.transcribeSeconds, r.rtf,
            )
        }

        /**
         * Utterances stored in another language, per spoken language (`ja 2/480, en 1/60`), or `-`
         * when the transcriber states no language.
         */
        fun languageErrors(s: RecordingScore): String =
            s.languageChecked.entries.joinToString(", ") { (lang, n) -> "$lang ${s.languageErrors[lang] ?: 0}/$n" }.ifEmpty { "-" }

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
