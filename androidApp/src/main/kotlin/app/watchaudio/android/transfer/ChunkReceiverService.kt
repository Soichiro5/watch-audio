package app.watchaudio.android.transfer

import app.watchaudio.android.AppGraph
import app.watchaudio.core.protocol.TransferPaths
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService

/**
 * Entry point for chunks from the Wear OS app (channel paths under
 * [TransferPaths.CHUNK_CHANNEL_PREFIX]); Google Play services starts it on demand. The work
 * itself runs in the process-wide [ChunkReceiver], which outlives this service.
 */
class ChunkReceiverService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(TransferPaths.CHUNK_CHANNEL_PREFIX)) return
        AppGraph.get(this).chunkReceiver.receive(channel)
    }
}
