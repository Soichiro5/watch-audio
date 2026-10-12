package app.watchaudio.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.CCHmacContext
import platform.CoreCrypto.CCHmacFinal
import platform.CoreCrypto.CCHmacInit
import platform.CoreCrypto.CCHmacUpdate
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCModeCTR
import platform.CoreCrypto.kCCModeOptionCTR_BE
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.ccNoPadding
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
actual class HmacSha256 actual constructor(key: ByteArray) {
    private val key = key.copyOf()
    private val context = nativeHeap.alloc<CCHmacContext>()

    init {
        require(key.isNotEmpty()) { "HMAC key must not be empty" }
        start()
    }

    private fun start() = key.usePinned { CCHmacInit(context.ptr, kCCHmacAlgSHA256, it.addressOf(0), key.size.convert()) }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range outside the array" }
        if (length == 0) return
        bytes.usePinned { CCHmacUpdate(context.ptr, it.addressOf(offset), length.convert()) }
    }

    actual fun doFinal(): ByteArray {
        val out = ByteArray(32)
        out.usePinned { CCHmacFinal(context.ptr, it.addressOf(0)) }
        start()
        return out
    }

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused") // Frees the native context once this instance is garbage.
    private val cleaner = createCleaner(context) { nativeHeap.free(it) }
}

@OptIn(ExperimentalForeignApi::class)
actual class Aes256Ctr actual constructor(key: ByteArray) {
    private val key = key.copyOf()

    init {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
    }

    /** A CommonCrypto cryptor per call: its IV cannot be reset in counter mode. */
    actual fun process(iv: ByteArray, input: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int) {
        require(iv.size == 16) { "the counter block has 16 bytes" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) { "input range outside the array" }
        require(outputOffset >= 0 && outputOffset + length <= output.size) { "output range outside the array" }
        if (length == 0) return
        memScoped {
            val cryptor = alloc<CCCryptorRefVar>()
            val created = iv.usePinned { ivPin ->
                key.usePinned { keyPin ->
                    CCCryptorCreateWithMode(
                        kCCEncrypt,
                        kCCModeCTR,
                        kCCAlgorithmAES,
                        ccNoPadding,
                        ivPin.addressOf(0),
                        keyPin.addressOf(0),
                        key.size.convert(),
                        null,
                        0u,
                        0,
                        kCCModeOptionCTR_BE,
                        cryptor.ptr,
                    )
                }
            }
            check(created == 0) { "CCCryptorCreateWithMode failed: $created" }
            try {
                val moved = alloc<size_tVar>()
                val status = input.usePinned { inPin ->
                    output.usePinned { outPin ->
                        CCCryptorUpdate(
                            cryptor.value,
                            inPin.addressOf(offset),
                            length.convert(),
                            outPin.addressOf(outputOffset),
                            length.convert(),
                            moved.ptr,
                        )
                    }
                }
                check(status == 0) { "CCCryptorUpdate failed: $status" }
                check(moved.value.toInt() == length) { "AES-CTR wrote ${moved.value} of $length bytes" }
            } finally {
                CCCryptorRelease(cryptor.value)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
actual fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
    require(iterations >= 1 && keyLength >= 1) { "iterations and keyLength must be positive" }
    require(password.isNotEmpty() && salt.isNotEmpty()) { "password and salt must not be empty" }
    val saltBytes = salt.asUByteArray()
    val out = UByteArray(keyLength)
    val status = password.usePinned { passwordPin ->
        saltBytes.usePinned { saltPin ->
            out.usePinned { outPin ->
                CCKeyDerivationPBKDF(
                    kCCPBKDF2,
                    passwordPin.addressOf(0),
                    password.size.convert(),
                    saltPin.addressOf(0),
                    salt.size.convert(),
                    kCCPRFHmacAlgSHA256,
                    iterations.convert(),
                    outPin.addressOf(0),
                    keyLength.convert(),
                )
            }
        }
    }
    check(status == 0) { "CCKeyDerivationPBKDF failed: $status" }
    return out.asByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    if (count == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed: $status" }
    return out
}

@OptIn(BetaInteropApi::class)
actual fun String.toNfc(): String = NSString.create(string = this).precomposedStringWithCanonicalMapping()
