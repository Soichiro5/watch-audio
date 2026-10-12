package app.watchaudio.harness

import app.watchaudio.core.search.HybridSearch
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The completion criterion of #24 on this machine: conversation-like paraphrase queries find the
 * conversation in the top 5 with the hybrid search, where the keyword search alone mostly cannot
 * ([SemanticEval], real model, the app's code path). Writes the tables to
 * `build/reports/semantic-eval.md` (also with other meaning bands, to choose
 * [HybridSearch.MEANING_BAND]).
 */
class SemanticSearchEvalTest {
    private val models = File(System.getProperty("watchaudio.models") ?: "models")
    private val repoRoot = File("").absoluteFile.parentFile.parentFile

    @Test
    fun paraphrasesAreFoundByMeaning() = runBlocking {
        if (!HarnessEmbedder.available(models)) {
            check(System.getProperty("watchaudio.requireModels") != "true") { "embedding model missing in $models" }
            println("SKIPPED: run tools/harness/download-models.sh to enable the semantic search evaluation")
            return@runBlocking
        }
        val workDir = Files.createTempDirectory("semantic-eval").toFile()
        val embedder = HarnessEmbedder.create(models)
        val sweep = listOf(1f, 0.10f, 0.06f, HybridSearch.MEANING_BAND, 0.03f, 0.02f, 0.01f).distinct().sortedDescending()
        val report = try {
            SemanticEval(repoRoot, workDir, embedder).run(sweep)
        } finally {
            embedder.release()
            workDir.deleteRecursively()
        }
        val outcomes = report.outcomes.getValue(HybridSearch.MEANING_BAND)
        val text = buildString {
            appendLine("# Semantic search evaluation (#24)")
            appendLine()
            appendLine("${report.chunks} chunks, ${report.segments} segments, ${report.passages} passages, embedded in %.1f s (1 thread)".format(report.embedSeconds))
            appendLine()
            appendLine("## Top-5 hit rate (meaning band ${HybridSearch.MEANING_BAND})")
            appendLine()
            append(SemanticEval.table(outcomes))
            appendLine()
            appendLine("## Meaning band sweep (hybrid top-5 hit rate overall / paraphrases; meaning-only rows in the top 5 of keyword queries)")
            appendLine()
            appendLine("| band | hybrid overall | corpus_para | conv_ja_para | meaning-only rows in top 5, kw queries (avg) |")
            appendLine("|---|---|---|---|---|")
            for ((band, list) in report.outcomes) {
                fun rate(type: String?) = list.filter { type == null || it.query.type == type }.let { g -> "%.2f".format(g.count { it.hybridRank != null }.toDouble() / g.size) }
                val kw = list.filter { it.query.type.endsWith("kw") }
                val noise = kw.sumOf { o -> o.hybridTop.count { it.startsWith("M:") } }.toDouble() / kw.size
                appendLine("| $band | ${rate(null)} | ${rate("corpus_para")} | ${rate("conv_ja_para")} | %.1f |".format(noise))
            }
            appendLine()
            appendLine("## Scores (best relevant passage / best other passage)")
            appendLine()
            for (o in outcomes) {
                appendLine(
                    "- ${o.query.id} [${o.query.type}] ${o.query.text}: kw=${o.keywordRank ?: "-"} hybrid=${o.hybridRank ?: "-"} " +
                        "meaning=${o.meaningRank ?: "-"} rel=%.3f other=%.3f".format(o.bestRelevantScore ?: Float.NaN, o.bestOtherScore ?: Float.NaN),
                )
            }
        }
        println(text)
        File("build/reports").mkdirs()
        File("build/reports/semantic-eval.md").writeText(text)

        val para = outcomes.filter { it.query.type.endsWith("para") }
        val keywordPara = para.count { it.keywordRank != null }
        val hybridPara = para.count { it.hybridRank != null }
        assertTrue(hybridPara >= keywordPara + para.size / 2, "paraphrases: hybrid $hybridPara vs keyword $keywordPara of ${para.size}")
        // Keyword queries must not get worse.
        val keyword = outcomes.filter { it.query.type.endsWith("kw") }
        assertTrue(keyword.count { it.hybridRank != null } >= keyword.count { it.keywordRank != null }, "keyword queries got worse")
    }
}
