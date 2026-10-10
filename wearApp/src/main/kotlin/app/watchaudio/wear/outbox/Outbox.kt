package app.watchaudio.wear.outbox

import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.decodeManifest
import app.watchaudio.core.protocol.toJson
import app.watchaudio.wear.recording.writeAtomically
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent queue of finalized chunks waiting for the phone's ACK.
 *
 * Layout (one directory, survives process death):
 * - `<chunkId>.m4a`   audio (absent for silent chunks)
 * - `<chunkId>.json`  the [ChunkManifest]; its presence makes the chunk "ready to send"
 * - `<chunkId>.state` send bookkeeping ([SendState]); absent until the first attempt
 *
 * A chunk is deleted only after a final ACK ([AckStatus.isFinal]); everything else keeps it.
 */
class Outbox(
    val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        dir.mkdirs()
    }

    data class Entry(val manifest: ChunkManifest, val audioFile: File?, val state: SendState)

    enum class AckOutcome { DELETED, RESEND, KEPT, UNKNOWN_CHUNK }

    /**
     * Adds a finalized chunk. [audio] (when the manifest has audio) is moved into the outbox under
     * `manifest.audio.fileName`; the manifest is written last so a crash in between leaves no
     * half-registered entry.
     */
    @Synchronized
    fun commit(manifest: ChunkManifest, audio: File?) {
        val audioMeta = manifest.audio
        if (audioMeta != null) {
            val source = requireNotNull(audio) { "manifest has audio but no file given" }
            val target = File(dir, audioMeta.fileName)
            if (source.absoluteFile != target.absoluteFile) {
                target.delete()
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
            }
        } else {
            audio?.delete()
        }
        writeAtomically(manifestFile(manifest.chunkId), manifest.toJson())
    }

    @Synchronized
    fun entries(): List<Entry> = dir.listFiles { f -> f.name.endsWith(MANIFEST_SUFFIX) }.orEmpty()
        .mapNotNull { file ->
            val manifest = runCatching { decodeManifest(file.readText()) }.getOrNull() ?: return@mapNotNull null
            val audio = manifest.audio?.let { File(dir, it.fileName) }
            Entry(manifest, audio, readState(manifest.chunkId))
        }
        .sortedBy { it.manifest.startedAtEpochMs }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(MANIFEST_SUFFIX) }?.size ?: 0

    /** Chunks to send now, oldest first. */
    fun dueForSend(nowMs: Long = clock()): List<Entry> = entries().filter { it.state.nextAttemptAtEpochMs <= nowMs }

    /** Chunks sent but not ACKed yet (still inside the ACK timeout). */
    fun awaitingAck(nowMs: Long = clock()): List<Entry> =
        entries().filter { it.state.lastSentAtEpochMs != null && it.state.nextAttemptAtEpochMs > nowMs && !it.state.rejected }

    /** The chunk went out completely; wait [ACK_TIMEOUT_MS] for the ACK before sending it again. */
    @Synchronized
    fun markSent(chunkId: String) {
        val now = clock()
        val state = readState(chunkId)
        writeState(chunkId, state.copy(attempts = state.attempts + 1, lastSentAtEpochMs = now, nextAttemptAtEpochMs = now + ACK_TIMEOUT_MS))
    }

    /** Sending failed midway (connection lost, phone not reachable): retry with backoff. */
    @Synchronized
    fun markFailed(chunkId: String, detail: String?) {
        val now = clock()
        val state = readState(chunkId)
        val attempts = state.attempts + 1
        writeState(chunkId, state.copy(attempts = attempts, nextAttemptAtEpochMs = now + retryDelayMs(attempts), detail = detail))
    }

    @Synchronized
    fun applyAck(ack: ChunkAck): AckOutcome {
        if (!manifestFile(ack.chunkId).exists()) return AckOutcome.UNKNOWN_CHUNK
        if (ack.status.isFinal) {
            delete(ack.chunkId)
            return AckOutcome.DELETED
        }
        val now = clock()
        val state = readState(ack.chunkId)
        return when (ack.status) {
            AckStatus.CORRUPT -> {
                writeState(ack.chunkId, state.copy(nextAttemptAtEpochMs = now + retryDelayMs(state.attempts), detail = ack.detail))
                AckOutcome.RESEND
            }
            // The phone cannot read this manifest (e.g. it runs an older app). Keep the chunk and
            // try again much later, after the phone app may have been updated.
            else -> {
                writeState(ack.chunkId, state.copy(rejected = true, nextAttemptAtEpochMs = now + REJECTED_RETRY_MS, detail = ack.detail))
                AckOutcome.KEPT
            }
        }
    }

    @Synchronized
    fun delete(chunkId: String) {
        val manifest = runCatching { decodeManifest(manifestFile(chunkId).readText()) }.getOrNull()
        manifest?.audio?.let { File(dir, it.fileName).delete() }
        stateFile(chunkId).delete()
        manifestFile(chunkId).delete()
    }

    private fun readState(chunkId: String): SendState {
        val file = stateFile(chunkId)
        if (!file.exists()) return SendState()
        return runCatching { StateJson.decodeFromString(SendState.serializer(), file.readText()) }.getOrDefault(SendState())
    }

    private fun writeState(chunkId: String, state: SendState) =
        writeAtomically(stateFile(chunkId), StateJson.encodeToString(SendState.serializer(), state))

    private fun manifestFile(chunkId: String) = File(dir, "$chunkId$MANIFEST_SUFFIX")

    private fun stateFile(chunkId: String) = File(dir, "$chunkId$STATE_SUFFIX")

    companion object {
        private const val MANIFEST_SUFFIX = ".json"
        private const val STATE_SUFFIX = ".state"
        private val StateJson = Json { ignoreUnknownKeys = true }

        /** Time to wait for an ACK after a complete send before sending the chunk again. */
        const val ACK_TIMEOUT_MS = 3 * 60_000L
        const val REJECTED_RETRY_MS = 6 * 60 * 60_000L
        private const val MAX_RETRY_DELAY_MS = 30 * 60_000L

        /** 30 s, 1 min, 2 min, ... capped at 30 min. */
        fun retryDelayMs(attempts: Int): Long {
            val shift = (attempts - 1).coerceIn(0, 10)
            return minOf(30_000L shl shift, MAX_RETRY_DELAY_MS)
        }
    }
}

@Serializable
data class SendState(
    val attempts: Int = 0,
    val lastSentAtEpochMs: Long? = null,
    val nextAttemptAtEpochMs: Long = 0,
    val rejected: Boolean = false,
    val detail: String? = null,
)
