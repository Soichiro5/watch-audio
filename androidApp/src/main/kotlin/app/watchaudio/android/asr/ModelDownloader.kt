package app.watchaudio.android.asr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads single model files with resume and verification (plain JVM, unit-tested against a
 * local fake server).
 *
 * Bytes go to a `.part` file first. When a `.part` already exists, only the rest is requested
 * with `Range: bytes=<n>-`, so an interrupted download continues where it stopped (across
 * retries and process restarts). The complete file must have the expected size and SHA-256
 * before it is renamed atomically onto the target, so a target file is always complete.
 */
internal class ModelDownloader(
    private val connect: (URL) -> HttpURLConnection = ::defaultConnect,
) {
    /**
     * Downloads [source] into [target] via [part]. [onProgress] receives the bytes of this file
     * present so far; [checkActive] is called between buffers and may throw to abort.
     *
     * A resumed `.part` whose result fails verification (e.g. it held bytes of another version)
     * is discarded and downloaded once more from the start.
     *
     * @throws IOException on network errors (the `.part` is kept for resuming) or when the
     *   downloaded bytes do not match [source] (the `.part` is deleted).
     */
    fun download(
        source: ModelSource,
        target: File,
        part: File,
        checkActive: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ) {
        while (true) {
            val startedAt = transfer(source, part, checkActive, onProgress)
            val length = part.length()
            if (length < source.size) throw IOException("incomplete ${source.path}: $length of ${source.size} bytes")
            if (length == source.size && sha256(part, checkActive) == source.sha256) {
                target.parentFile?.mkdirs()
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            }
            part.delete()
            if (startedAt == 0L) throw IOException("SHA-256 or size mismatch for ${source.path}")
        }
    }

    /**
     * Appends the missing bytes of [source] to [part]. Returns the offset the transfer started
     * at (0 when the file was fetched from the beginning).
     */
    private fun transfer(source: ModelSource, part: File, checkActive: () -> Unit, onProgress: (Long) -> Unit): Long {
        var offset = if (part.isFile) part.length() else 0L
        if (offset > source.size) {
            part.delete()
            offset = 0L
        }
        if (offset == source.size) {
            onProgress(offset)
            return offset
        }
        part.parentFile?.mkdirs()
        val connection = connect(URL(source.url))
        try {
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = connection.getHeaderField("Content-Range")
                    if (offset == 0L || !matchesRange(range, offset, source.size)) {
                        part.delete()
                        throw IOException("unexpected Content-Range '$range' for ${source.url} at $offset")
                    }
                }
                // The server ignored the Range header and sends the whole file.
                HttpURLConnection.HTTP_OK -> offset = 0L
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    part.delete()
                    throw IOException("HTTP $code for ${source.url} at $offset")
                }
                else -> throw IOException("HTTP $code for ${source.url}")
            }
            FileOutputStream(part, offset > 0).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var present = offset
                    onProgress(present)
                    while (true) {
                        checkActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        present += read
                        onProgress(present)
                    }
                    output.fd.sync()
                }
            }
            return offset
        } finally {
            connection.disconnect()
        }
    }

    private fun matchesRange(contentRange: String?, offset: Long, size: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(contentRange?.trim() ?: return false) ?: return false
        val (start, _, total) = match.destructured
        return start.toLong() == offset && (total == "*" || total.toLong() == size)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

        private fun defaultConnect(url: URL): HttpURLConnection =
            (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }

        /** Lowercase hex SHA-256 of [file]. */
        fun sha256(file: File, checkActive: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte ->
                val value = byte.toInt() and 0xff
                "${Character.forDigit(value shr 4, 16)}${Character.forDigit(value and 0xf, 16)}"
            }
        }
    }
}
