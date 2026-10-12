package app.watchaudio.core.backup

import app.watchaudio.core.db.AudioFileInfo
import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.RestoredChunk
import app.watchaudio.core.db.RestoredSegment
import app.watchaudio.core.export.ExportChunk
import app.watchaudio.core.export.ExportDay
import app.watchaudio.core.export.ExportSegment
import app.watchaudio.core.export.ExportTranscription
import app.watchaudio.core.export.ZipFormatException
import app.watchaudio.core.export.ZipStreamReader
import app.watchaudio.core.export.decodeExportDay
import app.watchaudio.core.export.decodeExportIndex
import app.watchaudio.core.export.toSafeFileName
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.platform.IoDispatcher
import app.watchaudio.core.platform.Sha256
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.EOFException
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** What a restore did. */
data class RestoreSummary(
    /** Days in the backup. */
    val days: Int,
    /** Chunks in the backup that were not on this device and were added. */
    val chunksAdded: Int,
    /** Chunks in the backup that were already on this device (kept, see [BackupRestorer]). */
    val chunksAlreadyPresent: Int,
    /** Transcript segments added (of new chunks, and of stored chunks that had no transcript yet). */
    val segmentsAdded: Int,
    val marksAdded: Int,
    val audioFilesRestored: Int,
    /** Added chunks with recorded audio that is not on this device (not in the backup, or deleted before it). */
    val chunksWithoutAudio: Int,
    /**
     * Audio files brought back for chunks that were already on this device without their audio
     * (offloaded, #156, or deleted by retention).
     */
    val audioFilesReturned: Int = 0,
)

/** Where [BackupRestorer.restore] is. */
sealed interface RestoreProgress {
    /** Checking the passphrase and the whole file; nothing is written yet. */
    data object Verifying : RestoreProgress

    /** Adding the data: [doneDays] of [totalDays] days are done. */
    data class Applying(val doneDays: Int, val totalDays: Int) : RestoreProgress
}

/**
 * Restores an encrypted backup (#40) written by [BackupWriter], on any platform.
 *
 * The file is read twice. The first pass derives the keys, authenticates every chunk to the end
 * and checks the content, writing nothing: a wrong passphrase, a damaged or truncated file, or a
 * file from a newer app version fails here with a [BackupException] and the device is untouched.
 * The second pass adds the data day by day, each day in one transaction.
 *
 * Merging is idempotent by chunk UUID, like receiving a chunk again from the watch:
 * - a chunk that is not stored is added with its transcript, marks and (if included) audio;
 * - a stored chunk is kept as it is, so newer local data is never overwritten. It only gains the
 *   backup's marks it lacks, the backup's transcript if it has none yet (not transcribed), and
 *   the backup's audio file if its own is no longer on the device (offloaded, #156, or deleted by
 *   retention) and the backup's copy is the same file (size and SHA-256).
 *
 * Audio the backup holds counts as backed up at the backup's creation time
 * ([app.watchaudio.core.db.ChunkRecord.audioBackedUpAt]): the user has that file.
 * Restoring the same backup again therefore adds nothing. If the second pass fails part way
 * (e.g. the storage is full), the days already added stay complete and restoring again adds the rest.
 *
 * Audio goes where the ingest would put it ([Ingestor.archivePath]); the timeline's gaps follow
 * from the chunks, so they come back as they were.
 */
