package app.watchaudio.wear.recording

import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.RecordingFormat
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.wear.outbox.Outbox
import java.io.File
import kotlinx.io.files.Path

/**
 * Turns a closed in-progress chunk ([ChunkJournal] + audio file in [RecordingDir]) into an
 * [Outbox] entry: decides silence, hashes the audio and writes the manifest.
 */
class ChunkFinalizer(
    private val recordingDir: RecordingDir,
    private val outbox: Outbox,
) {
    /** @property audioDropped the audio was unusable (not finalized / missing), not just silent. */
    data class Result(val manifest: ChunkManifest, val silent: Boolean, val audioDropped: Boolean)

    /**
     * @param audioUsable false when the recorder did not stop cleanly; the audio cannot be
     *   decoded and is dropped.
     *
     * Without usable audio the chunk is emitted with `durationMs = 0`: `audio == null` with a
     * duration means "confidently silent" on the phone, which would be wrong here. The zero-length
     * chunk still tells the phone where the gap started and why ([endReason]).
     */
    fun finalize(
        journal: ChunkJournal,
        durationMs: Long,
        endReason: ChunkEndReason,
        audioUsable: Boolean = true,
    ): Result {
        val file = recordingDir.audioFile(journal.chunkId)
        val usable = audioUsable && file.isFile && file.length() > 0
        val duration = if (usable) durationMs.coerceAtLeast(0) else 0L
        val silent = usable && journal.silenceDetector().isSilent(duration)
        val keepAudio = usable && !silent
        val manifest = buildManifest(journal, duration, endReason, if (keepAudio) describe(file, journal.audioFileName) else null)
        outbox.commit(manifest, if (keepAudio) file else null)
        recordingDir.delete(journal.chunkId)
        return Result(manifest, silent = silent, audioDropped = !usable)
    }

    /**
     * Finalizes chunks left behind by a process that died while recording. Only call this when
     * no recorder is running in this process (e.g. from `Application.onCreate`).
     */
    fun recoverLeftovers(journals: List<ChunkJournal> = recordingDir.journals()): List<Result> {
        val results = journals.map { journal ->
            val file = recordingDir.audioFile(journal.chunkId)
            val duration = journal.lastAliveAtEpochMs - journal.startedAtEpochMs
            finalize(journal, duration, ChunkEndReason.TERMINATED, audioUsable = Mp4Probe.isFinalized(file))
        }
        recordingDir.deleteUnreadableJournals()
        recordingDir.orphanAudioFiles().forEach { it.delete() }
        return results
    }

    companion object {
        fun buildManifest(journal: ChunkJournal, durationMs: Long, endReason: ChunkEndReason, audio: AudioFile?) = ChunkManifest(
            chunkId = journal.chunkId,
            sessionId = journal.sessionId,
            deviceId = journal.deviceId,
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = journal.startedAtEpochMs,
            durationMs = durationMs,
            timeZone = journal.timeZone,
            audio = audio,
            // A mark tapped in the instant the chunk closed can land a few ms past its end.
            markOffsetsMs = journal.markOffsetsMs.map { it.coerceIn(0, durationMs) }.sorted(),
            endReason = endReason,
        )

        /** Describes [file] in the format every [RecorderInput] writes ([RecordingFormat.CURRENT]). */
        fun describe(file: File, fileName: String): AudioFile =
            RecordingFormat.CURRENT.audioFile(fileName, sizeBytes = file.length(), sha256 = sha256Of(Path(file.path)))
    }
}
