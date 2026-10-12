package app.watchaudio.core.backup

import app.watchaudio.core.db.ChunkBackupInfo
import app.watchaudio.core.export.ExportJson
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// The plaintext of a backup (#40) is an export archive (#155, all days) with these extra JSON
// files, so a decrypted backup is also a complete export: `backup/<date>.json` ([BackupDay])
// right after each `json/<date>.json`, and `backup.json` ([BackupIndex]) at the end. Like the
// export JSON, the format only grows: readers ignore unknown keys, and a change older readers
// would misread raises the version. Each file states the oldest version that reads it correctly:
// a diff backup's `backup.json` is version 2 (an app that reads only version 1 says it needs an
// update instead of failing on the unknown kind), everything else stays version 1 (older apps
// still restore full backups; they ignore `updatedAt`). Spec: docs/architecture/02 §6.7.

/** Value of `format` in the backup's own JSON files. */
const val BACKUP_FORMAT = "watch-audio-backup"

/**
 * Newest version of the backup's JSON files this app reads (the container has its own,
 * [BackupCipher.FORMAT_VERSION]). Files are written with [BackupKind.formatVersion].
 */
const val BACKUP_FORMAT_VERSION = 2

/** Extension of backup files. */
const val BACKUP_FILE_EXTENSION = "wabackup"

/** What a backup covers. */
@Serializable
enum class BackupKind(
    /** Version of the backup's `backup.json`: the oldest reader that restores this kind correctly. */
    val formatVersion: Int,
) {
    /** Every chunk on the device when the backup was made. */
    @SerialName("full") FULL(1),

    /**
     * The chunks added or changed since the previous backup saved on the device ([BackupIndex.base],
     * #164), and the audio on the device that was in no saved backup yet. Restored on top of the
     * full backup and the diffs before it (in any order).
     */
    @SerialName("diff") DIFF(2),
}

/** `backup.json`: written last, with the totals of the backup. */
@Serializable
data class BackupIndex(
    val format: String = BACKUP_FORMAT,
    /** [kind]'s [BackupKind.formatVersion]. */
    val formatVersion: Int = 1,
    /** When the backup was made (epoch ms). */
    val createdAt: Long,
    val kind: BackupKind = BackupKind.FULL,
    val includesAudio: Boolean,
    val days: Int,
    val chunks: Int,
    val segments: Int,
    val marks: Int,
    val audioFiles: Int,
    /** For a [BackupKind.DIFF]: the saved backups it builds on. */
    val base: BackupBase? = null,
)

/** The saved backups a diff builds on (#164), as the device that wrote it knew them. */
@Serializable
data class BackupBase(
    /** Creation time of the previous saved backup (full or diff): the diff holds what changed after it. */
    val createdAt: Long,
    /** Creation time of the full backup at the start of the chain. */
    val fullCreatedAt: Long,
    /** Diffs saved between that full backup and this diff. */
    val diffsBefore: Int,
)

/** `backup/<date>.json`: what the backup keeps of the chunks of `json/<date>.json` beyond the export. */
@Serializable
data class BackupDay(
    val format: String = BACKUP_FORMAT,
    val formatVersion: Int = 1,
    val date: LocalDate,
    val chunks: List<BackupChunk>,
)

/** One chunk of [BackupDay]. */
@Serializable
data class BackupChunk(
    /** The chunk's UUID, as in the export. */
    val id: String,
    /** When the phone received the chunk from the watch (epoch ms). */
    val receivedAt: Long,
    /** The manifest as the watch sent it (`shared/protocol` `ChunkManifest`), if it could be read. */
    val manifest: JsonObject? = null,
    /**
     * When the chunk's record (state, transcript, marks) last changed on the device that wrote the
     * backup (epoch ms, #164), or null if unknown (backups written before #164). Of two backups
     * holding the chunk, a restore keeps the transcript of the newer version.
     */
    val updatedAt: Long? = null,
)

/** The [BackupDay] of [date] from the chunks' [ChunkBackupInfo] by UUID, in [ids] order. */
internal fun backupDay(date: LocalDate, ids: List<String>, info: Map<String, ChunkBackupInfo>): BackupDay = BackupDay(
    date = date,
    chunks = ids.mapNotNull { id ->
        info[id]?.let {
            BackupChunk(id, it.receivedAt, runCatching { ExportJson.parseToJsonElement(it.manifestJson).jsonObject }.getOrNull(), it.updatedAt)
        }
    },
)

fun BackupIndex.toJson(): String = ExportJson.encodeToString(BackupIndex.serializer(), this)

fun BackupDay.toJson(): String = ExportJson.encodeToString(BackupDay.serializer(), this)

/** Reads `backup.json`. @throws IllegalArgumentException if it is not one this version can read. */
fun decodeBackupIndex(json: String): BackupIndex =
    ExportJson.decodeFromString(BackupIndex.serializer(), json).also { checkBackupFormat(it.format, it.formatVersion) }

/** Reads a `backup/<date>.json` file. @throws IllegalArgumentException if it is not one this version can read. */
fun decodeBackupDay(json: String): BackupDay =
    ExportJson.decodeFromString(BackupDay.serializer(), json).also { checkBackupFormat(it.format, it.formatVersion) }

private fun checkBackupFormat(format: String, version: Int) {
    require(format == BACKUP_FORMAT) { "Not a $BACKUP_FORMAT file: $format" }
    require(version in 1..BACKUP_FORMAT_VERSION) { "Unsupported $BACKUP_FORMAT version $version" }
}

/** Archive path (under the root folder) of `backup.json`. */
const val BACKUP_INDEX_ENTRY = "backup.json"

/** Archive path (under the root folder) of the [BackupDay] of [date]. */
fun backupDayEntry(date: LocalDate): String = "backup/$date.json"
