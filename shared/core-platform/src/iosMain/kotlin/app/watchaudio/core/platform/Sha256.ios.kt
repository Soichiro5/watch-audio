package app.watchaudio.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

@OptIn(ExperimentalForeignApi::class)
actual class Sha256 actual constructor() {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        bytes.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(offset), length.convert()) }
    }

    actual fun digestHex(): String {
        val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { CC_SHA256_Final(it.addressOf(0), context.ptr) }
        nativeHeap.free(context)
        return out.asByteArray().toHex()
    }
}
