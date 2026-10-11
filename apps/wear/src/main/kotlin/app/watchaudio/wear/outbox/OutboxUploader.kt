package app.watchaudio.wear.outbox

import android.content.Context
import app.watchaudio.core.protocol.TransferPaths
import app.watchaudio.wear.AppGraph
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Sends outbox chunks to the phone over a `ChannelClient` stream, one channel per chunk
 * (`/watch-audio/chunk/<chunkId>`, payload = [app.watchaudio.core.protocol.ChunkFrame]).
 *
 * Local only: chunks go exclusively to a *nearby* node (`Node.isNearby`, i.e. connected directly
 * over Bluetooth/Wi-Fi). `DataClient` is never used because data items can be synced through
 * Google's servers. The phone's ACK arrives separately ([DataLayerListenerService]); until then
 * the chunk stays in the outbox.
 */
class OutboxUploader(context: Context, private val graph: AppGraph) {
    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val channelClient = Wearable.getChannelClient(context)

    /** Serializes the service's immediate attempts and WorkManager runs (same process). */
    private val mutex = Mutex()

    sealed interface Outcome {
        /** Nothing left to send right now (but ACKs may still be outstanding). */
        data class Done(val sent: Int, val awaitingAck: Int) : Outcome
        data class NoPhone(val pending: Int) : Outcome
        data class Failed(val sent: Int, val error: String) : Outcome
        data object Disabled : Outcome
    }

    suspend fun drain(): Outcome = mutex.withLock {
        if (!graph.prefs.uploadEnabled) return Outcome.Disabled
        var sent = 0
        while (true) {
            val due = graph.outbox.dueForSend()
            if (due.isEmpty()) break
            val phone = findPhone() ?: return Outcome.NoPhone(graph.outbox.count())
            for (entry in due) {
                val id = entry.manifest.chunkId
                try {
                    send(phone, entry)
                    graph.outbox.markSent(id)
                    sent++
                    graph.log("chunk_sent", "$id bytes=${entry.manifest.audio?.sizeBytes ?: 0} to=${phone.displayName}")
                } catch (e: Exception) {
                    graph.outbox.markFailed(id, e.toString())
                    graph.log("send_failed", "$id $e")
                    // The connection is most likely gone; let the scheduler retry later.
                    return Outcome.Failed(sent, e.toString())
                }
            }
        }
        Outcome.Done(sent, graph.outbox.awaitingAck().size)
    }

    private suspend fun send(node: Node, entry: Outbox.Entry) {
        val path = TransferPaths.CHUNK_CHANNEL_PREFIX + entry.manifest.chunkId
        val channel = withTimeout(OPEN_TIMEOUT_MS) { channelClient.openChannel(node.id, path).await() }
        try {
            val stream = channelClient.getOutputStream(channel).await()
            withContext(Dispatchers.IO) {
                // Closing the stream signals the end of the audio to the phone.
                stream.use { ChunkFrameWriter.write(entry.manifest, entry.audioFile, it) }
            }
        } catch (e: Exception) {
            runCatching { channelClient.close(channel) }
            throw e
        }
        // On success the phone closes the channel after reading to the end of the stream.
    }

    /**
     * Prefers nodes advertising the phone app's capability; falls back to any connected nearby
     * node (the phone app may not declare the capability yet). Never returns a cloud-only node.
     */
    private suspend fun findPhone(): Node? {
        val capable = runCatching {
            capabilityClient.getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        }.getOrDefault(emptySet())
        capable.firstOrNull { it.isNearby }?.let { return it }
        return runCatching { nodeClient.connectedNodes.await() }.getOrDefault(emptyList()).firstOrNull { it.isNearby }
    }

    companion object {
        /** Capability the phone app declares in `res/values/wear.xml` (`android_wear_capabilities`). */
        const val PHONE_CAPABILITY = "watch_audio_receiver"
        private const val OPEN_TIMEOUT_MS = 30_000L
    }
}
