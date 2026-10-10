package app.watchaudio.app.logic

import app.watchaudio.app.Strings
import app.watchaudio.core.pipeline.AudioRetention

private const val GIB = 1L shl 30

/** `850.3 MB` below 1 GB, `12.4 GB` above. */
fun formatBytes(bytes: Long): String {
    if (bytes < GIB) return formatMegabytes(bytes)
    val tenths = (bytes * 10 + GIB / 2) / GIB
    return "${tenths / 10}.${tenths % 10} GB"
}

/** Switching retention from [current] to [target] deletes audio sooner, so it needs a confirmation. */
fun retentionChangeNeedsConfirmation(current: AudioRetention, target: AudioRetention): Boolean =
    target.isShorterThan(current)

/** "Delete everything" goes ahead only after the user typed [Strings.DELETE_ALL_WORD]. */
fun isDeleteAllConfirmed(input: String?): Boolean = input?.trim() == Strings.DELETE_ALL_WORD
