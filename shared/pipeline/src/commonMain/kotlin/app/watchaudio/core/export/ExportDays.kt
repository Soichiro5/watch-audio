package app.watchaudio.core.export

import app.watchaudio.core.db.AudioFileInfo
import app.watchaudio.core.db.ChunkBackupInfo
import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.HourSummaryRecord
import app.watchaudio.core.db.HourSummaryState
import app.watchaudio.core.db.MarkRecord
import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.db.StoreTransaction
import app.watchaudio.core.timeline.DayWindow
import app.watchaudio.core.timeline.GapCause
import app.watchaudio.core.timeline.buildDayTimeline
import app.watchaudio.core.timeline.dayWindow
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What the database holds for one exported day, read in one go ([readDayRecords]). */
data class DayRecords(
    /** Chunks that started on the day: the ones the day's files contain. */
    val chunks: List<ChunkRecord>,
    /** Chunks overlapping the day and the last one before it, for its gaps (as the timeline). */
    val overlapping: List<ChunkRecord>,
    val previous: ChunkRecord?,
    /** Segments and marks of [chunks]. */
    val segments: List<SegmentRecord>,
    val marks: List<MarkRecord>,
    /** Codec and checksum of the audio of [chunks], by chunk UUID. */
    val audioFiles: Map<String, AudioFileInfo>,
    /** For a backup (#40) only: what the backup keeps of [chunks] beyond the export, by chunk UUID. */
    val backupInfo: Map<String, ChunkBackupInfo> = emptyMap(),
    /** Not for a backup: the summaries of the day's hours (#27) and the segments they cite, by id. */
    val summaries: List<HourSummaryRecord> = emptyList(),
    val citedSegments: Map<Long, SegmentRecord> = emptyMap(),
)

/**
 * Reads [DayRecords] for [window]; with [forBackup], also [DayRecords.backupInfo], otherwise also
 * the hour summaries (#27: exported, but derived data a backup leaves out).
 */
fun StoreTransaction.readDayRecords(window: DayWindow, forBackup: Boolean = false): DayRecords {
    val summaries = if (forBackup) emptyList() else hourSummariesBetween(window.start, window.end)
    val segments = segmentsOfChunksStartedBetween(window.start, window.end)
    val known = segments.associateBy { it.id }
    val cited = summaries.flatMap { s -> s.highlights.flatMap { it.segmentIds } }.distinct()
        .mapNotNull { id -> known[id] ?: segment(id) }
        .associateBy { it.id }
    return DayRecords(
        chunks = chunksStartedBetween(window.start, window.end),
        overlapping = chunksBetween(window.start, window.end),
        previous = lastChunkBefore(window.start),
        segments = segments,
        marks = marksOfChunksStartedBetween(window.start, window.end),
        audioFiles = audioFilesOfChunksStartedBetween(window.start, window.end),
        backupInfo = if (forBackup) backupInfoOfChunksStartedBetween(window.start, window.end) else emptyMap(),
        summaries = summaries,
        citedSegments = cited,
    )
}

/**
 * These records narrowed to the chunks [ids] (a diff backup, #164) with their segments, marks and
 * files. The chunks around them stay, so the day's gaps are those of the whole day.
 */
fun DayRecords.only(ids: Set<String>): DayRecords = copy(
    chunks = chunks.filter { it.id in ids },
    segments = segments.filter { it.chunkId in ids },
    marks = marks.filter { it.chunkId in ids },
    audioFiles = audioFiles.filterKeys { it in ids },
    backupInfo = backupInfo.filterKeys { it in ids },
)

/**
 * The [ExportDay] of [date] in [timeZone].
 *
 * @param now the export time: gaps end there, and recent ones count as still arriving, exactly as
 *   the timeline would show them at that moment.
 * @param includedAudio archive paths of the audio files written for this day, by chunk UUID.
 */
