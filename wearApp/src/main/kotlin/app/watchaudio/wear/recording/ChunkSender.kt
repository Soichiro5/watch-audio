package app.watchaudio.wear.recording

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Wearable
import java.io.File
import kotlinx.coroutines.tasks.await

/**
 * Spike-quality transfer of closed chunks to the phone over the Wearable Data Layer.
 * No manifest/ACK protocol yet — that is M1-6 (#18). Here we only measure the cost
 * of transferring while recording.
 */
class ChunkSender(context: Context) {
    private val nodeClient = Wearable.getNodeClient(context)
    private val channelClient = Wearable.getChannelClient(context)

    /** Returns true when the file was handed to a connected phone. */
    suspend fun send(file: File): Boolean {
        val node = nodeClient.connectedNodes.await().firstOrNull { it.isNearby } ?: return false
        val channel = channelClient.openChannel(node.id, "$PATH_PREFIX${file.name}").await()
        channelClient.sendFile(channel, Uri.fromFile(file)).await()
        return true
    }

    companion object {
        const val PATH_PREFIX = "/spike/chunk/"
    }
}
