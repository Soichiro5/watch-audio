package app.watchaudio.android.transfer

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkFrame
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FrameReaderTest {
    private val target = File.createTempFile("frame", ".part")

    @AfterTest
    fun tearDown() {
        target.delete()
    }

    private fun manifest(size: Long?) = ChunkManifest(
        chunkId = "c1",
        sessionId = "s",
        deviceId = "w",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = 0,
        durationMs = 300_000,
        timeZone = "UTC",
        audio = size?.let { AudioFile("c1.m4a", AudioCodec.AAC, 16_000, 1, 24_000, it, "00") },
        endReason = ChunkEndReason.ROTATION,
    )

    @Test
    fun spoolsAudioAfterHeader() {
        val audio = ByteArray(200_000) { it.toByte() }
        val read = FrameReader.read(ByteArrayInputStream(ChunkFrame.header(manifest(audio.size.toLong())) + audio), target)
        assertEquals(manifest(audio.size.toLong()), read)
        assertContentEquals(audio, target.readBytes())
    }

    @Test
    fun capsOverlongStreams() {
        val stream = ByteArrayInputStream(ChunkFrame.header(manifest(10)) + ByteArray(1_000))
        FrameReader.read(stream, target)
        // One byte over the declared size: enough for the ingest size check to reject it.
        assertEquals(11, target.length())
    }

    @Test
    fun silentChunkWritesNothing() {
        target.delete()
        FrameReader.read(ByteArrayInputStream(ChunkFrame.header(manifest(null))), target)
        assertFalse(target.exists())
    }

    @Test
    fun malformedAndTruncatedHeaders() {
        assertFailsWith<IllegalArgumentException> { FrameReader.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)), target) }
        val header = ChunkFrame.header(manifest(10))
        assertFailsWith<EOFException> { FrameReader.read(ByteArrayInputStream(header.copyOf(header.size - 2)), target) }
    }
}
