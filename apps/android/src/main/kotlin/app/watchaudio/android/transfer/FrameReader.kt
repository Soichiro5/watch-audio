package app.watchaudio.android.transfer

import app.watchaudio.core.protocol.ChunkFrame
import app.watchaudio.core.protocol.ChunkManifest
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/** Reads one [ChunkFrame] from a stream, spooling the audio to disk. */
internal object FrameReader {
    /**
     * Parses the frame header from [input] and writes the audio bytes that follow to [audioFile].
     * At most `audio.sizeBytes + 1` bytes are written, so an overlong stream cannot fill the disk
     * and still fails the ingest size check. Nothing is written for a silent chunk.
     *
     * @throws IllegalArgumentException if the header or manifest is malformed.
     * @throws EOFException if the stream ends inside the header.
     */
    fun read(input: InputStream, audioFile: File, checkActive: () -> Unit = {}): ChunkManifest {
        val data = DataInputStream(input)
        val manifest = ChunkFrame.readManifest(data::readFully)
        val audio = manifest.audio ?: return manifest
        val limit = audio.sizeBytes + 1
        audioFile.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            while (written < limit) {
                checkActive()
                val read = data.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
            }
        }
        return manifest
    }
}
