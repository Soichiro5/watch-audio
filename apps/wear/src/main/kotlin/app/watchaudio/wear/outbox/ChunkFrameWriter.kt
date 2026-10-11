package app.watchaudio.wear.outbox

import app.watchaudio.core.protocol.ChunkFrame
import app.watchaudio.core.protocol.ChunkManifest
import java.io.File
import java.io.OutputStream

/** Writes one [ChunkFrame] (length-prefixed manifest JSON, then the raw audio) to a stream. */
object ChunkFrameWriter {
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * @param audio the chunk's audio, required when [ChunkManifest.audio] is set; silent chunks
     *   send the header only. The caller closes [out]: end of stream marks the end of the audio.
     */
    fun write(manifest: ChunkManifest, audio: File?, out: OutputStream) {
        out.write(ChunkFrame.header(manifest))
        if (manifest.audio != null) {
            val file = requireNotNull(audio) { "manifest ${manifest.chunkId} has audio but no file" }
            file.inputStream().use { it.copyTo(out, BUFFER_BYTES) }
        }
        out.flush()
    }
}
