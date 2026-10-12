package app.watchaudio.core.backup

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.export.ExportPlan
import app.watchaudio.core.export.ExportProgress
import app.watchaudio.core.export.ExportRange
import app.watchaudio.core.export.ExportRequest
import app.watchaudio.core.export.ExportSummary
import app.watchaudio.core.platform.IoDispatcher
import app.watchaudio.core.platform.secureRandomBytes
import app.watchaudio.core.timeline.localDateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.io.RawSink
import kotlinx.io.files.Path

/**
 * Writes an encrypted backup (#40) of everything on the device: the export archive of all days
 * ([DataExporter] with [ExportRequest.forBackup]) encrypted with a key derived from the user's
 * passphrase ([BackupCipher]). The sink is the place the user picked; the passphrase and the
 * keys stay in memory on this device and are not stored anywhere.
 */
class BackupWriter(
    store: LocalStore,
    audioRoot: Path,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
    /** PBKDF2 rounds of the backups it writes; only tests lower it. */
    private val iterations: Int = BackupCipher.DEFAULT_ITERATIONS,
) {
    private val exporter = DataExporter(store, audioRoot, dispatcher)

    /**
     * The backup of all days, with audio if [includeAudio], made at [now]; null when there is
     * nothing to back up. [timeZone] only decides how the days are grouped inside the file.
     */
    suspend fun plan(includeAudio: Boolean, timeZone: TimeZone, now: Long): ExportPlan? =
        exporter.plan(ExportRequest(ExportRange.ALL, includeAudio, timeZone, now, forBackup = true))

    /**
     * Encrypts [plan] with [passphrase] into [sink] (flushed, not closed). Throws if the store,
     * an audio file or the sink fails; the partial file then has no final chunk and is rejected
     * when restored, and the caller discards it.
     *
     * @param chunkSize plaintext bytes per authenticated chunk; only tests lower it.
     * @param random salt and nonce source; only tests fix it.
     */
    suspend fun write(
        plan: ExportPlan,
        passphrase: String,
        sink: RawSink,
        onProgress: (ExportProgress) -> Unit = {},
        chunkSize: Int = BackupCipher.DEFAULT_CHUNK_SIZE,
        random: (Int) -> ByteArray = ::secureRandomBytes,
    ): ExportSummary {
        require(plan.request.forBackup) { "not a backup plan" }
        val encrypting = withContext(dispatcher) { BackupCipher.encrypt(sink, passphrase, iterations, chunkSize, random) }
        try {
            val summary = exporter.write(plan, encrypting, onProgress)
            withContext(dispatcher) { encrypting.finish() }
            return summary
        } finally {
            encrypting.close()
        }
    }

    companion object {
        /** `watch-audio-backup-2026-10-12.wabackup` for a backup made on that day in [timeZone]. */
        fun fileName(now: Long, timeZone: TimeZone): String = "watch-audio-backup-${localDateOf(now, timeZone)}.$BACKUP_FILE_EXTENSION"
    }
}