class BackupRestorer(
    private val store: LocalStore,
    private val audioRoot: Path,
    private val clock: Clock = Clock.System,
    /** A restored failed chunk counts as having used its attempts, so it is only retried on request. */
    private val maxAttempts: Int = TranscriptionPipeline.DEFAULT_MAX_ATTEMPTS,
    private val dispatcher: CoroutineDispatcher = IoDispatcher,
) {
    /**
     * Restores the backup that [open] reads (called once per pass; it must return the same bytes
     * each time) with [passphrase].
     * @throws BackupException if the file cannot be restored; nothing was changed then.
     * @throws kotlinx.io.IOException if reading the file or writing the data fails.
     */
    suspend fun restore(open: () -> RawSource, passphrase: String, onProgress: (RestoreProgress) -> Unit = {}): RestoreSummary =
        withContext(dispatcher) {
            onProgress(RestoreProgress.Verifying)
            var header: BackupHeader? = null
            var keys: BackupKeys? = null
            try {
                val verified = open().buffered().use { source ->
                    val read = BackupCipher.readHeader(source)
                    header = read
                    val unlocked = BackupCipher.unlock(read, passphrase)
                    keys = unlocked
                    corruptedOnBadContent { walk(BackupCipher.decrypt(source, read, unlocked).buffered(), apply = null) }
                }
                onProgress(RestoreProgress.Applying(0, verified.days))
                open().buffered().use { source ->
                    val again = BackupCipher.readHeader(source)
                    if (!again.sameAs(header!!)) throw BackupException.Corrupted("the file changed between the passes")
                    val applier = Applier(clock.now().toEpochMilliseconds(), verified.createdAt) { done ->
                        onProgress(RestoreProgress.Applying(done, verified.days))
                    }
                    try {
                        corruptedOnBadContent { walk(BackupCipher.decrypt(source, again, keys!!).buffered(), applier) }
                    } finally {
                        withContext(NonCancellable) { deleteTree(stagingDir) }
                    }
                    applier.summary(verified.days)
                }
            } finally {
                keys?.wipe()
            }
        }

    /** Counts of a verified backup, and when it was created ([BackupIndex.createdAt]). */
    private class Walked(val days: Int, val createdAt: Long)

    /** An audio entry of the current day: its staged copy (second pass only). */
    private class Staged(val path: Path?, val size: Long, val sha256: String?)

    /**
     * Reads the archive in [plain] to the end (so that the final chunk is authenticated) and
     * checks every file. With an [apply], also adds each day once its files are read.
     */
    private suspend fun walk(plain: Source, apply: Applier?): Walked {
        val zip = ZipStreamReader(plain)
        var root: String? = null
        var days = 0
        var pendingDay: ExportDay? = null
        var index: BackupIndex? = null
        var sawExportIndex = false
        val staged = mutableMapOf<String, Staged>()
        var stagedCount = 0
        apply?.let { withContext(NonCancellable) { deleteTree(stagingDir) } }
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.next() ?: break
            val top = entry.name.substringBefore('/')
            if (root == null) root = top
            if (top != root || '/' !in entry.name) throw ZipFormatException("entry outside the root folder")
            val name = entry.name.substringAfter('/')
            when {
                name.startsWith("audio/") -> {
                    staged[name] = if (apply == null) {
                        zip.read { _, _ -> }
                        Staged(null, entry.size, null)
                    } else {
                        SystemFileSystem.createDirectories(stagingDir)
                        val path = Path(stagingDir, "${stagedCount++}.part")
                        val sha = Sha256()
                        SystemFileSystem.sink(path).buffered().use { out: Sink ->
                            zip.read { buffer, n ->
                                sha.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                            }
                        }
                        Staged(path, entry.size, sha.digestHex())
                    }
                }
                name.startsWith("json/") -> {
                    if (pendingDay != null) throw ZipFormatException("day without backup data")
                    pendingDay = decodeExportDay(zip.readBytes(MAX_JSON_BYTES).decodeToString())
                }
                name.startsWith("backup/") -> {
                    val day = pendingDay ?: throw ZipFormatException("backup data without a day")
                    val extras = decodeBackupDay(zip.readBytes(MAX_JSON_BYTES).decodeToString())
                    checkDay(day, extras)
                    apply?.apply(day, extras, staged)
                    staged.values.forEach { it.path?.let(::deleteQuietly) }
                    staged.clear()
                    pendingDay = null
                    days++
                }
                name == "export.json" -> {
                    decodeExportIndex(zip.readBytes(MAX_JSON_BYTES).decodeToString())
                    sawExportIndex = true
                }
                name == BACKUP_INDEX_ENTRY -> index = decodeBackupIndex(zip.readBytes(MAX_JSON_BYTES).decodeToString())
                // Markdown, README and files of newer versions are not needed to restore.
                else -> Unit
            }
        }
        zip.drain()
        if (pendingDay != null) throw ZipFormatException("day without backup data")
        if (index == null || !sawExportIndex) throw ZipFormatException("no index")
        if (index.days != days) throw ZipFormatException("index lists ${index.days} days, found $days")
        return Walked(days, index.createdAt)
    }

    /** Fails if [extras] does not belong to [day] or the day refers to chunks it does not hold. */
    private fun checkDay(day: ExportDay, extras: BackupDay) {
        if (extras.date != day.date) throw ZipFormatException("backup data of another day")
        val ids = day.chunks.map { it.id }.toSet()
        if (ids.size != day.chunks.size) throw ZipFormatException("duplicate chunk")
        if (day.segments.any { it.chunkId !in ids } || day.marks.any { it.chunkId !in ids } || extras.chunks.any { it.id !in ids }) {
            throw ZipFormatException("reference to a chunk of another day")
        }
    }

    /** Adds verified days to the store (second pass). */
    private inner class Applier(private val now: Long, private val backupCreatedAt: Long, private val onDay: (Int) -> Unit) {
        private var days = 0
        private var added = 0
        private var present = 0
        private var segments = 0
        private var marks = 0
        private var audioFiles = 0
        private var withoutAudio = 0
        private var returned = 0

        suspend fun apply(day: ExportDay, extras: BackupDay, staged: Map<String, Staged>) {
            val extraById = extras.chunks.associateBy { it.id }
            val (stored, storedFiles) = store.read {
                day.chunks.mapNotNull { chunk(it.id) }.associateBy { it.id } to
                    audioFilesOfChunksStartedBetween(day.dayStart, day.dayEnd)
            }
            val segmentsByChunk = day.segments.groupBy { it.chunkId }
            val marksByChunk = day.marks.groupBy({ it.chunkId }, { it.at })
            val moved = mutableListOf<Path>()
            val newChunks = day.chunks.filter { it.id !in stored }.map { chunk ->
                val audio = chunk.audio
                val relative = audio?.let { Ingestor.archivePath(chunk.id.toSafeFileName(), chunk.startedAt, extensionOf(chunk)) }
                val file = audio?.file?.let { staged[it] }?.takeIf { it.matches(chunk) }
                if (relative != null && file?.path != null) {
                    val target = Path(audioRoot, relative)
                    SystemFileSystem.createDirectories(target.parent!!)
                    SystemFileSystem.atomicMove(file.path, target)
                    moved += target
                }
                restoredChunk(chunk, relative, audioRestored = relative != null && file != null, extraById[chunk.id])
            }
            // Stored chunks whose audio the backup holds: back on the device if it was not, and
            // in any case known to be in this backup.
            val inBackup = mutableListOf<String>()
            val returnedIds = mutableListOf<String>()
            stored.values.forEach { local ->
                val backup = day.chunks.first { it.id == local.id }
                val file = backup.audio?.file?.let { staged[it] }?.takeIf { it.matches(backup) && it.sameAs(local, storedFiles[local.id]) }
                    ?: return@forEach
                val relative = local.audioPath ?: return@forEach
                if (local.hasAudioFile) {
                    inBackup += local.id
                    return@forEach
                }
                val target = Path(audioRoot, relative)
                if (file.path == null || SystemFileSystem.exists(target)) return@forEach
                SystemFileSystem.createDirectories(target.parent!!)
                SystemFileSystem.atomicMove(file.path, target)
                moved += target
                returnedIds += local.id
            }
            try {
                store.transaction {
                    newChunks.forEach { chunk ->
                        if (!insertRestoredChunk(chunk)) return@forEach
                        added++
                        if (chunk.audioPath != null) {
                            if (chunk.audioPurgedAt == null) audioFiles++ else withoutAudio++
                        }
                        val restored = segmentsByChunk[chunk.id].orEmpty().map { it.toRestored() }
                        addSegments(chunk.id, restored)
                        segments += restored.size
                        marks += addMarks(chunk.id, marksByChunk[chunk.id].orEmpty())
                    }
                    if (inBackup.isNotEmpty()) markAudioBackedUp(inBackup, backupCreatedAt)
                    returnedIds.forEach { id -> if (markAudioRestored(id, backupCreatedAt)) returned++ }
                    stored.values.forEach { local ->
                        present++
                        marks += addMarks(local.id, marksByChunk[local.id].orEmpty())
                        val backup = day.chunks.first { it.id == local.id }
                        if (local.state.hasNoTranscript && backup.transcription == ExportTranscription.DONE) {
                            val restored = segmentsByChunk[local.id].orEmpty().map { it.toRestored() }
                            replaceTranscript(local.id, restored)
                            segments += restored.size
                        }
                    }
                }
            } catch (e: Throwable) {
                // Files whose rows were not committed would be orphans.
                withContext(NonCancellable) { moved.forEach(::deleteQuietly) }
                throw e
            }
            onDay(++days)
        }

        fun summary(totalDays: Int) = RestoreSummary(
            days = totalDays,
            chunksAdded = added,
            chunksAlreadyPresent = present,
            segmentsAdded = segments,
            marksAdded = marks,
            audioFilesRestored = audioFiles,
            chunksWithoutAudio = withoutAudio,
            audioFilesReturned = returned,
        )

        private fun restoredChunk(chunk: ExportChunk, audioPath: String?, audioRestored: Boolean, extra: BackupChunk?): RestoredChunk {
            val audio = chunk.audio
            val state = when {
                audio == null -> ChunkState.NO_AUDIO
                chunk.transcription == ExportTranscription.DONE -> ChunkState.TRANSCRIBED
                chunk.transcription == ExportTranscription.PENDING && audioRestored -> ChunkState.RECEIVED
                else -> ChunkState.FAILED
            }
            return RestoredChunk(
                id = chunk.id,
                sessionId = chunk.sessionId,
                deviceId = chunk.deviceId,
                source = chunk.source,
                startedAt = chunk.startedAt,
                endedAt = chunk.endedAt,
                timeZone = chunk.timeZone,
                audioPath = audioPath,
                codec = audio?.codec,
                sizeBytes = audio?.sizeBytes,
                sha256 = audio?.sha256,
                endReason = chunk.endReason,
                state = state,
                attempts = if (state == ChunkState.FAILED) maxAttempts else 0,
                lastError = when {
                    state != ChunkState.FAILED -> null
                    audioRestored -> ERROR_FAILED_BEFORE_BACKUP
                    else -> ERROR_AUDIO_NOT_RESTORED
                },
                receivedAt = extra?.receivedAt ?: now,
                manifestJson = extra?.manifest?.toString() ?: "{}",
                audioPurgedAt = when {
                    audio == null -> null
                    audio.purgedAt != null -> audio.purgedAt
                    audioRestored -> null
                    else -> now
                },
                audioBackedUpAt = when {
                    audio == null -> null
                    audioRestored -> maxOf(backupCreatedAt, audio.backedUpAt ?: backupCreatedAt)
                    else -> audio.backedUpAt
                },
            )
        }
    }

    private val stagingDir = Path(audioRoot, STAGING_DIR)

    private fun deleteTree(target: Path) {
        val metadata = SystemFileSystem.metadataOrNull(target) ?: return
        if (metadata.isDirectory) SystemFileSystem.list(target).forEach(::deleteTree)
        runCatching { SystemFileSystem.delete(target, mustExist = false) }
    }

    private fun deleteQuietly(path: Path) {
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }

    companion object {
        /** Folder under the audio root where the second pass stages audio until its day is added. */
        const val STAGING_DIR = ".restore"

        /** Largest JSON file read into memory (a day file is a few MB even on a busy day). */
        private const val MAX_JSON_BYTES = 256 * 1024 * 1024

        /** `last_error` of a restored chunk that had failed on the device that wrote the backup. */
        const val ERROR_FAILED_BEFORE_BACKUP = "failed before the backup"

        /** `last_error` of a restored chunk that was waiting for transcription, without its audio. */
        const val ERROR_AUDIO_NOT_RESTORED = "audio not in the backup"

        private val ChunkState.hasNoTranscript: Boolean
            get() = this == ChunkState.RECEIVED || this == ChunkState.TRANSCRIBING || this == ChunkState.FAILED

        private fun extensionOf(chunk: ExportChunk): String = chunk.audio?.codec?.fileExtension
            ?: chunk.audio?.file?.substringAfterLast('/')?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.toSafeFileName()
            ?: "bin"

        private fun Staged.matches(chunk: ExportChunk): Boolean {
            val audio = chunk.audio ?: return false
            if (audio.sizeBytes != null && audio.sizeBytes != size) return false
            return audio.sha256 == null || sha256 == null || sha256.equals(audio.sha256, ignoreCase = true)
        }

        /** The staged file is the stored chunk's own audio ([stored] = its codec and checksum on this device). */
        private fun Staged.sameAs(local: ChunkRecord, stored: AudioFileInfo?): Boolean {
            if (local.sizeBytes != null && local.sizeBytes != size) return false
            return stored == null || sha256 == null || sha256.equals(stored.sha256, ignoreCase = true)
        }

        private fun ExportSegment.toRestored() = RestoredSegment(startAt, endAt, text, lang, engine)

        /** Content problems found after authentication (a writer bug or a crafted file) are corruption too. */
        private inline fun <T> corruptedOnBadContent(block: () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipFormatException) {
            throw BackupException.Corrupted(e.message ?: "archive")
        } catch (e: EOFException) {
            throw BackupException.Corrupted("archive ends early")
        } catch (e: IllegalArgumentException) {
            // Includes kotlinx.serialization's SerializationException. The decoders reject a newer
            // format version with "Unsupported <format> version <n>" (ExportFormat, BackupFormat).
            throw if (e.message?.startsWith("Unsupported ") == true) {
                BackupException.UnsupportedVersion(e.message!!)
            } else {
                BackupException.Corrupted("content")
            }
        }
    }
}
