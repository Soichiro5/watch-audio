package app.watchaudio.android.asr

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Streaming extraction of the sherpa-onnx model archives (`.tar.bz2`) without storing them. */
internal object ModelArchive {
    /**
     * Reads a `.tar.bz2` from [input] and writes only the entries in [wanted] (path inside the
     * archive → target file). Stops reading once every wanted entry is written, so the rest of
     * the archive (e.g. fp32 models) is never downloaded. [checkActive] is called between
     * buffers and may throw to abort.
     *
     * @throws IOException if the archive ends before all wanted entries were found.
     */
    fun extract(input: InputStream, wanted: Map<String, File>, checkActive: () -> Unit = {}) {
        val remaining = wanted.toMutableMap()
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(BUFFER_SIZE))).use { tar ->
            while (remaining.isNotEmpty()) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile) continue
                val target = remaining.remove(entry.name.removePrefix("./")) ?: continue
                target.parentFile?.mkdirs()
                target.outputStream().use { copy(tar, it, checkActive) }
            }
        }
        if (remaining.isNotEmpty()) throw IOException("archive is missing ${remaining.keys}")
    }

    fun copy(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}

/** Reports the number of bytes read so far to [onProgress]. */
internal class CountingInputStream(input: InputStream, private val onProgress: (Long) -> Unit) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { advance(it) }

    private fun advance(n: Long) {
        count += n
        onProgress(count)
    }
}
