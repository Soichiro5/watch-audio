package app.watchaudio.android.asr

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDownloaderTest {
    private val dir = Files.createTempDirectory("models").toFile()
    private val body = Random(46).nextBytes(300_000)
    private val server = FakeServer(body)
    private val target = File(dir, "m/encoder.int8.onnx")
    private val part = File(dir, ".partial/x.part")
    private val source = ModelSource(server.url, "m/encoder.int8.onnx", body.size.toLong(), sha256(body))

    @AfterTest
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun resumesAfterTheConnectionIsCut() {
        server.cuts += 100_000
        assertFailsWith<IOException> { ModelDownloader().download(source, target, part) }
        assertFalse(target.exists(), "an incomplete download must not appear as the model")
        val kept = part.length()
        assertTrue(kept in 1 until body.size, "the received prefix is kept for resuming, was $kept")

        var progress = 0L
        ModelDownloader().download(source, target, part) { progress = it }

        assertContentEquals(body, target.readBytes())
        assertEquals(source.sha256, ModelDownloader.sha256(target))
        assertFalse(part.exists())
        assertEquals(listOf(null, "bytes=$kept-"), server.ranges)
        assertEquals(body.size.toLong(), progress)
    }

    @Test
    fun corruptedPartIsDownloadedAgain() {
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(150_000) { 7 })

        ModelDownloader().download(source, target, part)

        assertContentEquals(body, target.readBytes())
        assertEquals(listOf("bytes=150000-", null), server.ranges, "resume first, then start over after the SHA mismatch")
    }

    @Test
    fun completePartIsOnlyVerified() {
        part.parentFile!!.mkdirs()
        part.writeBytes(body)

        ModelDownloader().download(source, target, part)

        assertContentEquals(body, target.readBytes())
        assertEquals(emptyList(), server.ranges)
    }

    @Test
    fun serverIgnoringRangeRestartsFromTheBeginning() {
        server.honourRange = false
        part.parentFile!!.mkdirs()
        part.writeBytes(body.copyOf(1_000))

        ModelDownloader().download(source, target, part)

        assertContentEquals(body, target.readBytes())
        assertEquals(listOf<String?>("bytes=1000-"), server.ranges)
    }

    @Test
    fun wrongContentIsRejected() {
        val wrong = source.copy(sha256 = sha256(byteArrayOf(1)))
        assertFailsWith<IOException> { ModelDownloader().download(wrong, target, part) }
        assertFalse(target.exists())
        assertFalse(part.exists(), "unverifiable bytes must not be resumed")
    }

    @Test
    fun httpErrorKeepsNothing() {
        server.status = 404
        assertFailsWith<IOException> { ModelDownloader().download(source, target, part) }
        assertFalse(target.exists())
    }

    @Test
    fun abortsWhenCancelled() {
        assertFailsWith<IllegalStateException> {
            ModelDownloader().download(source, target, part, checkActive = { error("cancelled") })
        }
        assertFalse(target.exists())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Minimal HTTP/1.1 file server on a raw socket, so a response can be cut in the middle of
     * the body. Honours `Range: bytes=<n>-` with 206 unless [honourRange] is false.
     */
    private class FakeServer(private val body: ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

        /** The Range header of each request (null when absent). */
        val ranges = CopyOnWriteArrayList<String?>()

        /** For each upcoming response in turn: close the connection after this many body bytes. */
        val cuts = ConcurrentLinkedQueue<Int>()

        @Volatile var honourRange = true

        @Volatile var status = 200

        val url = "http://127.0.0.1:${socket.localPort}/model.onnx"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    client.use(::serve)
                }
            }
        }

        private fun serve(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            reader.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val (name, value) = line.split(":", limit = 2).map { it.trim() }
                if (name.equals("Range", ignoreCase = true)) range = value
            }
            ranges += range
            val output = client.getOutputStream()
            if (status != 200) {
                output.write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val start = range?.takeIf { honourRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val headers = buildString {
                append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Length: ${body.size - start}\r\n")
                if (start > 0) append("Content-Range: bytes $start-${body.size - 1}/${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray())
            val end = cuts.poll()?.let { minOf(start + it, body.size) } ?: body.size
            output.write(body, start, end - start)
            output.flush()
        }

        override fun close() = socket.close()
    }
}
