package app.watchaudio.core.export

import app.watchaudio.core.protocol.ChunkEndReason
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * The words of the exported Markdown (Japanese, like the app). They live with the format rather
 * than in the UI's `Strings` because the export is a file, written by shared code; the end-reason
 * labels must match the timeline's (`Strings.endReason`, checked by a shared/ui test).
 */
object MarkdownText {
    const val GAPS = "録音できていない時間"
    const val TRANSCRIPT = "文字起こし"
    const val AUDIO_FILES = "音声ファイル"
    const val MARK = "マーク"
    const val NO_UTTERANCES = "文字起こしされた発話はありません"
    const val GAP_MISSING = "データ欠落（未受信）"
    const val GAP_AWAITING = "転送待ち"
    const val NEXT_DAY = "翌日"
    const val AUDIO_PURGED = "保持期間を過ぎて削除済み"
    const val AUDIO_NOT_FOUND = "ファイルが見つかりませんでした"
    val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

    fun endReason(reason: ChunkEndReason): String = when (reason) {
        ChunkEndReason.ROTATION -> "継続"
        ChunkEndReason.MARK -> "マーク"
        ChunkEndReason.USER_STOP -> "手動で停止"
        ChunkEndReason.INTERRUPTION -> "割り込み（通話など）"
        ChunkEndReason.CHARGING -> "充電中"
        ChunkEndReason.OFF_BODY -> "非装着"
        ChunkEndReason.LOW_BATTERY -> "電池残量低下"
        ChunkEndReason.LOW_STORAGE -> "ストレージ不足"
        ChunkEndReason.TERMINATED -> "アプリ終了"
        ChunkEndReason.ERROR -> "エラー"
    }

    fun stopped(reason: ChunkEndReason) = "録音停止（${endReason(reason)}）"
    fun title(date: LocalDate) = "${date.year}年${date.month.number}月${date.day}日(${WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]})"
    fun summary(recorded: String, audio: String, silent: String, utterances: Int, marks: Int) =
        "録音 $recorded（音声 $audio ・ 無音 $silent）、発話 ${utterances}件、マーク ${marks}件"
    fun notTranscribed(pending: Int, failed: Int) = buildList {
        if (pending > 0) add("文字起こし待ち ${pending}件")
        if (failed > 0) add("文字起こし失敗 ${failed}件")
    }.joinToString("、") + "のチャンクは、文字起こしがまだ含まれていません。"
    fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}時間${m}分"
            h > 0 -> "${h}時間"
            else -> "${m}分"
        }
    }

    /** `README.md` at the root of the archive. */
    fun readme(includesAudio: Boolean) = buildString {
        appendLine("# watch-audio の書き出し")
        appendLine()
        appendLine("このフォルダは watch-audio アプリから書き出した記録です。")
        appendLine()
        appendLine("- `markdown/`: 日ごとの文字起こし（Markdown）。Obsidian などでそのまま読めます。")
        appendLine("- `json/`: 日ごとのチャンク・発話・マーク・録音できていない時間（JSON。アプリに取り込み直せる形式）。")
        if (includesAudio) appendLine("- `audio/`: 日ごとの録音ファイル。")
        appendLine("- `export.json`: 書き出しの内容の一覧。")
        appendLine()
        appendLine("時刻はすべて `export.json` の `timeZone` の時刻です。JSON の時刻は UNIX エポックからのミリ秒です。")
    }
}

/**
 * Renders [day] as one Markdown note (Obsidian-friendly: YAML front matter, one heading per hour).
 * Times are shown in the day's time zone; a time past midnight gets 「翌日」.
 *
 * @param archiveRoot how the note reaches the archive's root folder, for links to audio files.
 */
