package app.watchaudio.android.transfer

import android.content.Context
import android.util.Log
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.TransferPaths
import app.watchaudio.core.protocol.toJson
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.IOException
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
        val temp = File(incomingDir.apply { mkdirs() }, "${UUID.randomUUID()}.part")
        try {
            if (!isNearby(channel.nodeId)) {
                Log.w(TAG, "ignoring ${channel.path}: node is not directly connected")
                return
            }
            val job = currentCoroutineContext()
            val manifest = try {
                channelClient.getInputStream(channel).await().buffered().use { input ->
                    FrameReader.read(input, temp) { job.ensureActive() }
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "malformed frame on ${channel.path}; no ACK", e)
                return
            } catch (e: IOException) {
                Log.w(TAG, "transfer of ${channel.path} broke off; no ACK", e)
                return
            }
            if (channel.path.removePrefix(TransferPaths.CHUNK_CHANNEL_PREFIX) != manifest.chunkId) {
                Log.w(TAG, "channel ${channel.path} carries chunk ${manifest.chunkId}")
            }

            val ack = ingestor.ingest(manifest, if (manifest.audio != null) Path(temp.path) else null)
            Log.i(TAG, "chunk ${manifest.chunkId}: ${ack.status}${ack.detail?.let { " ($it)" }.orEmpty()}")
            if (ack.status == AckStatus.STORED) onStored()
            sendAck(channel.nodeId, ack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. the database is unavailable: no ACK, the watch resends.
            Log.e(TAG, "could not ingest ${channel.path}", e)
        } finally {
            temp.delete()
            runCatching { channelClient.close(channel).await() }
        }
    }

    private suspend fun isNearby(nodeId: String): Boolean =
        nodeClient.connectedNodes.await().any { it.id == nodeId && it.isNearby }

    private suspend fun sendAck(nodeId: String, ack: ChunkAck) {
        val payload = ack.toJson().encodeToByteArray()
        repeat(ACK_ATTEMPTS) { attempt ->
            try {
                messageClient.sendMessage(nodeId, TransferPaths.ACK_MESSAGE, payload).await()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ACK for ${ack.chunkId} not delivered (attempt ${attempt + 1})", e)
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
