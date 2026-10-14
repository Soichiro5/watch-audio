package app.watchaudio.harness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ground truth of the summary evaluation (#197) stays faithful to the corpus: every fact is
 * said by its supporting lines (its keys match them), and no `mustNot` claim is said anywhere in
 * the conversation. Needs no model.
 */
class SummaryEvalSetTest {
    private val repoRoot = File("").absoluteFile.parentFile.parentFile
    private val facts = SummaryFactSet.load(File(repoRoot, SummaryFactSet.PATH))
    private val corpus = CorpusConversation.loadAll(File(repoRoot, CorpusConversation.DIRECTORY))

    @Test
    fun everyConversationHasThreeToSixFacts() {
        assertEquals(16, corpus.size)
        assertEquals(corpus.map { it.id }, facts.conversations.map { it.id })
        for (c in facts.conversations) {
            assertTrue(c.facts.size in 3..6, "${c.id}: ${c.facts.size} facts")
            assertTrue(c.mustNot.isNotEmpty(), "${c.id}: no mustNot")
            val ids = c.facts.map { it.id } + c.mustNot.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "${c.id}: duplicate ids")
        }
    }

    @Test
    fun everyFactIsSaidByItsLines() {
        for (truth in facts.conversations) {
            val conversation = corpus.single { it.id == truth.id }
            for (fact in truth.facts) {
                assertTrue(fact.lines.isNotEmpty() && fact.keys.isNotEmpty(), "${truth.id} ${fact.id}: empty")
                assertTrue(fact.lines.all { it in 1..conversation.lines.size }, "${truth.id} ${fact.id}: lines ${fact.lines}")
                val said = fact.lines.joinToString("\n") { conversation.lines[it - 1].text }
                assertTrue(KeyTerms.matches(fact.keys, said), "${truth.id} ${fact.id}: keys ${fact.keys} not in lines ${fact.lines}: $said")
            }
        }
    }

    @Test
    fun noMustNotClaimIsInTheConversation() {
        for (truth in facts.conversations) {
            val conversation = corpus.single { it.id == truth.id }
            for (claim in truth.mustNot) {
                assertFalse(KeyTerms.matches(claim.keys, conversation.text), "${truth.id} ${claim.id}: ${claim.keys} occurs in the script")
            }
        }
    }

    @Test
    fun scriptsParseLikeCorpusPy() {
        val c = CorpusConversation.parse(
            "x",
            """
            # title: テスト
            # speakers: A=v B=w

            A: {Zoom|ズーム}のリンクです。
            B: はい: わかりました。
            """.trimIndent(),
        )
        assertEquals("テスト", c.title)
        assertEquals(listOf(ScriptLine(1, "A", "Zoomのリンクです。"), ScriptLine(2, "B", "はい: わかりました。")), c.lines)
        // Line numbers agree with semantic-eval-queries.json: 01 line 15 is the server cost.
        assertTrue("12万円" in corpus.first().lines[14].text)
    }

    @Test
    fun keyTermsNormalizeNumbersAndPunctuation() {
        val keys = listOf(listOf("サーバー"), listOf("12万"))
        assertTrue(KeyTerms.matches(keys, "サーバー費用が月に十二万円増える"))
        assertTrue(KeyTerms.matches(keys, "サーバー費用：月 120,000 円の増加"))
        assertFalse(KeyTerms.matches(keys, "サーバー費用が増える"), "every group must match")
        assertTrue(KeyTerms.matches(listOf(listOf("QR", "スマホ")), "qrコードで払う"), "case-insensitive, any alternative")
        assertTrue(KeyTerms.matches(listOf(listOf("6時半", "18時半")), "１８時半から"), "full-width digits")
        assertFalse(KeyTerms.matches(emptyList(), "anything"))
    }
}
