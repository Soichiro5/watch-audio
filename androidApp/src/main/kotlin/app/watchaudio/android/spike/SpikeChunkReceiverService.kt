package app.watchaudio.android.spike

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

/**
 * Receives chunks sent by the Wear OS spike recorder (#10) and stores them under
 * `files/watch-inbox/`. Replaced by the real ingest + ACK flow in M1-6 (#18).
 *
 * Pull with `adb shell run-as app.watchaudio ls files/watch-inbox`.
 */
class SpikeChunkReceiverService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val name = channel.path.substringAfterLast('/')
        val target = File(File(filesDir, "watch-inbox").apply { mkdirs() }, name)
        Wearable.getChannelClient(this)
            .receiveFile(channel, Uri.fromFile(target), false)
            .addOnFailureListener { Log.w(TAG, "receive failed: $name", it) }
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        Log.i(TAG, "received ${channel.path} (closeReason=$closeReason)")
    }

    private companion object {
        const val TAG = "SpikeChunkReceiver"
    }
}
