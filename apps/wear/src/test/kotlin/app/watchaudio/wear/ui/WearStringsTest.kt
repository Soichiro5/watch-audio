package app.watchaudio.wear.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards #144: everything the watch shows (app, tile, complication, notifications, the debug input
 * screen of debug builds) is Japanese. Reads the resource XML directly, so it needs no device.
 */
class WearStringsTest {
    private val resourceFiles = listOf("src/main/res/values/strings.xml", "src/debug/res/values/strings.xml")

    @Test
    fun noStringResourceContainsEnglishWords() {
        val offenders = resourceFiles.flatMap { path -> strings(path).map { (name, value) -> "$path:$name" to value } }
            .filter { (_, value) -> englishWord.containsMatchIn(withoutAllowed(value)) }
        assertEquals(emptyList(), offenders, "English text in Wear OS strings (allowed only: $allowedTokens)")
    }

    @Test
    fun onlyTheBrandNameIsAsciiOnly() {
        val asciiOnly = resourceFiles.flatMap { strings(it).entries }
            .filter { (_, value) -> value.all { it.code < 128 } }
            .map { it.key }
            .toSet()
        assertEquals(setOf("app_name", "tile_label"), asciiOnly)
    }

    @Test
    fun complicationShortTextsFitAShortTextSlot() {
        val short = strings("src/main/res/values/strings.xml").filterKeys { it.startsWith("status_short_") }
        assertTrue(short.size >= 8, "status_short_* strings missing: $short")
        short.forEach { (name, value) -> assertTrue(value.length <= 3, "$name is too long for a complication: $value") }
    }

    @Test
    fun theCheckFindsEnglish() {
        assertTrue(englishWord.containsMatchIn(withoutAllowed("Paused · not worn")))
        assertTrue(englishWord.containsMatchIn(withoutAllowed("マーク Mark")))
        assertTrue(!englishWord.containsMatchIn(withoutAllowed("最終受領 %1\$s")))
        assertTrue(!englishWord.containsMatchIn(withoutAllowed("入力: %1\$s ×%2$.0f・%3\$d 秒")))
    }

    private fun strings(path: String): Map<String, String> {
        val file = File(path)
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun withoutAllowed(value: String): String =
        allowedTokens.fold(value.replace(formatSpecifier, " ")) { text, token -> text.replace(token, " ") }

    private companion object {
        /** Brand and units that may stay ASCII. */
        val allowedTokens = listOf("watch-audio")
        val formatSpecifier = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]|\\n""")
        val englishWord = Regex("[A-Za-z]{2,}")
    }
}
