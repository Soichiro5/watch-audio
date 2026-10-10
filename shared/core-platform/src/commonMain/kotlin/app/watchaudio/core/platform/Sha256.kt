package app.watchaudio.core.platform

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Incremental SHA-256 backed by the platform's crypto library. Single use. */
expect class Sha256() {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    /** Lowercase hex digest. Must be called exactly once. */
    fun digestHex(): String
}

/** Lowercase hex SHA-256 of a file's contents, streamed in 64 KiB blocks. */
fun sha256Of(path: Path): String {
    val sha = Sha256()
    SystemFileSystem.source(path).buffered().use { source ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.readAtMostTo(buffer)
            if (read == -1) break
            sha.update(buffer, 0, read)
        }
    }
    return sha.digestHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
