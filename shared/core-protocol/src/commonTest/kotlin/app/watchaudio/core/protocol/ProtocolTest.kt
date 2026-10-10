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
        // Same JSON as shared/core-protocol/testdata/manifest-v1.json (also read by the Swift side).
        assertEquals(manifest, decodeManifest(TestVectors.MANIFEST_V1))
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
}
