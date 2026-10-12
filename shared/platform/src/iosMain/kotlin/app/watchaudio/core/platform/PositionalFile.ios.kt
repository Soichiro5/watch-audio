package app.watchaudio.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close as closeFd
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
actual fun openPositionalFile(path: String): PositionalFile {
    val fd = open(path, O_RDWR or O_CREAT, (S_IRUSR or S_IWUSR).convert<UShort>())
    if (fd < 0) throw IllegalStateException("open failed: errno $errno")
    return PosixPositionalFile(fd)
}

/** [PositionalFile] over a POSIX file descriptor (`pread` / `pwrite` are thread-safe). */
@OptIn(ExperimentalForeignApi::class)
private class PosixPositionalFile(private val fd: Int) : PositionalFile {
    override val size: Long
        get() = memScoped {
            val info = alloc<stat>()
            if (fstat(fd, info.ptr) != 0) throw IllegalStateException("fstat failed: errno $errno")
            info.st_size
        }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var done = 0
        buffer.usePinned { pinned ->
            while (done < length) {
                val read = pread(fd, pinned.addressOf(offset + done), (length - done).convert(), position + done)
                if (read < 0) throw IllegalStateException("pread failed: errno $errno")
                if (read == 0L) break
                done += read.toInt()
            }
        }
        return done
    }

    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        var done = 0
        buffer.usePinned { pinned ->
            while (done < length) {
                val written = pwrite(fd, pinned.addressOf(offset + done), (length - done).convert(), position + done)
                if (written < 0) throw IllegalStateException("pwrite failed: errno $errno")
                done += written.toInt()
            }
        }
    }

    override fun truncate(size: Long) {
        if (this.size > size && ftruncate(fd, size) != 0) throw IllegalStateException("ftruncate failed: errno $errno")
    }

    override fun sync() {
        fsync(fd)
    }

    override fun close() {
        closeFd(fd)
    }
}
