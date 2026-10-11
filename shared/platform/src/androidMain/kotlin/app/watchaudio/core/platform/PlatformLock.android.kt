package app.watchaudio.core.platform

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as jvmWithLock

actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = lock.jvmWithLock(block)
}
