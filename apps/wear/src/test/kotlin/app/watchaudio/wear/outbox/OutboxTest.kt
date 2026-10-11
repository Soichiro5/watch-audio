package app.watchaudio.wear.outbox

import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkAck
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkFrame
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.protocol.decodeManifest
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val outbox by lazy { Outbox(tmp.newFolder("outbox")) { now } }

    private fun manifest(id: String, startedAt: Long = now, withAudio: Boolean = true) = ChunkManifest(
        chunkId = id,
        sessionId = "s",
        deviceId = "d",
        source = SourcePlatform.WEAROS,
        startedAtEpochMs = startedAt,
        durationMs = 300_000,
        timeZone = "UTC",
        audio = if (withAudio) AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 4, "00") else null,
        endReason = ChunkEndReason.ROTATION,
    )

    private fun commit(id: String, startedAt: Long = now, withAudio: Boolean = true): ChunkManifest {
        val m = manifest(id, startedAt, withAudio)
        val audio = if (withAudio) tmp.newFile("$id-src.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) } else null
        outbox.commit(m, audio)
        return m
    }

    @Test
    fun `committed chunks are due oldest first`() {
        commit("b", startedAt = 2_000)
        commit("a", startedAt = 1_000)
        commit("silent", startedAt = 3_000, withAudio = false)

        assertEquals(listOf("a", "b", "silent"), outbox.dueForSend().map { it.manifest.chunkId })
        assertContentEquals(byteArrayOf(1, 2, 3, 4), outbox.entries().first().audioFile!!.readBytes())
        assertEquals(null, outbox.entries().last().audioFile)
        assertEquals(3, outbox.count())
    }

    @Test
    fun `sent chunks wait for the ack timeout before resending`() {
        commit("a")
        outbox.markSent("a")
        assertTrue(outbox.dueForSend().isEmpty())
        assertEquals(listOf("a"), outbox.awaitingAck().map { it.manifest.chunkId })

        now += Outbox.ACK_TIMEOUT_MS
        assertEquals(listOf("a"), outbox.dueForSend().map { it.manifest.chunkId })
        assertEquals(1, outbox.entries().single().state.attempts)
    }

    @Test
    fun `final acks delete the chunk`() {
        commit("a")
        commit("b")
        outbox.markSent("a")
        assertEquals(Outbox.AckOutcome.DELETED, outbox.applyAck(ChunkAck(chunkId = "a", status = AckStatus.STORED)))
        assertEquals(Outbox.AckOutcome.DELETED, outbox.applyAck(ChunkAck(chunkId = "b", status = AckStatus.DUPLICATE)))
        assertEquals(0, outbox.count())
        assertTrue(outbox.dir.list()!!.isEmpty(), "audio, manifest and state are all gone")
    }

    @Test
    fun `corrupt ack schedules a resend`() {
        commit("a")
        outbox.markSent("a")
        assertEquals(Outbox.AckOutcome.RESEND, outbox.applyAck(ChunkAck(chunkId = "a", status = AckStatus.CORRUPT, detail = "sha256 mismatch")))
        assertTrue(outbox.dueForSend().isEmpty(), "backs off a little first")
        now += Outbox.retryDelayMs(1)
        val entry = outbox.dueForSend().single()
        assertEquals("sha256 mismatch", entry.state.detail)
    }

    @Test
    fun `rejected ack keeps the chunk for a long time`() {
        commit("a")
        outbox.markSent("a")
        assertEquals(Outbox.AckOutcome.KEPT, outbox.applyAck(ChunkAck(chunkId = "a", status = AckStatus.REJECTED)))
        assertEquals(1, outbox.count())
        now += Outbox.ACK_TIMEOUT_MS
        assertTrue(outbox.dueForSend().isEmpty())
        assertTrue(outbox.awaitingAck().isEmpty())
        now += Outbox.REJECTED_RETRY_MS
        assertEquals(1, outbox.dueForSend().size)
    }

    @Test
    fun `unknown acks are ignored`() {
        assertEquals(Outbox.AckOutcome.UNKNOWN_CHUNK, outbox.applyAck(ChunkAck(chunkId = "nope", status = AckStatus.STORED)))
    }

    @Test
    fun `failed sends back off exponentially up to a cap`() {
        assertEquals(30_000L, Outbox.retryDelayMs(1))
        assertEquals(60_000L, Outbox.retryDelayMs(2))
        assertEquals(30 * 60_000L, Outbox.retryDelayMs(50))
        commit("a")
        outbox.markFailed("a", "no phone")
        assertTrue(outbox.dueForSend().isEmpty())
        now += 30_000
        assertEquals(1, outbox.dueForSend().size)
    }

    @Test
    fun `state survives a new outbox instance`() {
        commit("a")
        outbox.markSent("a")
        val reopened = Outbox(outbox.dir) { now }
        assertTrue(reopened.dueForSend().isEmpty())
        assertEquals(1, reopened.entries().single().state.attempts)
    }

    @Test
    fun `frame is header plus audio bytes`() {
        val m = commit("a")
        val entry = outbox.entries().single()
        val out = ByteArrayOutputStream()
        ChunkFrameWriter.write(entry.manifest, entry.audioFile, out)
        val bytes = out.toByteArray()

        val length = ChunkFrame.manifestLength(bytes.copyOfRange(0, 4))
        assertEquals(m, decodeManifest(bytes.copyOfRange(4, 4 + length).decodeToString()))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(4 + length, bytes.size))
    }

    @Test
    fun `silent frame has no audio and audio frames need the file`() {
        val silent = manifest("s", withAudio = false)
        val out = ByteArrayOutputStream()
        ChunkFrameWriter.write(silent, null, out)
        assertContentEquals(ChunkFrame.header(silent), out.toByteArray())

        assertFailsWith<IllegalArgumentException> { ChunkFrameWriter.write(manifest("x"), null, ByteArrayOutputStream()) }
    }

    @Test
    fun `commit without audio removes a leftover file`() {
        val stray = File(tmp.root, "stray.m4a").apply { writeText("x") }
        outbox.commit(manifest("s", withAudio = false), stray)
        assertFalse(stray.exists())
    }
}
