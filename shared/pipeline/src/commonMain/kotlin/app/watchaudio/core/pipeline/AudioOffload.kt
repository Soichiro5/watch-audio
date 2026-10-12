package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState

/**
 * Which backed-up audio the offload (#156) removes from the phone: all of it, or only audio
 * older than [days] days (recent audio is the most likely to be played again).
 */
enum class OffloadAge(
    /** Minimum age in days; 0 for all backed-up audio. */
    val days: Int,
) {
    ALL(0),
    DAYS_7(7),
    DAYS_30(30),
}

/**
 * Decides which chunks may lose their audio file to the offload (#156). Pure; see
 * [StorageManager.offloadBackedUpAudio].
 *
 * Offloading only ever touches audio that was written into an encrypted backup the user saved
 * ([ChunkRecord.audioBackedUpAt]); audio that was never backed up stays on the phone. Like the
 * retention setting (#33), it keeps every transcript and only takes audio that is transcribed.
 */
object AudioOffloadPolicy {
    /** Chunks that ended at or before this time are old enough under [age]. */
    fun cutoff(age: OffloadAge, now: Long): Long =
        if (age.days == 0) Long.MAX_VALUE else now - age.days * AudioRetentionPolicy.DAY_MS

    /** True only for a transcribed chunk whose audio is still on the phone, is in a backup and ended by [cutoff]. */
    fun canOffload(chunk: ChunkRecord, cutoff: Long): Boolean =
        chunk.state == ChunkState.TRANSCRIBED && chunk.hasAudioFile && chunk.audioBackedUpAt != null && chunk.endedAt <= cutoff
}
