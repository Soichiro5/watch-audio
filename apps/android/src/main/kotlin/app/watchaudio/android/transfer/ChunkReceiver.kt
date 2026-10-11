package app.watchaudio.android.transfer

import android.content.Context
import android.util.Log
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.DiagnosticsLog
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.TransferPaths
import app.watchaudio.core.protocol.toJson
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.io.files.Path

/**
 * Receives framed chunks from the watch over `ChannelClient`, ingests them and replies with a
 * [ChunkAck] over `MessageClient`.
 *
 * Only directly connected ("nearby") watches are served, so audio and ACKs never take the
 * Data Layer's cloud route. A frame that cannot be parsed, or a transfer that breaks off, gets no
 * ACK at all: the watch keeps the chunk and resends it later, and ingest is idempotent.
 */
class ChunkReceiver(
    context: Context,
    private val ingestor: Ingestor,
    private val scope: CoroutineScope,
    private val diagnostics: Diagnostics,
    private val onStored: () -> Unit,
) {
    private val channelClient = Wearable.getChannelClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val incomingDir = File(context.cacheDir, "incoming")
    private val permits = Semaphore(MAX_CONCURRENT)

    fun receive(channel: ChannelClient.Channel) {
        scope.launch(Dispatchers.IO) { permits.withPermit { handle(channel) } }
    }

    private suspend fun handle(channel: ChannelClient.Channel) {
        try {
            if (!isNearby(channel.nodeId)) {
                Log.w(TAG, "ignoring ${channel.path}: node is not directly connected")
                diagnostics.log(DiagnosticEvents.CHUNK_REJECTED, "path=${channel.path} reason=not_nearby")
                return
            }
            val ack = channelClient.getInputStream(channel).await().buffered().use { input -> store(channel.path, input) }
            if (ack != null) sendAck(channel.nodeId, ack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. the database is unavailable: no ACK, the watch resends.
            Log.e(TAG, "could not ingest ${channel.path}", e)
            diagnostics.log(
                DiagnosticEvents.CHUNK_REJECTED,
                "path=${channel.path} reason=ingest_failed error=${DiagnosticsLog.typeName(e)}",
            )
        } finally {
            runCatching { channelClient.close(channel).await() }
        }
    }

    /**
     * Reads one framed chunk from [input] (the body of the channel [path]), ingests it, logs it to
     * the diagnostics log and, once it is stored, asks for transcription. The channel-independent
     * part of a transfer, so the emulator checks can drive it without a watch (#77).
     *
     * @return the ACK to send, or null if the frame was malformed or broke off (no ACK: the watch resends).
     * @throws Exception if the chunk could not be ingested (also no ACK).
     */
    suspend fun store(path: String, input: InputStream): ChunkAck? {
        val temp = File(incomingDir.apply { mkdirs() }, "${UUID.randomUUID()}.part")
        try {
            val job = currentCoroutineContext()
            val manifest = try {
                FrameReader.read(input, temp) { job.ensureActive() }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "malformed frame on $path; no ACK", e)
                diagnostics.log(DiagnosticEvents.CHUNK_REJECTED, "path=$path reason=malformed_frame")
                return null
            } catch (e: IOException) {
                Log.w(TAG, "transfer of $path broke off; no ACK", e)
                diagnostics.log(
                    DiagnosticEvents.CHUNK_REJECTED,
                    "path=$path reason=transfer_broke_off error=${DiagnosticsLog.typeName(e)}",
                )
                return null
            }
            if (path.removePrefix(TransferPaths.CHUNK_CHANNEL_PREFIX) != manifest.chunkId) {
                Log.w(TAG, "channel $path carries chunk ${manifest.chunkId}")
            }

            val ack = ingestor.ingest(manifest, if (manifest.audio != null) Path(temp.path) else null)
            Log.i(TAG, "chunk ${manifest.chunkId}: ${ack.status}${ack.detail?.let { " ($it)" }.orEmpty()}")
            diagnostics.log(
                DiagnosticEvents.CHUNK_RECEIVED,
                "chunk=${manifest.chunkId} status=${ack.status} audio_ms=${manifest.durationMs} " +
                    "bytes=${manifest.audio?.sizeBytes ?: 0}${ack.detail?.let { " detail=$it" }.orEmpty()}",
            )
            if (ack.status == AckStatus.STORED) onStored()
            return ack
        } finally {
            temp.delete()
        }
    }

    private suspend fun isNearby(nodeId: String): Boolean =
        nodeClient.connectedNodes.await().any { it.id == nodeId && it.isNearby }

    private suspend fun sendAck(nodeId: String, ack: ChunkAck) {
        val payload = ack.toJson().encodeToByteArray()
        repeat(ACK_ATTEMPTS) { attempt ->
            try {
                messageClient.sendMessage(nodeId, TransferPaths.ACK_MESSAGE, payload).await()
                diagnostics.log(DiagnosticEvents.ACK_SENT, "chunk=${ack.chunkId} status=${ack.status} attempt=${attempt + 1}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ACK for ${ack.chunkId} not delivered (attempt ${attempt + 1})", e)
                diagnostics.log(
                    DiagnosticEvents.ACK_FAILED,
                    "chunk=${ack.chunkId} attempt=${attempt + 1} error=${DiagnosticsLog.typeName(e)}",
                )
                delay(1_000L shl attempt)
            }
        }
        // The watch resends the chunk and gets a DUPLICATE ACK then.
    }

    private companion object {
        const val TAG = "ChunkReceiver"
        const val MAX_CONCURRENT = 2
        const val ACK_ATTEMPTS = 3
    }
}
