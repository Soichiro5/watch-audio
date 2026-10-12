package app.watchaudio.core.platform

import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual class HmacSha256 actual constructor(key: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) = mac.update(bytes, offset, length)

    actual fun doFinal(): ByteArray = mac.doFinal()

    /** [doFinal] into [output] without allocating (for the PBKDF2 loop). */
    internal fun doFinal(output: ByteArray) = mac.doFinal(output, 0)
}

actual class Aes256Ctr actual constructor(key: ByteArray) {
    init {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
    }

    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    actual fun process(iv: ByteArray, input: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int) {
        require(iv.size == 16) { "the counter block has 16 bytes" }
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val written = cipher.doFinal(input, offset, length, output, outputOffset)
        check(written == length) { "AES-CTR wrote $written of $length bytes" }
    }
}

/** Computed here rather than with `SecretKeyFactory`, whose `PBEKeySpec` takes characters, not bytes. */
actual fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
    require(iterations >= 1 && keyLength >= 1) { "iterations and keyLength must be positive" }
    val mac = HmacSha256(password)
    val out = ByteArray(keyLength)
    val u = ByteArray(32)
    val t = ByteArray(32)
    var block = 1
    var at = 0
    while (at < keyLength) {
        mac.update(salt)
        mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
        mac.doFinal(u)
        u.copyInto(t)
        repeat(iterations - 1) {
            mac.update(u)
            mac.doFinal(u)
            for (i in 0 until 32) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        val n = minOf(32, keyLength - at)
        t.copyInto(out, at, 0, n)
        at += n
        block++
    }
    u.wipe()
    t.wipe()
    return out
}

private val random = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

actual fun String.toNfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
