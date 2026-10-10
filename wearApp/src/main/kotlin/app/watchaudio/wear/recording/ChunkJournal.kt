package app.watchaudio.wear.recording

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything known about the chunk that is currently being recorded, persisted next to its audio
 * file so a chunk interrupted by a process death can still be finalized (as
 * [app.watchaudio.core.protocol.ChunkEndReason.TERMINATED]) on the next launch.
 */
@Serializable
data class ChunkJournal(
    val chunkId: String,
    val sessionId: String,
    val deviceId: String,
    val startedAtEpochMs: Long,
    val timeZone: String,
    val markOffsetsMs: List<Long> = emptyList(),
    /** Last time the recorder was known to be running; the end estimate after a crash. */
    val lastAliveAtEpochMs: Long = startedAtEpochMs,
    val amplitudeSamples: Int = 0,
    val amplitudeLoudSamples: Int = 0,
    val amplitudePeak: Int = 0,
    /** Started as a retry after an interruption (see [InterruptionBackoff.PROBE_DISCARD_MS]). */
    val retryProbe: Boolean = false,
) {
    val audioFileName: String get() = audioFileName(chunkId)

    fun silenceDetector() = SilenceDetector(amplitudeSamples, amplitudeLoudSamples, amplitudePeak)

    fun withSilence(detector: SilenceDetector) = copy(
        amplitudeSamples = detector.samples,
        amplitudeLoudSamples = detector.loudSamples,
        amplitudePeak = detector.peak,
    )

    companion object {
        const val AUDIO_EXTENSION = "m4a"

        fun audioFileName(chunkId: String) = "$chunkId.$AUDIO_EXTENSION"
    }
}

/**
 * Directory holding in-progress chunks: `<chunkId>.m4a` written by `MediaRecorder` and
 * `<chunkId>.journal.json`. Finalized chunks move to the [app.watchaudio.wear.outbox.Outbox].
 */
class RecordingDir(val dir: File) {
    init {
        dir.mkdirs()
    }

    fun audioFile(chunkId: String) = File(dir, ChunkJournal.audioFileName(chunkId))

    fun write(journal: ChunkJournal) {
        writeAtomically(journalFile(journal.chunkId), JournalJson.encodeToString(ChunkJournal.serializer(), journal))
    }

    fun journals(): List<ChunkJournal> = dir.listFiles { f -> f.name.endsWith(JOURNAL_SUFFIX) }.orEmpty()
        .mapNotNull { file -> runCatching { JournalJson.decodeFromString(ChunkJournal.serializer(), file.readText()) }.getOrNull() }
        .sortedBy { it.startedAtEpochMs }

    /** Removes the journal and the audio file (if still here). */
    fun delete(chunkId: String) {
        journalFile(chunkId).delete()
        audioFile(chunkId).delete()
    }

    /** Audio files without a journal: created for a chunk that never started (or a corrupt journal). */
    fun orphanAudioFiles(): List<File> {
        val journaled = dir.listFiles { f -> f.name.endsWith(JOURNAL_SUFFIX) }.orEmpty()
            .map { it.name.removeSuffix(JOURNAL_SUFFIX) }.toSet()
        return dir.listFiles { f -> f.name.endsWith(".${ChunkJournal.AUDIO_EXTENSION}") }.orEmpty()
            .filter { it.name.substringBeforeLast('.') !in journaled }
    }

    /** Removes broken journals (unparseable) so they do not linger forever. */
    fun deleteUnreadableJournals() {
        dir.listFiles { f -> f.name.endsWith(JOURNAL_SUFFIX) }.orEmpty().forEach { file ->
            val ok = runCatching { JournalJson.decodeFromString(ChunkJournal.serializer(), file.readText()) }.isSuccess
            if (!ok) file.delete()
        }
    }

    private fun journalFile(chunkId: String) = File(dir, "$chunkId$JOURNAL_SUFFIX")

    private companion object {
        const val JOURNAL_SUFFIX = ".journal.json"
        val JournalJson = Json { ignoreUnknownKeys = true }
    }
}

/** Writes via a temp file + rename so readers never see a half-written file. */
internal fun writeAtomically(target: File, text: String) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
        target.delete()
        check(tmp.renameTo(target)) { "could not write $target" }
    }
}
