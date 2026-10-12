package app.watchaudio.core.backup

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.export.DataExporter
import app.watchaudio.core.export.ExportRange
import app.watchaudio.core.export.ExportRequest
import app.watchaudio.core.export.readStoredZip
import app.watchaudio.core.platform.Sha256
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.timeline.dayWindow
import app.watchaudio.testing.testAudio
import app.watchaudio.testing.testManifest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * A fixed set of recordings for the backup tests (#40): three days in Tokyo with transcribed,
 * pending, failed, silent and purged chunks, marks and gaps. Everything is deterministic, so a
 * backup of it is the same bytes on every platform ([BackupFixtureTest]).
 */
internal object BackupTestData {
    val tokyo = TimeZone.of("Asia/Tokyo")
    val oct9 = LocalDate(2026, 10, 9)
    val oct10 = LocalDate(2026, 10, 10)
    val oct12 = LocalDate(2026, 10, 12)

    /** When the backup is made. */
    val createdAt = at(oct12, 20)

    const val PASSPHRASE = "とけいの記録 2026 backup"

    /** Salt and nonce for reproducible files. */
    val fixedRandom: (Int) -> ByteArray = { n -> ByteArray(n) { (0xA0 + it * 3 + n).toByte() } }

    fun at(date: LocalDate, hour: Int, minute: Int = 0) = dayWindow(date, tokyo).start + hour * 3_600_000L + minute * 60_000L

    fun clockAt(epochMs: Long): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    fun audioBytes(id: String): ByteArray = "audio of $id ".encodeToByteArray() + ByteArray(400) { (it * 13 + id.length).toByte() }

    /** Stores the recordings in [store], their audio under [audioRoot] (as the ingest lays it out). */
    suspend fun seed(store: LocalStore, audioRoot: Path) {
        add(store, audioRoot, "a0", at(oct9, 22), ChunkEndReason.USER_STOP, marks = listOf(60_000), segments = listOf(10_000L to "前日の発話"))
        add(store, audioRoot, "b0", at(oct10, 7), marks = listOf(120_000, 180_000), segments = listOf(20_000L to "おはようございます", 180_000L to "会議は *10時* から"))
        add(store, audioRoot, "b1", at(oct10, 7, 5), ChunkEndReason.CHARGING, segments = listOf(60_000L to "了解です"))
        add(store, audioRoot, "b2", at(oct10, 9), audio = false)
        add(store, audioRoot, "b3", at(oct10, 12)) // waiting for transcription
        add(store, audioRoot, "b4", at(oct10, 12, 5), ChunkEndReason.OFF_BODY)
        store.transaction {
            markTranscribing("b4")
            markFailed("b4", "decoder error")
        }
        add(store, audioRoot, "c0", at(oct12, 8), segments = listOf(5_000L to "三日目", 65_000L to "English and 日本語"), engine = "other-engine")
        store.transaction { markAudioPurged(listOf("c0"), at(oct12, 9)) }
        SystemFileSystem.delete(Path(audioRoot, archivePathOf("c0", at(oct12, 8))))
    }

    fun archivePathOf(id: String, startedAt: Long) = app.watchaudio.core.pipeline.Ingestor.archivePath(id, startedAt, "m4a")

    private suspend fun add(
        store: LocalStore,
        audioRoot: Path,
        id: String,
        start: Long,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        audio: Boolean = true,
        marks: List<Long> = emptyList(),
        segments: List<Pair<Long, String>>? = null,
        engine: String = "test-engine",
    ) {
        val bytes = audioBytes(id)
        val relative = if (audio) archivePathOf(id, start) else null
        if (relative != null) {
            val path = Path(audioRoot, relative)
            SystemFileSystem.createDirectories(path.parent!!)
            SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        }
        val file = if (audio) testAudio(id, sizeBytes = bytes.size.toLong(), sha256 = Sha256().apply { update(bytes) }.digestHex()) else null
        store.transaction {
            insertChunk(testManifest(id, start, audio = file, timeZone = "Asia/Tokyo", endReason = endReason, marks = marks), relative, receivedAt = start + 400_000)
            if (segments != null) {
                markTranscribing(id)
                saveTranscript(id, segments.map { (offset, text) -> NewSegment(start + offset, start + offset + 2_000, text, "ja") }, engine)
            }
        }
    }

    /** The export JSON day files of [store] (no audio), by archive path: what a user would get back. */
    suspend fun exportedDays(store: LocalStore, audioRoot: Path): Map<String, String> {
        val exporter = DataExporter(store, audioRoot)
        val plan = exporter.plan(ExportRequest(ExportRange.ALL, includeAudio = false, timeZone = tokyo, exportedAt = createdAt)) ?: return emptyMap()
        val buffer = Buffer()
        exporter.write(plan, buffer)
        return readStoredZip(buffer.readByteArray())
            .filterKeys { it.substringAfter('/').startsWith("json/") }
            .mapValues { it.value.decodeToString() }
    }

    fun readFile(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
}