fun buildExportDay(
    date: LocalDate,
    timeZone: TimeZone,
    records: DayRecords,
    now: Long,
    includedAudio: Map<String, String> = emptyMap(),
): ExportDay {
    val window = dayWindow(date, timeZone)
    val timeline = buildDayTimeline(window, records.overlapping, records.previous, now)
    return ExportDay(
        date = date,
        timeZone = timeZone.id,
        dayStart = window.start,
        dayEnd = window.end,
        chunks = records.chunks.sortedBy { it.startedAt }.map { it.toExport(records.audioFiles[it.id], includedAudio[it.id]) },
        segments = records.segments.sortedWith(compareBy({ it.startAt }, { it.id })).map {
            ExportSegment(it.chunkId, it.startAt, it.endAt, it.text, it.lang, it.engine)
        },
        marks = records.marks.sortedWith(compareBy({ it.at }, { it.chunkId })).map { ExportMark(it.chunkId, it.at) },
        gaps = timeline.gaps.mapNotNull { gap ->
            when (val cause = gap.cause) {
                is GapCause.Stopped -> ExportGap(gap.start, gap.end, ExportGapCause.STOPPED, cause.reason)
                GapCause.Missing -> ExportGap(gap.start, gap.end, ExportGapCause.MISSING)
                GapCause.Awaiting -> ExportGap(gap.start, gap.end, ExportGapCause.AWAITING)
                // Before anything was ever recorded: not a gap in the recording.
                GapCause.NoRecording -> null
            }
        },
        summaries = records.summaries.sortedBy { it.hourStart }.map { it.toExport(records.citedSegments) },
    )
}

private fun HourSummaryRecord.toExport(cited: Map<Long, SegmentRecord>) = ExportHourSummary(
    hourStart = hourStart,
    hourEnd = hourEnd,
    state = when (state) {
        HourSummaryState.DONE -> ExportSummaryState.DONE
        HourSummaryState.LITTLE_SPEECH -> ExportSummaryState.LITTLE_SPEECH
        HourSummaryState.REFUSED -> ExportSummaryState.REFUSED
        HourSummaryState.FAILED -> ExportSummaryState.FAILED
    },
    summary = summary,
    highlights = highlights.map { h ->
        ExportHighlight(h.title, h.body, h.segmentIds.mapNotNull { cited[it] }.map { ExportCitation(it.chunkId, it.startAt) })
    },
    stale = stale,
    provider = provider,
    modelVersion = modelVersion,
    createdAt = createdAt,
)

private fun ChunkRecord.toExport(file: AudioFileInfo?, includedPath: String?) = ExportChunk(
    id = id,
    sessionId = sessionId,
    deviceId = deviceId,
    source = source,
    startedAt = startedAt,
    endedAt = endedAt,
    timeZone = timeZone,
    endReason = endReason,
    transcription = when (state) {
        ChunkState.RECEIVED, ChunkState.TRANSCRIBING -> ExportTranscription.PENDING
        ChunkState.TRANSCRIBED -> ExportTranscription.DONE
        ChunkState.FAILED -> ExportTranscription.FAILED
        ChunkState.NO_AUDIO -> ExportTranscription.NONE
    },
    audio = audioPath?.let {
        ExportAudio(
            codec = file?.codec,
            sizeBytes = sizeBytes,
            sha256 = file?.sha256,
            purgedAt = audioPurgedAt,
            file = includedPath,
            backedUpAt = audioBackedUpAt,
        )
    },
)

/**
 * Archive path of [chunk]'s audio on [date]: `audio/<date>/<HHmmss>_<chunk UUID>.<ext>`, so the
 * files sort by time and still name their chunk.
 */
fun audioEntryName(date: LocalDate, chunk: ChunkRecord, timeZone: TimeZone, file: AudioFileInfo?): String {
    val time = Instant.fromEpochMilliseconds(chunk.startedAt).toLocalDateTime(timeZone)
    val clock = listOf(time.hour, time.minute, time.second).joinToString("") { it.toString().padStart(2, '0') }
    val extension = file?.codec?.fileExtension
        ?: chunk.audioPath?.substringAfterLast('/')?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        ?: "bin"
    return "audio/$date/${clock}_${chunk.id.toSafeFileName()}.$extension"
}

/** The watch assigns UUIDs, but a chunk id is never trusted as a path. */
internal fun String.toSafeFileName(): String = map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
