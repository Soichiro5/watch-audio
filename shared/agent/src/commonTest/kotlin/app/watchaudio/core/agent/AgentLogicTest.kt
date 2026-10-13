package app.watchaudio.core.agent

import app.watchaudio.core.db.SegmentRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLogicTest {
    @Test
    fun rateLimiterSlidesOverOneMinute() {
        val limiter = RateLimiter(perMinute = 2)
        assertTrue(limiter.tryAcquire(0))
        assertTrue(limiter.tryAcquire(10_000))
        assertFalse(limiter.tryAcquire(59_999))
        assertTrue(limiter.tryAcquire(60_000)) // the first call left the window
        assertFalse(limiter.tryAcquire(60_001))
    }

    @Test
    fun framesSurviveAnySplit() {
        val messages = listOf(Hello(1, AgentRole.PHONE, "電話", "test"), PairConfirm, Bye(ByeReason.ACCESS_OFF))
        val stream = messages.fold(ByteArray(0)) { acc, m -> acc + AgentWire.frame(m) }
        for (split in listOf(1, 3, 7, stream.size)) {
            val decoder = FrameDecoder()
            val out = stream.toList().chunked(split).flatMap { decoder.feed(it.toByteArray()) }
            assertEquals(messages, out, "split $split")
        }
        assertFailsWith<FrameException> { FrameDecoder().feed(byteArrayOf(0, 0, 0, 2) + "{}".encodeToByteArray()) }
        assertFailsWith<FrameException> { FrameDecoder(maxFrameBytes = 10).feed(AgentWire.frame(Bye("a long reason here"))) }
    }

    @Test
    fun hostPorts() {
        assertEquals("192.168.1.2" to 47823, splitHostPort("192.168.1.2:47823"))
        assertEquals("fe80::1" to 80, splitHostPort("[fe80::1]:80"))
        assertNull(splitHostPort("fe80::1:80"))
        assertNull(splitHostPort("host:0"))
        assertNull(splitHostPort("host"))
    }

    @Test
    fun conversationsSplitAtLongPauses() {
        fun seg(id: Long, start: Long, end: Long, text: String) = SegmentRecord(id, "c", start, end, text, "ja", "e")
        val groups = groupConversations(
            listOf(
                seg(3, 400_000, 410_000, "三"),
                seg(1, 0, 10_000, "一"),
                seg(2, 300_000, 310_000, "二"), // 290 s after the first: same conversation
                seg(4, 800_000, 810_000, "四"), // 390 s pause: a new one
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(Conversation(0, 410_000, 3, 1, "一 二 三"), groups[0])
        assertEquals(Conversation(800_000, 810_000, 1, 4, "四"), groups[1])
    }

    @Test
    fun wavHeader() {
        val wav = wavPcm16(floatArrayOf(0f, 1f, -1f), 16_000)
        assertEquals(50, wav.size)
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertEquals(0x7f, wav[47].toInt() and 0xff) // 32767 little-endian
    }

    @Test
    fun percentEncodingRoundTrips() {
        val text = "Taro's MacBook ＆ 電話 100%"
        assertEquals(text, percentDecode(percentEncode(text)))
        assertEquals("a%20b", percentEncode("a b"))
    }
}
