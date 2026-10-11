package app.watchaudio.android.asr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelManifestTest {
    @Test
    fun firstDownloadIsUnder200MB() {
        val total = ModelManifest.totalBytes
        println("model download: ${ModelManifest.files.size} files, $total bytes")
        ModelManifest.files.forEach { println("  ${it.size}\t${it.url}") }
        assertTrue(total < 200_000_000, "first download is $total bytes")
    }

    @Test
    fun coversExactlyTheFilesTheTranscriberNeeds() {
        val files = ModelFiles(File("/models"))
        assertEquals(files.all.toSet(), ModelManifest.files.map(files::fileFor).toSet())
        assertEquals(ModelManifest.files.size, ModelManifest.files.map { it.path }.distinct().size)
    }

    @Test
    fun entriesArePinnedAndVerifiable() {
        for (source in ModelManifest.files) {
            assertTrue(source.url.startsWith("https://"), source.url)
            assertTrue(!source.url.contains("/resolve/main/"), "pin a commit, not a branch: ${source.url}")
            assertTrue(Regex("[0-9a-f]{64}").matches(source.sha256), source.sha256)
            assertTrue(source.size > 0)
        }
    }

    /** The harness E2E must transcribe with the very files the app downloads. */
    @Test
    fun harnessScriptDownloadsTheSameFiles() {
        // Unit tests run in the module directory (apps/android/).
        val script = File("").absoluteFile.parentFile.resolveSibling("tools/harness/download-models.sh")
        val variables = mutableMapOf<String, String>()
        val fetched = mutableSetOf<ModelSource>()
        for (raw in script.readLines()) {
            val line = variables.entries.sortedByDescending { it.key.length }
                .fold(raw.trim()) { text, (name, value) -> text.replace("\${$name}", value).replace("\$$name", value) }
            ASSIGNMENT.matchEntire(line)?.let { variables[it.groupValues[1]] = it.groupValues[2] }
            FETCH.matchEntire(line)?.let { fetched += ModelSource(it.groupValues[3], it.groupValues[1], 0, it.groupValues[2]) }
        }
        for (source in ModelManifest.files) {
            assertTrue(source.copy(size = 0) in fetched, "download-models.sh must fetch ${source.path} from ${source.url}")
        }
    }

    private companion object {
        val ASSIGNMENT = Regex("""([A-Z_]+)=(\S+)""")
        val FETCH = Regex("""fetch "([^"]+)" ([0-9a-f]{64}) "([^"]+)"""")
    }
}
