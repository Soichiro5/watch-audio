package app.watchaudio.app.logic

import app.watchaudio.app.Strings
import app.watchaudio.core.pipeline.AudioRetention
import app.watchaudio.core.timeline.localDateOf
import kotlinx.datetime.TimeZone

private const val GIB = 1L shl 30

/** `850.3 MB` below 1 GB, `12.4 GB` above. */
fun formatBytes(bytes: Long): String {
    if (bytes < GIB) return formatMegabytes(bytes)
    val tenths = (bytes * 10 + GIB / 2) / GIB
    return "${tenths / 10}.${tenths % 10} GB"
}

/**
 * Size of a backup file (#164): like [formatBytes], but a small diff reads `12 KB` instead of
 * `0.0 MB`.
 */
fun formatBackupSize(bytes: Long): String =
    if (bytes >= 100L shl 10) formatBytes(bytes) else "${((bytes + 1023) shr 10).coerceAtLeast(1)} KB"

/** Switching retention from [current] to [target] deletes audio sooner, so it needs a confirmation. */
fun retentionChangeNeedsConfirmation(current: AudioRetention, target: AudioRetention): Boolean =
    target.isShorterThan(current)

/**
 * The day (or the first and last day) of the backups that hold audio about to be offloaded (#156),
 * in [timeZone]: `2026年10月12日(月)` or `2026年10月5日(月)〜2026年10月12日(月)`.
 */
fun formatBackupDates(oldest: Long, newest: Long, timeZone: TimeZone): String {
    val first = localDateOf(oldest, timeZone)
    val last = localDateOf(newest, timeZone)
    return if (first == last) formatDate(first) else "${formatDate(first)}〜${formatDate(last)}"
}

/** "Delete everything" goes ahead only after the user typed [Strings.DELETE_ALL_WORD]. */
fun isDeleteAllConfirmed(input: String?): Boolean = input?.trim() == Strings.DELETE_ALL_WORD
