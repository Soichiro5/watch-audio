package app.watchaudio.core.pipeline

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.platform.IoDispatcher
import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.ChunkManifest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Accepts a chunk received from a watch (or recorded on the phone), verifies it, moves the audio
 * into the local archive and records it in the database. Idempotent per chunk id, so the watch
 * can safely resend after a lost ACK.
 *
 * Audio lives under [audioRoot]/yyyy/MM/dd/<chunkId>.<ext> (UTC date); the database stores the
 * path relative to [audioRoot].
 */
class Ingestor(
    private val store: LocalStore,
    private val audioRoot: Path,
    private val clock: Clock = Clock.System,
) {
    /**
     * @param receivedFile temporary file holding the audio, or null for a silent chunk. On success
     *   (and on duplicates) the file is moved or deleted; on failure it is left for the caller.
     */
    suspend fun ingest(manifest: ChunkManifest, receivedFile: Path?): ChunkAck {
        if (manifest.schemaVersion > ChunkManifest.SCHEMA_VERSION) {
            return ack(manifest, AckStatus.REJECTED, "unsupported schema ${manifest.schemaVersion}")
        }
        if (store.read { chunk(manifest.chunkId) } != null) {
            receivedFile?.let(::deleteQuietly)
            return ack(manifest, AckStatus.DUPLICATE)
        }

        val audio = manifest.audio
        val relativePath = if (audio == null) {
            null
        } else {
            if (receivedFile == null) return ack(manifest, AckStatus.CORRUPT, "missing audio file")
            val problem = withContext(IoDispatcher) { verify(receivedFile, audio.sizeBytes, audio.sha256) }
            if (problem != null) return ack(manifest, AckStatus.CORRUPT, problem)
            withContext(IoDispatcher) { archive(manifest, receivedFile, audio.codec) }
        }

        val inserted = store.transaction { insertChunk(manifest, relativePath, clock.now().toEpochMilliseconds()) }
        if (!inserted) {
            // Lost a race with a concurrent delivery of the same chunk; keep the stored copy.
            return ack(manifest, AckStatus.DUPLICATE)
        }
        return ack(manifest, AckStatus.STORED)
    }

    private fun verify(file: Path, expectedSize: Long, expectedSha256: String): String? {
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: return "file not found"
        if (size != expectedSize) return "size $size != $expectedSize"
        val actual = sha256Of(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) return "sha256 mismatch"
        return null
    }

    private fun archive(manifest: ChunkManifest, source: Path, codec: AudioCodec): String {
        val date = Instant.fromEpochMilliseconds(manifest.startedAtEpochMs).toLocalDateTime(TimeZone.UTC).date
        val directory = "${date.year}/${date.month.number.pad2()}/${date.day.pad2()}"
        val extension = when (codec) {
            AudioCodec.AAC -> "m4a"
            AudioCodec.OPUS -> "ogg"
        }
        val relative = "$directory/${manifest.chunkId}.$extension"
        val target = Path(audioRoot, relative)
        SystemFileSystem.createDirectories(Path(audioRoot, directory))
        try {
            SystemFileSystem.atomicMove(source, target)
        } catch (e: kotlinx.io.IOException) {
            // Source on another volume (e.g. a system temp dir): copy, then delete.
            SystemFileSystem.source(source).use { input ->
                SystemFileSystem.sink(target).use { output -> input.buffered().transferTo(output) }
            }
            SystemFileSystem.delete(source)
        }
        return relative
    }

    private fun Int.pad2() = toString().padStart(2, '0')

    private fun deleteQuietly(path: Path) = runCatching { SystemFileSystem.delete(path, mustExist = false) }

    private fun ack(manifest: ChunkManifest, status: AckStatus, detail: String? = null) =
        ChunkAck(chunkId = manifest.chunkId, status = status, detail = detail)
}
