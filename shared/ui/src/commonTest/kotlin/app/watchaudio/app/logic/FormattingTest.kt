package app.watchaudio.app.logic

import app.watchaudio.core.protocol.ChunkEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class FormattingTest {
    @Test
    fun snippetMarkersBecomeHighlightedParts() {
        assertEquals(
            listOf(SnippetPart("明日の", false), SnippetPart("会議は", true), SnippetPart("十時からです", false)),
            parseSnippet("明日の[会議は]十時からです"),
        )
        assertEquals(
            listOf(SnippetPart("…a ", false), SnippetPart("x", true), SnippetPart(" b ", false), SnippetPart("x", true)),
            parseSnippet("…a [x] b [x]"),
        )
    }

    @Test
    fun malformedSnippetsDegradeGracefully() {
        assertEquals(emptyList(), parseSnippet(""))
        assertEquals(listOf(SnippetPart("a", false), SnippetPart("bc", true)), parseSnippet("a[bc"))
        assertEquals(listOf(SnippetPart("a]b", false)), parseSnippet("a]b"))
        assertEquals(listOf(SnippetPart("ab", false)), parseSnippet("a[]b"))
    }

    @Test
    fun clockAndDateInLocalTimeZone() {
        val t = at(9, 5) + 7_000
        assertEquals("09:05", formatClock(t, TOKYO))
        assertEquals("00:05", formatClock(t, TimeZone.UTC))
        assertEquals(DAY, localDateOf(t, TOKYO))
        assertEquals(LocalDate(2026, 10, 10), localDateOf(at(0), TOKYO))
        assertEquals(LocalDate(2026, 10, 9), localDateOf(at(0) - 1, TOKYO))
        assertEquals("2026年10月10日(土)", formatDate(DAY))
        assertEquals("10月10日(土)", formatDateShort(DAY))
        assertEquals("22:00–24:00", formatClockRange(at(22), WINDOW.end, TOKYO))
        assertEquals("09:00–09:05", formatClockRange(at(9), at(9, 5), TOKYO))
    }

    @Test
    fun durationsAndSizes() {
        assertEquals("45秒", formatDuration(45_000))
        assertEquals("12分", formatDuration(12 * MIN + 30_000))
        assertEquals("3時間", formatDuration(3 * HOUR))
        assertEquals("3時間12分", formatDuration(3 * HOUR + 12 * MIN))
        assertEquals("たった今", formatAgo(30_000))
        assertEquals("5分前", formatAgo(5 * MIN))
        assertEquals("1.5 MB", formatMegabytes(1_572_864))
        assertEquals("0.0 MB", formatMegabytes(0))
        assertEquals("12.5 時間", formatHours(12 * HOUR + 30 * MIN))
        assertEquals("62%", formatPercent(0.625f))
        assertEquals("100%", formatPercent(1.2f))
    }

    @Test
    fun arrivalWarningOnlyWhileRecordingShouldContinue() {
        val now = at(12)
        assertEquals(ArrivalState.NoData, arrivalState(null, null, now))

        val rotating = chunk("a", at(11, 50))
        assertIs<ArrivalState.Receiving>(arrivalState(rotating, now - 5 * MIN, now))
        val late = assertIs<ArrivalState.Late>(arrivalState(rotating, now - 11 * MIN, now))
        assertEquals(11 * MIN, late.silentForMs)

        val charging = chunk("b", at(10), endReason = ChunkEndReason.CHARGING)
        val stopped = assertIs<ArrivalState.Stopped>(arrivalState(charging, now - 2 * HOUR, now))
        assertEquals(ChunkEndReason.CHARGING, stopped.reason)
    }
}
