package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState

/**
 * How long audio files are kept on the phone. Transcripts are always kept forever; only the
 * audio of chunks that are already transcribed is ever deleted.
 */
enum class AudioRetention(
    /** Days to keep audio, or null to keep it forever. */
    val days: Int?,
) {
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    UNLIMITED(null),
    ;

    /** True if switching from this setting to [other] makes audio be deleted sooner. */
    fun isShorterThan(other: AudioRetention): Boolean = when {
        days == null -> false
        other.days == null -> true
        else -> days < other.days
    }

    companion object {
        /** The product keeps everything unless the user opts out. */
        val DEFAULT = UNLIMITED

        /** Key in the `app_setting` table. */
        const val SETTING_KEY = "audio_retention"

        /** Parses a stored value; unknown or missing values fall back to [DEFAULT]. */
        fun fromSetting(value: String?): AudioRetention = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** Decides which chunks lose their audio under an [AudioRetention]. Pure; see [StorageManager]. */
object AudioRetentionPolicy {
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Chunks that ended at or before this time are old enough to lose their audio, or null if
     * nothing is ever deleted.
     */
    fun cutoff(retention: AudioRetention, now: Long): Long? = retention.days?.let { now - it * DAY_MS }

    /**
     * True only for a chunk that is transcribed, still has its audio and ended before the cutoff.
     * Untranscribed audio (pending, in progress, failed) is never deleted.
     */
    fun shouldPurge(chunk: ChunkRecord, retention: AudioRetention, now: Long): Boolean {
        val cutoff = cutoff(retention, now) ?: return false
        return chunk.state == ChunkState.TRANSCRIBED && chunk.hasAudioFile && chunk.endedAt <= cutoff
    }
}
