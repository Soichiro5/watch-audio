package app.watchaudio.core.export

import app.watchaudio.core.db.AudioFileInfo
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.export.ExportFixtures.DATE
import app.watchaudio.core.export.ExportFixtures.TOKYO
import app.watchaudio.core.export.ExportFixtures.at
import app.watchaudio.core.export.ExportFixtures.chunk
import app.watchaudio.core.export.ExportFixtures.records
import app.watchaudio.core.protocol.AudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class ExportFormatTest {
    @Test
    fun aDayReadsBackWithoutLosingAnything() {
        val day = ExportFixtures.day()
        val read = decodeExportDay(day.toJson())

        assertEquals(day, read)
        // What the round trip is for (#155): transcripts, marks and gaps survive.
        assertEquals(3, read.segments.size)
        assertEquals(2, read.marks.size)
        assertEquals(4, read.gaps.size)
    }

    @Test
    fun theIndexReadsBack() {
        val index = ExportIndex(
            exportedAt = ExportFixtures.EXPORTED_AT,
            timeZone = "Asia/Tokyo",
            from = LocalDate(2026, 10, 1),
            to = null,
            includesAudio = true,
            days = listOf(ExportIndexDay(DATE, "json/2026-10-10.json", "markdown/2026-10-10.md", 4, 3, 2, 4, 1)),
        )
        assertEquals(index, decodeExportIndex(index.toJson()))
    }

    @Test
    fun readersIgnoreFieldsAddedLaterButRefuseOtherFormatsAndNewerVersions() {
        val json = ExportFixtures.day().toJson()
        val withExtra = json.replaceFirst("\"date\":", "\"addedLater\": {\"x\": 1},\n    \"date\":")
        assertEquals(ExportFixtures.day(), decodeExportDay(withExtra))

        assertFailsWith<IllegalArgumentException> {
            decodeExportDay(json.replace("\"formatVersion\": 1", "\"formatVersion\": ${EXPORT_FORMAT_VERSION + 1}"))
        }
        assertFailsWith<IllegalArgumentException> { decodeExportDay(json.replace(EXPORT_FORMAT, "something-else")) }
        assertFailsWith<IllegalArgumentException> { decodeExportDay("{\"format\": \"$EXPORT_FORMAT\"}") }
        assertFailsWith<IllegalArgumentException> { decodeExportIndex("not json") }
    }

    @Test
    fun pendingChunksAndRecentHolesAreMarkedAsSuch() {
        val pending = chunk("p", at(9), state = ChunkState.TRANSCRIBING)
        val day = buildExportDay(
            DATE,
            TOKYO,
            records.copy(chunks = listOf(pending), overlapping = listOf(pending), previous = null, segments = emptyList(), marks = emptyList()),
            // 20 minutes after the chunk ended: its successor may still be on its way.
            now = at(9, 25),
        )

        assertEquals(ExportTranscription.PENDING, day.chunks.single().transcription)
        // Before the first recording ever is not a gap; after it, the hole up to now is still arriving.
        assertEquals(listOf(ExportGap(at(9, 5), at(9, 25), ExportGapCause.AWAITING)), day.gaps)
    }

    @Test
    fun audioFileNamesSortByTimeAndNeverEscapeTheirFolder() {
        val c1 = chunk("c1", at(7, 5, 9))
        assertEquals("audio/2026-10-10/070509_c1.m4a", audioEntryName(DATE, c1, TOKYO, AudioFileInfo(AudioCodec.AAC, "x")))
        assertEquals("audio/2026-10-10/070509_c1.ogg", audioEntryName(DATE, c1, TOKYO, AudioFileInfo(AudioCodec.OPUS, "x")))
        // Without the codec, the stored file's extension.
        assertEquals("audio/2026-10-10/070509_c1.m4a", audioEntryName(DATE, c1, TOKYO, null))
        val hostile = c1.copy(id = "../../x/y")
        assertTrue(audioEntryName(DATE, hostile, TOKYO, null).removePrefix("audio/2026-10-10/").none { it == '/' })
    }

    @Test
    fun markdownKeepsRecognizedTextLiteral() {
        assertEquals("a\\*b\\* \\[c\\](d) \\#tag \\=\\=x\\=\\= \\<b\\> e", escapeMarkdown("a*b* [c](d) #tag ==x== <b>\ne"))
        assertEquals("会議は10時から", escapeMarkdown("会議は10時から"))
    }
}
