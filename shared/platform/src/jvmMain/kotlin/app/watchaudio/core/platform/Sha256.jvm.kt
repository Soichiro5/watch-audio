package app.watchaudio.core.platform

import java.security.MessageDigest

actual class Sha256 actual constructor() {
    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)

    actual fun digestHex(): String = digest.digest().toHex()
}
