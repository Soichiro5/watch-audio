package app.watchaudio.wear.outbox

import app.watchaudio.core.protocol.TransferPaths
import app.watchaudio.core.protocol.decodeAck
import app.watchaudio.wear.AppGraph
import app.watchaudio.wear.tile.StatusSurfaces
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the phone's [app.watchaudio.core.protocol.ChunkAck] messages and reconnect signals.
 * Declared in the manifest so it is delivered even when the app is not running.
 */
class DataLayerListenerService : WearableListenerService() {
    private val graph by lazy { AppGraph.of(this) }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != TransferPaths.ACK_MESSAGE) return
        val ack = runCatching { decodeAck(event.data.decodeToString()) }.getOrElse {
            graph.log("ack_unreadable", it.toString())
            return
        }
        val outcome = graph.outbox.applyAck(ack)
        graph.log("ack", "${ack.chunkId} ${ack.status.name.lowercase()} -> ${outcome.name.lowercase()} ${ack.detail.orEmpty()}")
        if (ack.status.isFinal) graph.prefs.lastAckAtEpochMs = System.currentTimeMillis()
        graph.refreshPendingCount()
        StatusSurfaces.refresh(this)
        if (outcome == Outbox.AckOutcome.RESEND) UploadScheduler.enqueue(this)
    }

    /** The phone app became reachable again (e.g. back in Bluetooth range): resend what is pending. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.any { it.isNearby }) {
            graph.log("phone_reachable", info.nodes.joinToString { it.displayName })
            UploadScheduler.enqueue(this)
        }
    }
}