fun renderDayMarkdown(day: ExportDay, archiveRoot: String = "../"): String = buildString {
    val zone = TimeZone.of(day.timeZone)
    val audioMs = day.chunks.filter { it.audio != null }.sumOf { it.endedAt - it.startedAt }
    val silentMs = day.chunks.filter { it.audio == null }.sumOf { it.endedAt - it.startedAt }

    appendLine("---")
    appendLine("date: ${day.date}")
    appendLine("time_zone: ${day.timeZone}")
    appendLine("recorded_minutes: ${(audioMs + silentMs) / 60_000}")
    appendLine("utterances: ${day.segments.size}")
    appendLine("marks: ${day.marks.size}")
    appendLine("gaps: ${day.gaps.size}")
    appendLine("tags:")
    appendLine("  - watch-audio")
    appendLine("---")
    appendLine()
    appendLine("# ${MarkdownText.title(day.date)}")
    appendLine()
    appendLine(
        MarkdownText.summary(
            MarkdownText.duration(audioMs + silentMs),
            MarkdownText.duration(audioMs),
            MarkdownText.duration(silentMs),
            day.segments.size,
            day.marks.size,
        ),
    )
    val pending = day.chunks.count { it.transcription == ExportTranscription.PENDING }
    val failed = day.chunks.count { it.transcription == ExportTranscription.FAILED }
    if (pending > 0 || failed > 0) {
        appendLine()
        appendLine(MarkdownText.notTranscribed(pending, failed))
    }

    if (day.gaps.isNotEmpty()) {
        appendLine()
        appendLine("## ${MarkdownText.GAPS}")
        appendLine()
        day.gaps.forEach { gap -> appendLine("- ${clockRange(gap.start, gap.end, day, zone, seconds = false)} ${gapText(gap)}") }
    }

    appendLine()
    appendLine("## ${MarkdownText.TRANSCRIPT}")
    val lines = buildList {
        day.segments.forEach { add(Line(it.startAt, 1, "- ${clock(it.startAt, day, zone)} ${escapeMarkdown(it.text)}")) }
        day.marks.forEach { add(Line(it.at, 0, "- ${clock(it.at, day, zone)} **${MarkdownText.MARK}**")) }
        day.gaps.forEach { add(Line(it.start, 0, "- ${clockRange(it.start, it.end, day, zone, seconds = true)} *${gapText(it)}*")) }
    }.sortedWith(compareBy({ it.at }, { it.order }))
    if (day.segments.isEmpty()) {
        appendLine()
        appendLine(MarkdownText.NO_UTTERANCES)
    }
    var hour: String? = null
    lines.forEach { line ->
        val heading = hourHeading(line.at, day, zone)
        if (heading != hour) {
            hour = heading
            appendLine()
            appendLine("### $heading")
            appendLine()
        }
        appendLine(line.text)
    }

    val withAudio = day.chunks.filter { it.audio != null }
    if (withAudio.any { it.audio?.file != null }) {
        appendLine()
        appendLine("## ${MarkdownText.AUDIO_FILES}")
        appendLine()
        withAudio.forEach { chunk ->
            val audio = chunk.audio ?: return@forEach
            val range = clockRange(chunk.startedAt, chunk.endedAt, day, zone, seconds = false)
            val target = when {
                audio.file != null -> "[${audio.file.substringAfterLast('/')}]($archiveRoot${audio.file})"
                audio.purgedAt != null -> MarkdownText.AUDIO_PURGED
                else -> MarkdownText.AUDIO_NOT_FOUND
            }
            appendLine("- $range $target")
        }
    }
}

private class Line(val at: Long, val order: Int, val text: String)

private fun gapText(gap: ExportGap): String = when (gap.cause) {
    ExportGapCause.STOPPED -> MarkdownText.stopped(gap.endReason ?: ChunkEndReason.ERROR)
    ExportGapCause.MISSING -> MarkdownText.GAP_MISSING
    ExportGapCause.AWAITING -> MarkdownText.GAP_AWAITING
}

/** `HH:mm:ss` in [zone]; 「翌日 」 in front once past the day. */
private fun clock(at: Long, day: ExportDay, zone: TimeZone, seconds: Boolean = true): String {
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    val text = listOfNotNull(t.hour, t.minute, t.second.takeIf { seconds }).joinToString(":") { it.toString().padStart(2, '0') }
    return if (at >= day.dayEnd) "${MarkdownText.NEXT_DAY} $text" else text
}

/** `HH:mm–HH:mm`; an end exactly at the end of the day is `24:00`. */
private fun clockRange(from: Long, to: Long, day: ExportDay, zone: TimeZone, seconds: Boolean): String {
    val end = if (to == day.dayEnd) (if (seconds) "24:00:00" else "24:00") else clock(to, day, zone, seconds)
    return "${clock(from, day, zone, seconds)}–$end"
}

/** `07:00` for the hour [at] falls in; past midnight it is the next day's hour. */
private fun hourHeading(at: Long, day: ExportDay, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    val text = "${t.hour.toString().padStart(2, '0')}:00"
    return if (at >= day.dayEnd) "${MarkdownText.NEXT_DAY} $text" else text
}

/**
 * Makes recognized text literal in Markdown: line breaks become spaces and ASCII punctuation
 * that CommonMark or Obsidian would interpret (emphasis, links, HTML, tags, highlights, math,
 * comments, tables) is backslash-escaped.
 */
fun escapeMarkdown(text: String): String = buildString {
    text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').forEach { c ->
        if (c in MARKDOWN_SPECIAL) append('\\')
        append(c)
    }
}

private const val MARKDOWN_SPECIAL = "\\`*_[]<>#|~=\$%!"
