package app.watchaudio.app

import platform.Foundation.NSData

/**
 * The Apple frameworks behind agent access on iOS (#185), implemented in Swift
 * (`apps/ios/App/Agent/AgentPlatform.swift`) and handed to [IosAppGraph.start], like
 * [IosSpeechTranscriber]. Kotlin keeps the logic ([IosAgentAccess], [IosAgentTransport], the shared
 * `AgentConnector`); Swift only reaches the Keychain, Network.framework, the camera and AVFoundation.
 *
 * Callbacks may run on any thread and are invoked exactly once (except [browse]'s, see there).
 */
interface IosAgentPlatform {
    /**
     * The phone's ECDSA P-256 key in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`,
     * not synchronized, never exported), created on first use. Blocking: call off the main thread.
     */
    fun loadKey(): IosAgentKey

    /** Signs [data] with the key of [loadKey] (ECDSA with SHA-256, DER), or null on failure. Blocking. */
    fun sign(data: NSData): NSData?

    /**
     * Stores [certificate] (DER, for the key of [loadKey]) in the Keychain, replacing an earlier one,
     * so that the key and certificate form the TLS identity. Returns null when done, or the error.
     * Blocking.
     */
    fun installCertificate(certificate: NSData): String?

    /** The certificate [installCertificate] stored (DER), or null if there is none. Blocking. */
    fun storedCertificate(): NSData?

    /**
     * Opens mutual TLS 1.3 (Network.framework) to the IP literal [host] (no DNS) and [port],
     * presenting the Keychain identity and accepting only a bridge whose public key has the SHA-256
     * fingerprint [fingerprint] (base64url, `keyFingerprint`). Gives up after [timeoutMs].
     */
    fun connect(host: String, port: Int, fingerprint: String, timeoutMs: Long, callback: (IosAgentConnectResult) -> Unit)

    /**
     * Browses mDNS for `_watchaudio-mcp._tcp` (announces nothing) and calls [onFound] for each
     * bridge seen, with its TXT `n` / `h` (as nonce / keyHash) and its IP address (possibly several times), until the
     * returned handle is cancelled. [onFailed] reports that browsing stopped (e.g. the local network
     * was refused), at most once.
     */
    fun browse(onFound: (IosBridgeFound) -> Unit, onFailed: (String) -> Unit): IosAgentCancellable

    /**
     * Shows the camera to read a QR code, asking for the camera permission first if it was never
     * asked. Main thread.
     */
    fun scanQr(callback: (IosQrScanResult) -> Unit)

    /**
     * Decodes [durationMs] of the audio file at [path] (absolute; AAC in .m4a) from [offsetMs] into
     * 16 kHz mono 32-bit float samples (native byte order), fewer if the file ends first.
     */
    fun readAudio(path: String, offsetMs: Long, durationMs: Long, callback: (IosAudioClipResult) -> Unit)
}

/** [IosAgentPlatform.loadKey]: the public key as DER SubjectPublicKeyInfo, or the error. */
class IosAgentKey(val subjectPublicKeyInfo: NSData?, val error: String?)

/** [IosAgentPlatform.connect]: the open connection, or the error. */
class IosAgentConnectResult(
    val connection: IosAgentLink?,
    val error: String?,
    /** The system refused the local network (iOS's Local Network privacy setting). */
    val localNetworkDenied: Boolean,
)

/** One mutual TLS connection opened by [IosAgentPlatform.connect]: a byte pipe; framing is in Kotlin. */
interface IosAgentLink {
    /** Sends [data]; calls back with null once sent, or the error. */
    fun send(data: NSData, callback: (String?) -> Unit)

    /** Receives exactly [length] bytes; null once the connection ended or failed. */
    fun receive(length: Int, callback: (NSData?) -> Unit)

    /** Closes the connection; a pending [receive] calls back with null. Safe to call more than once. */
    fun close()
}

/** A bridge [IosAgentPlatform.browse] saw: its TXT values and where it listens (an IP literal). */
class IosBridgeFound(val nonce: String, val keyHash: String, val host: String, val port: Int)

/** Stops what [IosAgentPlatform.browse] started. */
interface IosAgentCancellable {
    fun cancel()
}

/**
 * [IosAgentPlatform.scanQr]: the text of the QR code, or null when the user closed the scanner or
 * it is [unavailable] (no camera, or the camera permission refused).
 */
class IosQrScanResult(val text: String?, val unavailable: Boolean)

/** [IosAgentPlatform.readAudio]: the samples, or the error. */
class IosAudioClipResult(val samples: NSData?, val error: String?)
