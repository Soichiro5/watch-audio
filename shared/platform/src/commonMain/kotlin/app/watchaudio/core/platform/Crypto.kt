package app.watchaudio.core.platform

// Cryptographic primitives for the encrypted backup (#40), from the OS crypto libraries only
// (javax.crypto on Android and the JVM, CommonCrypto and Security on iOS). The construction
// built from them lives in shared/pipeline (core/backup); see docs/architecture/02 §6.7.

/**
 * HMAC-SHA-256 (RFC 2104) with a fixed [key] (not empty). Reusable: [doFinal] returns the tag of
 * everything passed to [update] since the previous [doFinal] and starts the next message.
 */
expect class HmacSha256(key: ByteArray) {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    /** The 32-byte tag. */
    fun doFinal(): ByteArray
}

/**
 * AES-256 in counter mode (NIST SP 800-38A) with a 32-byte [key]. The counter block starts at the
 * 16-byte IV and is incremented as a big-endian number; encrypting and decrypting are the same
 * operation.
 */
expect class Aes256Ctr(key: ByteArray) {
    /** Writes [length] bytes of [input] from [offset], XORed with the key stream from [iv], to [output] at [outputOffset]. */
    fun process(iv: ByteArray, input: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int)
}

/**
 * PBKDF2 (RFC 8018) with HMAC-SHA-256: [keyLength] bytes derived from [password] (not empty)
 * and [salt] with [iterations] rounds. The password is taken as bytes, so every platform derives
 * the same key from the same text (see [toNfc]).
 */
expect fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray

/** [count] bytes from the OS's cryptographically secure random number generator. */
expect fun secureRandomBytes(count: Int): ByteArray

/**
 * This text in Unicode Normalization Form C, so that a passphrase typed on one OS's keyboard
 * (e.g. 「が」 as one code point or as か + combining mark) derives the same key on another.
 */
expect fun String.toNfc(): String

/** Overwrites the array with zeros (best effort: the platform libraries may keep their own copies). */
fun ByteArray.wipe() = fill(0)
