package app.watchaudio.core.export

import app.watchaudio.core.db.AudioFileInfo
import app.watchaudio.core.db.ChunkRecord
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.MarkRecord
import app.watchaudio.core.db.SegmentRecord
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.SourcePlatform
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A fixed day for the export tests: 2026-10-10 in Tokyo, exported the next morning. */
internal object ExportFixtures {
    val TOKYO = TimeZone.of("Asia/Tokyo")
    val DATE = LocalDate(2026, 10, 10)

    /** 2026-10-10T00:00+09:00. */
    const val DAY_START = 1_791_558_000_000L
    const val MIN = 60_000L
    const val HOUR = 60 * MIN

    /** 2026-10-11T09:00+09:00. */
    const val EXPORTED_AT = DAY_START + 33 * HOUR

    fun at(hour: Int, minute: Int = 0, second: Int = 0) = DAY_START + hour * HOUR + minute * MIN + second * 1_000L

    fun chunk(
        id: String,
        start: Long,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        state: ChunkState = ChunkState.TRANSCRIBED,
        audio: Boolean = true,
        purgedAt: Long? = null,
    ) = ChunkRecord(
        id = id,
        sessionId = "session-1",
        deviceId = "watch-1",
        source = SourcePlatform.WEAROS,
        startedAt = start,
        endedAt = start + 5 * MIN,
        timeZone = "Asia/Tokyo",
        audioPath = if (audio) "2026/10/10/$id.m4a" else null,
        endReason = endReason,
        state = state,
        attempts = if (state == ChunkState.FAILED) 3 else 1,
        lastError = if (state == ChunkState.FAILED) "java.io.IOException: /data/user/0/app/files/audio" else null,
        sizeBytes = if (audio) 1_000 else null,
        audioPurgedAt = purgedAt,
    )

    /** The evening before: the wearer stopped recording, which explains the morning's gap. */
    val previous = chunk("p0", DAY_START - 2 * HOUR, endReason = ChunkEndReason.USER_STOP).copy(audioPath = "2026/10/09/p0.m4a")

    val chunks = listOf(
        chunk("c1", at(7)),
        chunk("c2", at(7, 5), endReason = ChunkEndReason.CHARGING, purgedAt = EXPORTED_AT - HOUR),
        chunk("c3", at(8), state = ChunkState.NO_AUDIO, audio = false),
        chunk("c4", at(12), state = ChunkState.FAILED),
    )

    val records = DayRecords(
        chunks = chunks,
        overlapping = chunks,
        previous = previous,
        segments = listOf(
            SegmentRecord(1, "c1", at(7, 0, 20), at(7, 0, 23), "おはようございます、今日もよろしくお願いします", "ja", "sherpa-onnx"),
            SegmentRecord(2, "c1", at(7, 3), at(7, 3, 4), "会議は *10時* から [資料] #重要", "ja", "sherpa-onnx"),
            SegmentRecord(3, "c2", at(7, 6), at(7, 6, 2), "了解です", null, "sherpa-onnx"),
        ),
        marks = listOf(MarkRecord("c2", at(7, 9, 30)), MarkRecord("c1", at(7, 2))),
        audioFiles = mapOf(
            "c1" to AudioFileInfo(AudioCodec.AAC, "ab12"),
            "c2" to AudioFileInfo(AudioCodec.AAC, "cd34"),
            "c4" to AudioFileInfo(AudioCodec.AAC, "ef56"),
        ),
    )

    /** The day with c1's audio in the archive (c2's was purged, c4's not included). */
    fun day(): ExportDay = buildExportDay(
        DATE,
        TOKYO,
        records,
        now = EXPORTED_AT,
        includedAudio = mapOf("c1" to audioEntryName(DATE, chunks[0], TOKYO, records.audioFiles["c1"])),
    )
}
