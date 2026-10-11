package app.watchaudio.core.db

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetTest {
    @Test
    fun shortTextIsHighlightedWhole() {
        assertEquals("明日の[会議は]十時からです", highlightSnippet("明日の会議は十時からです", "会議は"))
        assertEquals("[会議]と[会議]の間", highlightSnippet("会議と会議の間", "会議"))
        assertEquals("ヒットなし", highlightSnippet("ヒットなし", "会議"))
    }

    @Test
    fun matchingIgnoresAsciiCaseAndKeepsTheOriginalText() {
        assertEquals("新しい[API]の話", highlightSnippet("新しいAPIの話", "api"))
    }

    @Test
    fun longTextIsCutAroundTheFirstMatch() {
        val text = "あ".repeat(30) + "ミーティング" + "い".repeat(30)
        assertEquals("…" + "あ".repeat(10) + "[ミーティング]" + "い".repeat(24) + "…", highlightSnippet(text, "ミーティング"))
    }

    @Test
    fun windowsAtEitherEndShowOnlyOneEllipsis() {
        val head = "会議" + "あ".repeat(50)
        assertEquals("[会議]" + "あ".repeat(38) + "…", highlightSnippet(head, "会議"))
        val tail = "あ".repeat(50) + "会議"
        assertEquals("…" + "あ".repeat(38) + "[会議]", highlightSnippet(tail, "会議"))
    }

    @Test
    fun aMatchIsNeverCutInHalf() {
        val text = "会議" + "あ".repeat(37) + "会議" + "い".repeat(10)
        assertEquals("[会議]" + "あ".repeat(37) + "[会議]…", highlightSnippet(text, "会議"))
    }
}
