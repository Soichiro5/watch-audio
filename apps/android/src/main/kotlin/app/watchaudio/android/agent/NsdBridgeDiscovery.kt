package app.watchaudio.android.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import app.watchaudio.core.agent.BridgeAdvertisement
import app.watchaudio.core.agent.MDNS_SERVICE_TYPE
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Finds the bridge CLI on the local network while agent access is on (#184): browses mDNS for
 * `_watchaudio-mcp._tcp` with the platform's [NsdManager] (the phone only listens for
 * advertisements; it announces nothing) and resolves each instance to its address and TXT values,
 * which `shared/agent` matches against the paired key ([app.watchaudio.core.agent.advertisementMatches]).
 * A Wi-Fi multicast lock is held while browsing so that power saving does not drop the replies.
 */
class NsdBridgeDiscovery(context: Context) {
    private val app = context.applicationContext

    fun advertisements(): Flow<BridgeAdvertisement> = callbackFlow {
        val nsd = app.getSystemService(NsdManager::class.java)
        val lock = app.getSystemService(WifiManager::class.java)?.createMulticastLock(LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        // Resolve one instance at a time: older Android versions refuse concurrent resolutions.
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        launch {
            for (info in found) {
                resolve(nsd, info)?.let { trySend(it) }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start ($errorCode)")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            found.close()
            lock?.release()
        }
    }

    @Suppress("DEPRECATION") // resolveService: registerServiceInfoCallback needs API 34; this works on all.
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): BridgeAdvertisement? = suspendCancellableCoroutine { continuation ->
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(serviceInfo.toAdvertisement())
                }
            },
        )
    }

    private fun NsdServiceInfo.toAdvertisement(): BridgeAdvertisement? {
        val nonce = attributes["n"]?.decodeToString() ?: return null
        val hash = attributes["h"]?.decodeToString() ?: return null
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hostAddresses.firstOrNull() else @Suppress("DEPRECATION") host
        val hostText = address?.hostAddress ?: return null
        return BridgeAdvertisement(nonce, hash, hostText, port)
    }

    private companion object {
        const val TAG = "NsdBridgeDiscovery"
        const val LOCK_TAG = "watch-audio-agent-mdns"
    }
}
