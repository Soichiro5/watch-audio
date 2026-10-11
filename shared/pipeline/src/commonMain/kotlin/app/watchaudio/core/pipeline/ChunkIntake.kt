package app.watchaudio.core.pipeline

import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.decodeManifest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Where a chunk from the watch enters the phone, the same on Android (Wear OS `ChannelClient`)
 * and iOS (WatchConnectivity); the platforms only read the transport and send the ACK (#134).
 *
 * - An unreadable manifest is answered with [AckStatus.REJECTED] when its chunk id can still be
 *   read: both watches then keep the chunk and offer it again much later (Wear OS after 6 hours,
 *   watchOS on its next activation) instead of resending it every few minutes. Without a chunk
 *   id there is nobody to answer, so there is no ACK.
 * - A stored chunk is logged to [diagnostics] and handed to [onStored] (ask for transcription,
 *   re-arm the gap alert); the ACK goes back whatever the outcome.
 * - If the chunk cannot be ingested at all (e.g. the database is unavailable), there is no ACK:
 *   the watch keeps it and resends, and the next delivery is ingested idempotently.
 * - The received audio file is always gone afterwards: moved into the archive, or deleted.
 *
 * Privacy: the diagnostics lines hold ids, sizes and error types, never audio or text.
 */
class ChunkIntake(
    private val ingestor: Ingestor,
    private val diagnostics: Diagnostics,
    private val onStored: suspend (ChunkManifest) -> Unit,
) {
    /** A manifest as received: usable, or [Unreadable] with the reply for the watch. */
    sealed interface Decoded {
        data class Valid(val manifest: ChunkManifest) : Decoded

        /** [ack] is a REJECTED answer, or null if not even the chunk id could be read. */
        data class Unreadable(val ack: ChunkAck?) : Decoded
    }

    /** What happened to one chunk; [ack] is what to send back (null: send nothing, the watch resends). */
    sealed interface Result {
        val ack: ChunkAck?

        /** Ingested; the ACK says how (stored, duplicate, corrupt or rejected). */
        data class Answered(override val ack: ChunkAck) : Result

        /** The manifest could not be read (see [Decoded.Unreadable]). */
        data class Unreadable(override val ack: ChunkAck?) : Result

        /** Ingesting failed; [error] is for the platform's log and status. */
        data class Failed(val error: Exception) : Result {
            override val ack: ChunkAck? get() = null
        }
    }

    /** Decodes a manifest; an unreadable one is logged as `chunk_rejected reason=malformed_manifest`. */
    fun decode(manifestJson: String): Decoded = try {
        Decoded.Valid(decodeManifest(manifestJson))
    } catch (e: Exception) {
        // IllegalArgumentException (incl. SerializationException) for bad JSON or invalid fields.
        val chunkId = chunkIdOf(manifestJson)
        diagnostics.log(
            DiagnosticEvents.CHUNK_REJECTED,
            (chunkId?.let { "chunk=$it " }.orEmpty()) + "reason=malformed_manifest error=${DiagnosticsLog.typeName(e)}",
        )
        Decoded.Unreadable(
            chunkId?.let { ChunkAck(chunkId = it, status = AckStatus.REJECTED, detail = e.message?.take(MAX_DETAIL_LENGTH)) },
        )
    }

    /** [decode]s [manifestJson] and [accept]s it; for transports that deliver manifest and file together. */
    suspend fun receive(manifestJson: String, receivedFile: Path?): Result = when (val decoded = decode(manifestJson)) {
        is Decoded.Valid -> accept(decoded.manifest, receivedFile)
        is Decoded.Unreadable -> {
            receivedFile?.let(::deleteQuietly)
            Result.Unreadable(decoded.ack)
        }
    }

    /**
     * Ingests [manifest] with its audio in [receivedFile] (null for a silent chunk), logs it and,
     * once stored, calls [onStored]. [receivedFile] is moved into the archive or deleted.
     */
    suspend fun accept(manifest: ChunkManifest, receivedFile: Path?): Result {
        val ack = try {
            ingestor.ingest(manifest, receivedFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.log(
                DiagnosticEvents.CHUNK_REJECTED,
                "chunk=${manifest.chunkId} reason=ingest_failed error=${DiagnosticsLog.typeName(e)}",
            )
            return Result.Failed(e)
        } finally {
            // Moved into the archive when stored; anything else (duplicate, corrupt, failure) is dropped.
            receivedFile?.let(::deleteQuietly)
        }
        diagnostics.log(
            DiagnosticEvents.CHUNK_RECEIVED,
            "chunk=${manifest.chunkId} status=${ack.status} audio_ms=${manifest.durationMs} " +
                "bytes=${manifest.audio?.sizeBytes ?: 0}${ack.detail?.let { " detail=$it" }.orEmpty()}",
        )
        if (ack.status == AckStatus.STORED) onStored(manifest)
        return Result.Answered(ack)
    }

    private fun deleteQuietly(path: Path) {
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }

    companion object {
        private const val MAX_DETAIL_LENGTH = 200

        /** The `chunkId` of a manifest that does not decode as a whole, if it has a usable one. */
        fun chunkIdOf(manifestJson: String): String? = runCatching {
            (Json.parseToJsonElement(manifestJson).jsonObject["chunkId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
