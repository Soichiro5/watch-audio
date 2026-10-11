package app.watchaudio.core.protocol

/**
 * Framing used on byte streams (Wear OS `ChannelClient`): a 4-byte big-endian length, the UTF-8
 * manifest JSON, then the raw audio bytes until end of stream.
 */
object ChunkFrame {
    /** Upper bound for the manifest JSON; a larger length prefix is treated as malformed. */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    /** The length prefix and manifest JSON that precede the audio bytes. */
    fun header(manifest: ChunkManifest): ByteArray {
        val json = manifest.toJson().encodeToByteArray()
        require(json.size <= MAX_MANIFEST_BYTES) { "manifest too large: ${json.size} bytes" }
        val size = json.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + json
    }

    /** Reads the manifest length from the first 4 bytes of a frame. */
    fun manifestLength(prefix: ByteArray): Int {
        require(prefix.size >= 4) { "need 4 bytes, got ${prefix.size}" }
        val size = ((prefix[0].toInt() and 0xff) shl 24) or
            ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or
            (prefix[3].toInt() and 0xff)
        require(size in 1..MAX_MANIFEST_BYTES) { "invalid manifest length: $size" }
        return size
    }

    /**
     * Reads a frame header and returns its manifest; the audio bytes follow in the stream.
     * [readFully] must fill the whole array or throw (e.g. `DataInputStream::readFully`).
     *
     * @throws IllegalArgumentException if the length prefix or the manifest is malformed.
     */
    fun readManifest(readFully: (ByteArray) -> Unit): ChunkManifest {
        val prefix = ByteArray(4).also(readFully)
        val json = ByteArray(manifestLength(prefix)).also(readFully)
        return decodeManifest(json.decodeToString())
    }
}
