package app.watchaudio.core.embedding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scripts that fetch the embedding model for tests download exactly what the apps download
 * ([RuriV3.FILES]): the iOS simulator check's (`.github/scripts/ios-embedding-model.sh`, #178) and
 * the harness's (`tools/harness/download-models.sh`, also checked by Android's ModelManifestTest).
 */
class RuriFilesScriptTest {
    @Test
    fun theTestScriptsFetchTheAppsFiles() {
        for (script in listOf("../../.github/scripts/ios-embedding-model.sh", "../../tools/harness/download-models.sh")) {
            val text = File(script).readText()
            val variables = Regex("""^(\w+)=(\S+)$""", RegexOption.MULTILINE).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val fetched = Regex("""^fetch "([^"]+)" ([0-9a-f]{64}) "([^"]+)"$""", RegexOption.MULTILINE).findAll(text).map { match ->
                val expand = { value: String -> Regex("""\$(\w+)""").replace(value) { variables[it.groupValues[1]] ?: it.value } }
                Triple(expand(match.groupValues[1]), match.groupValues[2], expand(match.groupValues[3]))
            }.toSet()
            for (file in RuriV3.FILES) {
                assertTrue(Triple(file.path, file.sha256, file.url) in fetched, "$script must fetch ${file.path} (${file.sha256}) from ${file.url}; it fetches $fetched")
            }
        }
    }
}
