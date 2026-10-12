package app.watchaudio.core.db

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Reads behind the data export (#155): everything follows the chunk it belongs to. */
class ExportQueriesTest {
    private val dir = TempDir("export-queries")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun readsTheChunksThatStartedInTheRangeOnly() = runTest {
        store.registerChunk("before", 0, marks = listOf(1_000))
        store.registerChunk("first", 300_000, marks = listOf(2_000, 299_000))
        store.registerChunk("silent", 600_000, audio = false, marks = listOf(5_000))
        store.registerChunk("after", 900_000, marks = listOf(3_000))
        store.transaction {
            saveTranscript("before", listOf(NewSegment(299_000, 301_000, "前の発話", "ja")), "engine-a")
            // Starts in "first" but runs into "silent": it still belongs to "first".
            saveTranscript(
                "first",
                listOf(NewSegment(301_000, 302_000, "一つ目", "ja"), NewSegment(599_000, 601_000, "またぐ発話", null)),
                "engine-b",
            )
            saveTranscript("after", listOf(NewSegment(901_000, 902_000, "後の発話", "ja")), "engine-a")
        }

        val (segments, marks, audio) = store.read {
            Triple(
                segmentsOfChunksStartedBetween(300_000, 900_000),
                marksOfChunksStartedBetween(300_000, 900_000),
                audioFilesOfChunksStartedBetween(300_000, 900_000),
            )
        }

        assertEquals(
            listOf(
                Triple("first", "一つ目", "engine-b"),
                Triple("first", "またぐ発話", "engine-b"),
            ),
            segments.map { Triple(it.chunkId, it.text, it.engine) },
        )
        assertNull(segments[1].lang)
        assertEquals(
            listOf(MarkRecord("first", 302_000), MarkRecord("silent", 605_000), MarkRecord("first", 599_000)).sortedBy { it.at },
            marks,
        )
        // The silent chunk has no audio file; the test manifests use AAC and checksum "00".
        assertEquals(mapOf("first" to AudioFileInfo(AudioCodec.AAC, "00")), audio)
    }

    @Test
    fun findsTheFirstChunkFromAPointInTime() = runTest {
        assertNull(store.read { firstChunkStartAtOrAfter(Long.MIN_VALUE) })
        store.registerChunk("b", 600_000)
        store.registerChunk("a", 300_000)

        assertEquals(300_000, store.read { firstChunkStartAtOrAfter(Long.MIN_VALUE) })
        assertEquals(600_000, store.read { firstChunkStartAtOrAfter(300_001) })
        assertNull(store.read { firstChunkStartAtOrAfter(600_001) })
    }
}
