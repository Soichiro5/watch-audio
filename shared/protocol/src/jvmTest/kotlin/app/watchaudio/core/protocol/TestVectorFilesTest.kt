package app.watchaudio.core.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kotlin tests use [TestVectors] (common code cannot read files), the Swift tests the files in
 * testdata/. This keeps the two identical.
 */
class TestVectorFilesTest {
    // Gradle runs JVM tests with the module directory as the working directory.
    private val testdata = File("testdata")

    @Test
    fun everyTestdataFileHasAnIdenticalVector() {
        val files = testdata.listFiles { f -> f.extension == "json" }.orEmpty().associate { it.name to it.readText() }
        assertEquals(files.keys.sorted(), TestVectors.FILES.keys.sorted(), "testdata/*.json vs TestVectors.FILES")
        TestVectors.FILES.forEach { (name, vector) -> assertEquals(files[name], vector, "testdata/$name differs from TestVectors") }
    }
}
