package app.watchaudio.core.export

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.platform.IoDispatcher
import app.watchaudio.core.timeline.dayWindow
import app.watchaudio.core.timeline.localDateOf
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Which days to export: from [from] to [to] inclusive; null leaves that end open. */
data class ExportRange(val from: LocalDate? = null, val to: LocalDate? = null) {
    init {
        require(from == null || to == null || from <= to) { "from must not be after to" }
    }

    companion object {
        val ALL = ExportRange()
    }
}

/** What the user asked to export. */
data class ExportRequest(
    val range: ExportRange,
    val includeAudio: Boolean,
    /** Decides which day each chunk belongs to, and the times in the Markdown. */
    val timeZone: TimeZone,
    /** When the export is made (epoch ms): gaps end there. */
    val exportedAt: Long,
)

/** An export with something in it: the days from the first to the last chunk in the range. */
data class ExportPlan(val request: ExportRequest, val firstDay: LocalDate, val lastDay: LocalDate) {
    /** Calendar days from [firstDay] to [lastDay] (days without chunks are skipped when writing). */
    val dayCount: Int get() = firstDay.daysUntil(lastDay) + 1

    /** `watch-audio-2026-10-04_2026-10-10`, or one date for a single day; also the archive's root folder. */
    val baseName: String get() = if (firstDay == lastDay) "watch-audio-$firstDay" else "watch-audio-${firstDay}_$lastDay"

    val fileName: String get() = "$baseName.zip"
}

/** What an export wrote. */
data class ExportSummary(
    /** Days with at least one chunk. */
    val days: Int,
    val chunks: Int,
    val segments: Int,
    val marks: Int,
    val audioFiles: Int,
    /** Chunks whose audio should still be on the device but whose file was not found. */
    val missingAudioFiles: Int,
)

/** Progress of [DataExporter.write]: the first [doneDays] of [totalDays] calendar days are written. */
data class ExportProgress(val doneDays: Int, val totalDays: Int)

/**
 * Writes the user's data as one ZIP archive (#155) to a sink the platform opened at the place the
 * user chose. Nothing here touches the network: the archive only goes where that sink goes.
 *
 * Layout under the root folder [ExportPlan.baseName]:
 * - `README.md`, `export.json` ([ExportIndex])
 * - `markdown/<date>.md` ([renderDayMarkdown]) and `json/<date>.json` ([ExportDay]) per day
 * - `audio/<date>/<HHmmss>_<chunk UUID>.<ext>` when [ExportRequest.includeAudio]
 *
 * Days are read one at a time and audio files are streamed, so memory does not grow with the
 * size of the export. The database is only locked while a day is read.
 */
class DataExporter(
    private val store: LocalStore,
    private val audioRoot: Path,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
) {
    /** The days [request] covers that have chunks, or null when there is nothing to export. */
    suspend fun plan(request: ExportRequest): ExportPlan? {
        val zone = request.timeZone
        val from = request.range.from?.let { dayWindow(it, zone).start } ?: Long.MIN_VALUE
        val to = request.range.to?.let { dayWindow(it, zone).end } ?: Long.MAX_VALUE
        val (first, last) = store.read {
            firstChunkStartAtOrAfter(from)?.takeIf { it < to } to lastChunkBefore(to)?.startedAt
        }
        if (first == null || last == null) return null
        return ExportPlan(request, localDateOf(first, zone), localDateOf(last, zone))
    }

    /**
     * Writes [plan] to [sink] (flushed, not closed). Throws if the store or an audio file cannot
     * be read, or the sink cannot be written; the caller discards the partial file.
     */
    suspend fun write(plan: ExportPlan, sink: RawSink, onProgress: (ExportProgress) -> Unit = {}): ExportSummary {
        val request = plan.request
        val zone = request.timeZone
        val root = plan.baseName
        val zip = ZipWriter(sink, Instant.fromEpochMilliseconds(request.exportedAt).toLocalDateTime(zone))
        val days = mutableListOf<ExportIndexDay>()
        var audioFiles = 0
        var missingAudio = 0

        var date = plan.firstDay
        while (date <= plan.lastDay) {
            currentCoroutineContext().ensureActive()
            val window = dayWindow(date, zone)
            val records = store.read { readDayRecords(window) }
            if (records.chunks.isNotEmpty()) {
                val included = mutableMapOf<String, String>()
                if (request.includeAudio) {
                    records.chunks.filter { it.hasAudioFile }.forEach { chunk ->
                        val path = Path(audioRoot, chunk.audioPath!!)
                        val name = audioEntryName(date, chunk, zone, records.audioFiles[chunk.id])
                        val written = withContext(dispatcher) {
                            if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) return@withContext false
                            zip.addFile("$root/$name") { SystemFileSystem.source(path) }
                            true
                        }
                        if (written) included[chunk.id] = name else missingAudio++
                    }
                }
                val day = buildExportDay(date, zone, records, request.exportedAt, included)
                val json = "json/$date.json"
                val markdown = "markdown/$date.md"
                withContext(dispatcher) {
                    zip.addFile("$root/$json", day.toJson().encodeToByteArray())
                    zip.addFile("$root/$markdown", renderDayMarkdown(day).encodeToByteArray())
                }
                audioFiles += included.size
                days += ExportIndexDay(
                    date = date,
                    json = json,
                    markdown = markdown,
                    chunks = day.chunks.size,
                    segments = day.segments.size,
                    marks = day.marks.size,
                    gaps = day.gaps.size,
                    audioFiles = included.size,
                )
            }
            onProgress(ExportProgress(plan.firstDay.daysUntil(date) + 1, plan.dayCount))
            // Days without chunks are skipped in one step (a watch clock far off can stretch the range).
            val next = store.read { firstChunkStartAtOrAfter(window.end) } ?: break
            date = maxOf(date.plus(1, DateTimeUnit.DAY), localDateOf(next, zone))
        }

        val index = ExportIndex(
            exportedAt = request.exportedAt,
            timeZone = zone.id,
            from = request.range.from,
            to = request.range.to,
            includesAudio = request.includeAudio,
            days = days,
        )
        withContext(dispatcher) {
            zip.addFile("$root/export.json", index.toJson().encodeToByteArray())
            zip.addFile("$root/README.md", MarkdownText.readme(request.includeAudio).encodeToByteArray())
            zip.finish()
        }
        return ExportSummary(
            days = days.size,
            chunks = days.sumOf { it.chunks },
            segments = days.sumOf { it.segments },
            marks = days.sumOf { it.marks },
            audioFiles = audioFiles,
            missingAudioFiles = missingAudio,
        )
    }
}
