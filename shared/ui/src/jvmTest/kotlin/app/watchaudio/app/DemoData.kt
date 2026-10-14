package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.timeline.dayWindow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A plausible recorded day for UI tests and screenshots. */
internal object DemoData {
    private const val MIN = 60_000L
    private const val CHUNK = 5 * MIN

    /** `lastError` of the demo's failed chunks. */
    const val DEMO_FAILURE = "java.io.IOException: Failed to decode audio (MediaCodec error 0x80001001)"

    private val lines = listOf(
        "おはようございます、今日もよろしくお願いします",
        "明日の会議は十時からです",
        "資料は午後までに共有しておきます",
        "歯医者の予約を来週に変更した",
        "駅前のカフェで少し休憩しよう",
        "今週末は雨の予報らしいよ",
    )

    /** Records [day] (Tokyo time) from 7:00 to 12:00 and from 13:00 until [until]. */
    suspend fun seed(store: LocalStore, day: LocalDate, timeZone: TimeZone, until: Long) {
        val start = dayWindow(day, timeZone).start
        var n = 0
        suspend fun record(from: Long, to: Long, lastReason: ChunkEndReason) {
            var t = from
            while (t < to) {
                val id = "c${n++}"
                val end = minOf(t + CHUNK, to)
                val silent = n % 4 == 0
                val reason = if (end >= to) lastReason else ChunkEndReason.ROTATION
                store.transaction {
                    insertChunk(manifest(id, t, end - t, silent, reason, timeZone), if (silent) null else "$id.m4a", end + MIN)
                }
                val state = n % 7
                if (!silent && t < until - 30 * MIN) {
                    store.transaction {
                        markTranscribing(id)
                        if (state == 3) {
                            // A raw exception message, as the pipeline stores it: the timeline must not show it (#144).
                            markFailed(id, DEMO_FAILURE)
                        } else {
                            saveTranscript(
                                id,
                                listOf(NewSegment(t + 20_000, t + 25_000, lines[(n - 1) % lines.size], "ja")),
                                "demo",
                            )
                        }
                    }
                }
                t = end
            }
        }
        record(start + 7 * 60 * MIN, start + 12 * 60 * MIN, ChunkEndReason.CHARGING)
        record(start + 13 * 60 * MIN, until, ChunkEndReason.ROTATION)
    }

    private val talk = listOf(
        "明日の会議は十時からなので、資料は今日中に共有します",
        "予算の見積もりは先週より少し増えそうだと聞きました",
        "来週の水曜日に、もう一度みんなで集まって決めましょう",
        "駅前のカフェはお昼の時間がとても混んでいるらしいです",
        "週末は雨の予報なので、出かけるのは日曜日にしようか",
    )

    /**
     * A talking hour for the hour summaries (#27): chunk `t<hour>` at [hour]:00 (Tokyo time) with
     * [utterances] utterances 20 s apart, its audio at `t<hour>.m4a`; [marks] are offsets in it.
     */
    suspend fun seedTalk(store: LocalStore, day: LocalDate, timeZone: TimeZone, hour: Int, utterances: Int = 10, transcribed: Boolean = true, marks: List<Long> = emptyList()) {
        val start = dayWindow(day, timeZone).start + hour * 60 * MIN
        val id = "t$hour"
        store.transaction {
            insertChunk(manifest(id, start, CHUNK, false, ChunkEndReason.ROTATION, timeZone).copy(markOffsetsMs = marks), "$id.m4a", start + CHUNK)
            if (transcribed) {
                markTranscribing(id)
                saveTranscript(id, (0 until utterances).map { i -> NewSegment(start + i * 20_000L, start + i * 20_000L + 4_000, talk[i % talk.size], "ja") }, "demo")
            }
        }
    }

    private fun manifest(id: String, start: Long, duration: Long, silent: Boolean, reason: ChunkEndReason, timeZone: TimeZone) =
        ChunkManifest(
            chunkId = id,
            sessionId = "demo",
            deviceId = "watch",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = start,
            durationMs = duration,
            timeZone = timeZone.id,
            audio = if (silent) null else AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 900_000, "00"),
            endReason = reason,
        )
}
