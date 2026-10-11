package app.watchaudio.android.transfer

import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkFrame
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.decodeManifest
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/** Reads one [ChunkFrame] from a stream, spooling the audio to disk. */
internal object FrameReader {
    /**
     * Reads the frame header from [input] and returns the manifest JSON undecoded, so that a
     * manifest the phone cannot decode can still be answered ([app.watchaudio.core.pipeline.ChunkIntake.decode]).
     *
     * @throws IllegalArgumentException if the length prefix is malformed.
     * @throws EOFException if the stream ends inside the header.
     */
    fun readHeader(input: InputStream): String = ChunkFrame.readManifestJson(DataInputStream(input)::readFully)

    /**
     * Writes the audio bytes that follow the header in [input] to [audioFile]. At most
     * `audio.sizeBytes + 1` bytes are written, so an overlong stream cannot fill the disk and
     * still fails the ingest size check. Nothing is written for a silent chunk ([audio] null).
     */
    fun readAudio(input: InputStream, audio: AudioFile?, audioFile: File, checkActive: () -> Unit = {}) {
        if (audio == null) return
        val limit = audio.sizeBytes + 1
        audioFile.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            while (written < limit) {
                checkActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
            }
        }
    }

    /**
     * [readHeader], decode and [readAudio] in one go.
     *
     * @throws IllegalArgumentException if the header or manifest is malformed.
     * @throws EOFException if the stream ends inside the header.
     */
    fun read(input: InputStream, audioFile: File, checkActive: () -> Unit = {}): ChunkManifest {
        val manifest = decodeManifest(readHeader(input))
        readAudio(input, manifest.audio, audioFile, checkActive)
        return manifest
    }
}
