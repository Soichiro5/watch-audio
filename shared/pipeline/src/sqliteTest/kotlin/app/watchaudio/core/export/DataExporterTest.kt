package app.watchaudio.core.export

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.timeline.dayWindow
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testAudio
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** The whole export (#155) over a real database and audio files, read back from the archive. */
class DataExporterTest {
    private val dir = TempDir("export")
    private val audioDir = dir.resolve("audio")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val exporter = DataExporter(store, audioDir)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val oct9 = LocalDate(2026, 10, 9)
    private val oct10 = LocalDate(2026, 10, 10)
    private val oct11 = LocalDate(2026, 10, 11)
    private val oct12 = LocalDate(2026, 10, 12)
    private val exportedAt = at(oct12, 20)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = dayWindow(date, tokyo).start + hour * 3_600_000L + minute * 60_000L

    /** Stores a chunk; with [audioBytes], its audio file is written too (unless [fileMissing]). */
    private suspend fun addChunk(
        id: String,
        start: Long,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        audioBytes: ByteArray? = id.encodeToByteArray() + ByteArray(5_000) { it.toByte() },
        fileMissing: Boolean = false,
        marks: List<Long> = emptyList(),
        segments: List<Pair<Long, String>>? = null,
    ) {
        val relative = audioBytes?.let { "2026/10/$id.m4a" }
        if (audioBytes != null && !fileMissing) {
            val path = Path(audioDir, relative!!)
            SystemFileSystem.createDirectories(path.parent!!)
            SystemFileSystem.sink(path).buffered().use { it.write(audioBytes) }
        }
        val audio = audioBytes?.let { testAudio(id, sizeBytes = it.size.toLong(), sha256 = "sha-$id") }
        store.transaction {
            insertChunk(
                testManifest(id, start, audio = audio, timeZone = "Asia/Tokyo", endReason = endReason, marks = marks),
                relative,
                receivedAt = start + 400_000,
            )
            if (segments != null) {
                markTranscribing(id)
                saveTranscript(id, segments.map { (offset, text) -> NewSegment(start + offset, start + offset + 2_000, text, "ja") }, "test-engine")
            }
        }
    }

    private suspend fun seed() {
        addChunk("a0", at(oct9, 22), ChunkEndReason.USER_STOP, marks = listOf(60_000), segments = listOf(10_000L to "前日の発話"))
        addChunk("b0", at(oct10, 7), marks = listOf(120_000), segments = listOf(20_000L to "おはようございます", 180_000L to "会議は *10時* から"))
        addChunk("b1", at(oct10, 7, 5), ChunkEndReason.CHARGING, segments = listOf(60_000L to "了解です"))
        addChunk("b2", at(oct10, 9), audioBytes = null)
        addChunk("b3", at(oct10, 12), fileMissing = true) // pending, and its file is gone
        addChunk("c0", at(oct12, 8), segments = listOf(5_000L to "三日目"))
        store.transaction { markAudioPurged(listOf("c0"), at(oct12, 9)) }
        SystemFileSystem.delete(Path(audioDir, "2026/10/c0.m4a"))
    }

    private suspend fun export(range: ExportRange, includeAudio: Boolean): Triple<ExportPlan, ExportSummary, Map<String, ByteArray>> {
        val plan = assertNotNull(exporter.plan(ExportRequest(range, includeAudio, tokyo, exportedAt)))
        val buffer = Buffer()
        val progress = mutableListOf<ExportProgress>()
        val summary = exporter.write(plan, buffer) { progress += it }
        assertEquals((1..plan.dayCount).map { ExportProgress(it, plan.dayCount) }, progress)
        return Triple(plan, summary, readStoredZip(buffer.readByteArray()))
    }

    @Test
    fun everythingReadsBackFromTheArchive() = runTest {
        seed()
        val (plan, summary, files) = export(ExportRange.ALL, includeAudio = true)

        assertEquals(oct9, plan.firstDay)
        assertEquals(oct12, plan.lastDay)
        assertEquals("watch-audio-2026-10-09_2026-10-12.zip", plan.fileName)
        assertEquals(ExportSummary(days = 3, chunks = 6, segments = 5, marks = 2, audioFiles = 3, missingAudioFiles = 1), summary)

        val root = "watch-audio-2026-10-09_2026-10-12/"
        assertTrue(files.keys.all { it.startsWith(root) }, "everything under one folder: ${files.keys}")
        val index = decodeExportIndex(files.getValue("${root}export.json").decodeToString())
        assertEquals(listOf(oct9, oct10, oct12), index.days.map { it.date })
        assertEquals(tokyo.id, index.timeZone)
        assertTrue(index.includesAudio)
        assertNull(index.from)

        index.days.forEach { entry ->
            val day = decodeExportDay(files.getValue(root + entry.json).decodeToString())
            assertTrue(files.getValue(root + entry.markdown).decodeToString().startsWith("---\ndate: ${entry.date}\n"))
            // The day as the database holds it, with the audio files the archive has.
            val records = store.read { readDayRecords(dayWindow(entry.date, tokyo)) }
            val included = day.chunks.mapNotNull { chunk -> chunk.audio?.file?.let { chunk.id to it } }.toMap()
            assertEquals(buildExportDay(entry.date, tokyo, records, exportedAt, included), day)
            assertEquals(day.segments.size, entry.segments)
            included.forEach { (id, file) ->
                val original = SystemFileSystem.source(Path(audioDir, "2026/10/$id.m4a")).buffered().use { it.readByteArray() }
                assertEquals(original.toList(), files.getValue(root + file).toList())
            }
        }

        // Nothing lost: every transcript segment, mark and gap of the store is in the export.
        val days = index.days.map { decodeExportDay(files.getValue(root + it.json).decodeToString()) }
        val allSegments = store.read { segmentsBetween(Long.MIN_VALUE, Long.MAX_VALUE) }
        assertEquals(allSegments.map { it.chunkId to it.text }, days.flatMap { day -> day.segments.map { it.chunkId to it.text } })
        assertEquals(listOf("a0" to at(oct9, 22, 1), "b0" to at(oct10, 7, 2)), days.flatMap { day -> day.marks.map { it.chunkId to it.at } })
        val oct10Day = days[1]
        assertEquals(
            listOf(
                ExportGap(at(oct10, 0), at(oct10, 7), ExportGapCause.STOPPED, ChunkEndReason.USER_STOP),
                ExportGap(at(oct10, 7, 10), at(oct10, 9), ExportGapCause.STOPPED, ChunkEndReason.CHARGING),
                ExportGap(at(oct10, 9, 5), at(oct10, 12), ExportGapCause.MISSING),
                ExportGap(at(oct10, 12, 5), at(oct11, 0), ExportGapCause.MISSING),
            ),
            oct10Day.gaps,
        )
        // b3's file is gone and c0's audio was purged: described, but not in the archive.
        assertNull(oct10Day.chunks.single { it.id == "b3" }.audio?.file)
        assertEquals(ExportTranscription.PENDING, oct10Day.chunks.single { it.id == "b3" }.transcription)
        assertEquals(at(oct12, 9), days[2].chunks.single().audio?.purgedAt)
        assertEquals(3, files.keys.count { it.startsWith("${root}audio/") })
    }

    @Test
    fun aDateRangeWithoutAudio() = runTest {
        seed()
        val (plan, summary, files) = export(ExportRange(oct10, oct11), includeAudio = false)

        assertEquals("watch-audio-2026-10-10.zip", plan.fileName)
        assertEquals(1, plan.dayCount)
        assertEquals(0, summary.audioFiles)
        assertEquals(0, summary.missingAudioFiles)
        assertEquals(
            listOf("README.md", "export.json", "json/2026-10-10.json", "markdown/2026-10-10.md"),
            files.keys.map { it.removePrefix("watch-audio-2026-10-10/") }.sorted(),
        )
        val index = decodeExportIndex(files.getValue("watch-audio-2026-10-10/export.json").decodeToString())
        assertEquals(oct10, index.from)
        assertEquals(oct11, index.to)
        val day = decodeExportDay(files.getValue("watch-audio-2026-10-10/json/2026-10-10.json").decodeToString())
        assertTrue(day.chunks.none { it.audio?.file != null })
        assertEquals(listOf("b0", "b1", "b2", "b3"), day.chunks.map { it.id })
    }

    @Test
    fun nothingToExport() = runTest {
        assertNull(exporter.plan(ExportRequest(ExportRange.ALL, true, tokyo, exportedAt)))
        seed()
        assertNull(exporter.plan(ExportRequest(ExportRange(oct11, oct11), true, tokyo, exportedAt)))
        assertNull(exporter.plan(ExportRequest(ExportRange(from = LocalDate(2026, 10, 13)), true, tokyo, exportedAt)))
        assertEquals(oct12, exporter.plan(ExportRequest(ExportRange(from = oct11), false, tokyo, exportedAt))?.firstDay)
    }
}
