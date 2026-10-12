package app.watchaudio.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.watchaudio.app.AppServices
import app.watchaudio.app.BACKUP_MIME_TYPE
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.PassphraseStrength
import app.watchaudio.app.logic.passphraseAccepted
import app.watchaudio.app.logic.passphraseStrength
import app.watchaudio.core.backup.BackupException
import app.watchaudio.core.backup.BackupWriter
import app.watchaudio.core.backup.RestoreProgress
import app.watchaudio.core.backup.RestoreSummary
import app.watchaudio.core.export.ExportPlan
import app.watchaudio.core.export.ExportSummary
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.DiagnosticsLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** The 「暗号化バックアップ」 card (#40): creating a backup and restoring one. */
data class BackupUiState(
    val passphrase: String = "",
    val confirmation: String = "",
    val includeAudio: Boolean = false,
    val creating: Boolean = false,
    /** Share of the days written (0..1) while creating; null before (e.g. while the user picks the place). */
    val createProgress: Float? = null,
    val createMessage: String? = null,
    val restorePassphrase: String = "",
    val restoring: Boolean = false,
    /** Where a running restore is; null while the user picks the file. */
    val restoreProgress: RestoreProgress? = null,
    val restoreMessage: String? = null,
) {
    val strength: PassphraseStrength get() = passphraseStrength(passphrase)

    /** The confirmation was typed and differs. */
    val confirmationMismatch: Boolean get() = confirmation.isNotEmpty() && confirmation != passphrase

    val busy: Boolean get() = creating || restoring

    val canCreate: Boolean get() = !busy && passphraseAccepted(passphrase, confirmation)

    val canRestore: Boolean get() = !busy && restorePassphrase.isNotEmpty()
}

/**
 * Creates and restores encrypted backups (#40), only on the user's tap. The passphrase stays in
 * this state while the user types and is dropped after a successful backup or restore; it is
 * never stored or logged. Failures show fixed texts (#135); the diagnostics log gets the error
 * type and counts only.
 */
class BackupViewModel(
    private val services: AppServices,
    private val timeZone: TimeZone,
    private val clock: NowProvider = NowProvider.System,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(BackupUiState())

    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()

    fun setPassphrase(value: String) = edit { it.copy(passphrase = value, createMessage = null) }

    fun setConfirmation(value: String) = edit { it.copy(confirmation = value, createMessage = null) }

    fun setIncludeAudio(include: Boolean) = edit { it.copy(includeAudio = include) }

    fun setRestorePassphrase(value: String) = edit { it.copy(restorePassphrase = value, restoreMessage = null) }

    fun create() {
        val choice = mutableState.value
        if (!choice.canCreate) return
        mutableState.value = choice.copy(creating = true, createProgress = null, createMessage = null)
        viewModelScope.launch {
            val outcome = try {
                createBackup(choice.passphrase, choice.includeAudio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                services.diagnostics.log(DiagnosticEvents.BACKUP_FAILED, "error=${DiagnosticsLog.typeName(e)}")
                Created(Strings.BACKUP_FAILED, done = false)
            }
            mutableState.update {
                val cleared = if (outcome?.done == true) it.copy(passphrase = "", confirmation = "") else it
                cleared.copy(creating = false, createProgress = null, createMessage = outcome?.message)
            }
        }
    }

    private class Created(val message: String, val done: Boolean)

    /** @return what to show, or null when the user cancelled. */
    private suspend fun createBackup(passphrase: String, includeAudio: Boolean): Created? {
        val writer = services.backupWriter
        val now = clock.now()
        val plan = writer.plan(includeAudio, timeZone, now) ?: return Created(Strings.BACKUP_NOTHING, done = false)
        var summary: ExportSummary? = null
        val saved = services.saveExport(BackupWriter.fileName(now, timeZone), BACKUP_MIME_TYPE) { sink ->
            summary = writer.write(plan, passphrase, sink, onProgress = { progress ->
                mutableState.update { it.copy(createProgress = progress.doneDays.toFloat() / progress.totalDays) }
            })
        }
        val written = summary?.takeIf { saved } ?: return null
        recordSaved(writer, plan, written)
        services.diagnostics.log(
            DiagnosticEvents.BACKUP_CREATED,
            "days=${written.days} chunks=${written.chunks} audio_files=${written.audioFiles} missing_audio=${written.missingAudioFiles}",
        )
        val message = listOfNotNull(
            Strings.backedUp(written.days, written.chunks, written.audioFiles),
            written.missingAudioFiles.takeIf { it > 0 }?.let(Strings::backupMissingAudio),
        ).joinToString("\n")
        return Created(message, done = true)
    }

    /**
     * Marks the audio of the saved backup as backed up, so it may be offloaded (#156). Only after
     * the platform reported the file saved. A failure here does not make the saved backup fail:
     * the audio just stays on the phone (as if never backed up) and the log gets the error type.
     */
    private suspend fun recordSaved(writer: BackupWriter, plan: ExportPlan, summary: ExportSummary) {
        try {
            writer.recordSaved(plan, summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            services.diagnostics.log(DiagnosticEvents.BACKUP_RECORD_FAILED, "error=${DiagnosticsLog.typeName(e)}")
        }
    }

    fun restore() {
        val choice = mutableState.value
        if (!choice.canRestore) return
        mutableState.value = choice.copy(restoring = true, restoreProgress = null, restoreMessage = null)
        viewModelScope.launch {
            var summary: RestoreSummary? = null
            var applying = false
            val message = try {
                val picked = services.openBackup { open ->
                    summary = services.backupRestorer.restore(open, choice.restorePassphrase) { progress ->
                        if (progress is RestoreProgress.Applying) applying = true
                        mutableState.update { it.copy(restoreProgress = progress) }
                    }
                }
                summary?.takeIf { picked }?.let { done ->
                    services.diagnostics.log(
                        DiagnosticEvents.BACKUP_RESTORED,
                        "days=${done.days} added=${done.chunksAdded} present=${done.chunksAlreadyPresent} " +
                            "audio_files=${done.audioFilesRestored} without_audio=${done.chunksWithoutAudio} " +
                            "audio_returned=${done.audioFilesReturned}",
                    )
                    Strings.restored(
                        done.chunksAdded,
                        done.segmentsAdded,
                        done.audioFilesRestored,
                        done.chunksAlreadyPresent,
                        done.audioFilesReturned,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val phase = if (applying) "apply" else "verify"
                services.diagnostics.log(DiagnosticEvents.BACKUP_RESTORE_FAILED, "phase=$phase error=${DiagnosticsLog.typeName(e)}")
                restoreFailure(e, applying)
            }
            mutableState.update {
                val cleared = if (summary != null) it.copy(restorePassphrase = "") else it
                cleared.copy(restoring = false, restoreProgress = null, restoreMessage = message)
            }
        }
    }

    private fun edit(change: (BackupUiState) -> BackupUiState) = mutableState.update { if (it.busy) it else change(it) }

    companion object {
        /** The fixed text for a failed restore; [applying] is true once data was being added. */
        fun restoreFailure(error: Exception, applying: Boolean): String = when {
            applying -> Strings.RESTORE_FAILED_PARTWAY
            error is BackupException.WrongPassphrase -> Strings.RESTORE_WRONG_PASSPHRASE
            error is BackupException.Corrupted -> Strings.RESTORE_CORRUPTED
            error is BackupException.NotABackup -> Strings.RESTORE_NOT_A_BACKUP
            error is BackupException.UnsupportedVersion -> Strings.RESTORE_UNSUPPORTED
            else -> Strings.RESTORE_FAILED
        }
    }
}
