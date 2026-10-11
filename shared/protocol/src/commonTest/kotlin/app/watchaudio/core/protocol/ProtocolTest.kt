package app.watchaudio.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private val manifest = ChunkManifest(
        chunkId = "6F9619FF-8B86-D011-B42D-00C04FC964FF",
        sessionId = "2B1A3C4D-0000-4000-8000-000000000001",
        deviceId = "watch-device-01",
        source = SourcePlatform.WATCHOS,
        startedAtEpochMs = 1_791_622_800_000,
        durationMs = 300_000,
        timeZone = "Asia/Tokyo",
        audio = AudioFile(
            fileName = "6F9619FF-8B86-D011-B42D-00C04FC964FF.m4a",
            codec = AudioCodec.AAC,
            sampleRateHz = 16_000,
            channels = 1,
            bitrateBps = 24_000,
            sizeBytes = 901_234,
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        ),
        markOffsetsMs = listOf(12_000, 240_500),
        endReason = ChunkEndReason.ROTATION,
    )

    @Test
    fun manifestRoundTrips() {
        assertEquals(manifest, decodeManifest(manifest.toJson()))
    }

    @Test
    fun decodesSharedTestVector() {
        // Same JSON as shared/protocol/testdata/manifest-v1.json (also read by the Swift side).
        assertEquals(manifest, decodeManifest(TestVectors.MANIFEST_V1))
    }

    @Test
    fun recordingFormatMatchesSharedTestVector() {
        // Same JSON as shared/protocol/testdata/recording-format.json, which the Swift side
        // compares with its RecordingFormat.current: both watches record the same format.
        assertEquals(RecordingFormat.CURRENT, ProtocolJson.decodeFromString(RecordingFormat.serializer(), TestVectors.RECORDING_FORMAT))
        assertEquals("m4a", RecordingFormat.CURRENT.codec.fileExtension)
    }

    @Test
    fun recordingFormatDescribesChunkFiles() {
        val audio = RecordingFormat.CURRENT.audioFile("a.m4a", sizeBytes = 4, sha256 = "00")
        assertEquals(AudioFile("a.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 4, "00"), audio)
        assertEquals(listOf("m4a", "ogg"), AudioCodec.entries.map { it.fileExtension })
    }

    @Test
    fun silentChunkHasNoAudioAndStartsGap() {
        val silent = decodeManifest(TestVectors.MANIFEST_V1_SILENT)
        assertNull(silent.audio)
        assertTrue(silent.endReason.startsGap)
        assertEquals(1_791_623_220_000, silent.endedAtEpochMs)
    }

    @Test
    fun ignoresUnknownFieldsFromNewerWatches() {
        val json = TestVectors.MANIFEST_V1.replace("\"endReason\"", "\"futureField\": 42, \"endReason\"")
        assertEquals(manifest, decodeManifest(json))
    }

    @Test
    fun rejectsMarksOutsideChunk() {
        assertFailsWith<IllegalArgumentException> { manifest.copy(markOffsetsMs = listOf(300_001)) }
    }

    @Test
    fun ackFinality() {
        assertEquals(ChunkAck(chunkId = manifest.chunkId, status = AckStatus.STORED), decodeAck(TestVectors.ACK_V1))
        assertTrue(AckStatus.DUPLICATE.isFinal)
        assertFalse(AckStatus.CORRUPT.isFinal)
    }

    @Test
    fun frameHeaderEncodesManifestLength() {
        val header = ChunkFrame.header(manifest)
        val length = ChunkFrame.manifestLength(header)
        assertEquals(header.size - 4, length)
        assertEquals(manifest, decodeManifest(header.copyOfRange(4, 4 + length).decodeToString()))
    }

    /** Serves [bytes] like `DataInputStream.readFully`: throws if fewer bytes remain than requested. */
    private class ByteReader(private val bytes: ByteArray) {
        var position = 0
            private set

        fun readFully(target: ByteArray) {
            check(position + target.size <= bytes.size) { "end of stream" }
            bytes.copyInto(target, 0, position, position + target.size)
            position += target.size
        }
    }

    private fun frame(json: ByteArray) = byteArrayOf(
        (json.size ushr 24).toByte(),
        (json.size ushr 16).toByte(),
        (json.size ushr 8).toByte(),
        json.size.toByte(),
    ) + json

    @Test
    fun readsManifestAndLeavesAudioInStream() {
        val header = ChunkFrame.header(manifest)
        val reader = ByteReader(header + byteArrayOf(1, 2, 3))
        assertEquals(manifest, ChunkFrame.readManifest(reader::readFully))
        assertEquals(header.size, reader.position)
    }

    @Test
    fun rejectsMalformedFrames() {
        // Length prefix of zero, and one beyond the limit.
        assertFailsWith<IllegalArgumentException> { ChunkFrame.readManifest(ByteReader(byteArrayOf(0, 0, 0, 0))::readFully) }
        assertFailsWith<IllegalArgumentException> { ChunkFrame.readManifest(ByteReader(byteArrayOf(0x7f, 0, 0, 0))::readFully) }
        // A payload that is not a manifest.
        assertFailsWith<IllegalArgumentException> {
            ChunkFrame.readManifest(ByteReader(frame("not json".encodeToByteArray()))::readFully)
        }
        // Manifest JSON that violates the model's invariants.
        val invalid = TestVectors.MANIFEST_V1.replace("\"durationMs\": 300000", "\"durationMs\": -1")
        assertFailsWith<IllegalArgumentException> {
            ChunkFrame.readManifest(ByteReader(frame(invalid.encodeToByteArray()))::readFully)
        }
    }

    @Test
    fun truncatedHeaderSurfacesTheReaderError() {
        val header = ChunkFrame.header(manifest)
        assertFailsWith<IllegalStateException> { ChunkFrame.readManifest(ByteReader(header.copyOf(header.size - 1))::readFully) }
    }
}
