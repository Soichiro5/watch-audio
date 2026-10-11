package app.watchaudio.core.db

import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.util.UUID
import kotlin.random.Random

/**
 * Deterministic everyday Japanese speech for [SearchBenchmark]: 5-minute chunks over the worn
 * hours of consecutive days, built from work, family and errand vocabulary with fillers.
 * Chunk ids are UUIDs and the engine id has the real length, so rows are as large as the app's.
 */
internal class TranscriptGenerator(seed: Int, private val profile: Profile) {
    private val random = Random(seed)

    /** How speech is cut into stored segments. */
    enum class Profile {
        /**
         * One row per utterance, as the pipeline stores them since #68 (PR #70) splits at
         * turn-taking pauses (#72): 1-2 s per segment (median about 1.7 s), about 12 characters,
         * conversations packed densely into some chunks and quiet chunks in between.
         * About 8,600 segments per 16-hour day.
         */
        UTTERANCES,

        /** The #23 assumption: ~10 segments of 1-3 clauses (~30 characters) per chunk. */
        SENTENCES,
    }

    data class Loaded(
        val chunks: Int,
        val segments: Int,
        val days: Int,
        val textChars: Long,
        val medianSegmentMs: Long,
    )

    suspend fun populate(store: LocalStore, targetSegments: Int): Loaded {
        val start = 1_760_054_400_000L // 2025-10-10T00:00:00Z (09:00 JST)
        val chunksPerDay = if (profile == Profile.UTTERANCES) WORN_CHUNKS_PER_DAY else 137
        var chunks = 0
        var segments = 0
        var chars = 0L
        val durations = IntArray(1_000) // histogram in 100 ms steps
        var at = start
        var session = uuid()
        while (segments < targetSegments) {
            store.transaction {
                repeat(CHUNKS_PER_TRANSACTION) {
                    if (segments >= targetSegments) return@repeat
                    val id = uuid()
                    if (chunks % 100 == 0) session = uuid()
                    val manifest = ChunkManifest(
                        chunkId = id,
                        sessionId = session,
                        deviceId = "watch",
                        source = SourcePlatform.WEAROS,
                        startedAtEpochMs = at,
                        durationMs = CHUNK_MS,
                        timeZone = "Asia/Tokyo",
                        audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 900_000, "00"),
                        endReason = ChunkEndReason.ROTATION,
                    )
                    insertChunk(manifest, "$id.m4a", at + CHUNK_MS)
                    val chunkSegments = when (profile) {
                        Profile.UTTERANCES -> utterances(at)
                        Profile.SENTENCES -> sentences(at)
                    }
                    saveTranscript(id, chunkSegments, ENGINE)
                    chunkSegments.forEach { segment ->
                        chars += segment.text.length
                        durations[((segment.endAt - segment.startAt) / 100).toInt().coerceIn(0, durations.lastIndex)]++
                    }
                    segments += chunkSegments.size
                    chunks++
                    // Worn hours of one day, then skip the night.
                    at += if (chunks % chunksPerDay == 0) CHUNK_MS + (24 * 3_600_000L - chunksPerDay * CHUNK_MS) else CHUNK_MS
                }
            }
        }
        return Loaded(
            chunks = chunks,
            segments = segments,
            days = (chunks + chunksPerDay - 1) / chunksPerDay,
            textChars = chars,
            medianSegmentMs = medianOf(durations, segments) * 100L + 50,
        )
    }

    private fun medianOf(histogram: IntArray, total: Int): Int {
        var seen = 0
        histogram.forEachIndexed { bucket, count ->
            seen += count
            if (seen * 2 >= total) return bucket
        }
        return histogram.lastIndex
    }

    /**
     * A conversation chunk (42%) holds 70-120 utterances back to back with turn-taking pauses;
     * the other chunks hold a few stray utterances (TV, a word to oneself).
     */
    private fun utterances(chunkStart: Long): List<NewSegment> {
        val count = if (random.nextInt(100) < 42) 70 + random.nextInt(51) else random.nextInt(13)
        val gapMax = if (count > 20) 1_200 else 20_000
        var at = chunkStart + random.nextInt(2_000)
        return buildList {
            repeat(count) {
                val text = utterance()
                val duration = 450L + 100L * text.length + random.nextInt(200)
                if (at + duration > chunkStart + CHUNK_MS) return@buildList
                add(NewSegment(at, at + duration, text, "ja"))
                at += duration + 150 + random.nextInt(gapMax)
            }
        }
    }

    private fun sentences(chunkStart: Long): List<NewSegment> {
        val count = 6 + random.nextInt(9) // 6..14, ~10 on average
        val slot = CHUNK_MS / count
        return List(count) { i ->
            val begin = chunkStart + i * slot + random.nextLong(slot / 4)
            NewSegment(begin, begin + slot / 2 + random.nextLong(slot / 4), sentence(), "ja")
        }
    }

    /** One utterance between turn-taking pauses: a backchannel or a single short clause. */
    private fun utterance(): String = buildString {
        if (random.nextInt(100) < 25) {
            append(REPLIES.pick())
            return@buildString
        }
        if (random.nextInt(5) == 0) append(FILLERS.pick()).append('、')
        when (random.nextInt(10)) {
            0 -> append("案件番号").append(1_000 + random.nextInt(9_000)).append("の").append(topic())
            1, 2, 3 -> append(PEOPLE.pick()).append("が").append(topic())
            4, 5, 6 -> append(TIMES.pick()).append("の").append(topic())
            else -> append(topic())
        }
        append(if (random.nextBoolean()) SHORT_PREDICATES.pick() else PREDICATES.pick())
        append(UTTERANCE_ENDINGS.pick())
    }

    private fun sentence(): String = buildString {
        if (random.nextInt(4) == 0) append(FILLERS.pick()).append('、')
        repeat(1 + random.nextInt(3)) { clause ->
            if (clause > 0) append('、')
            when (random.nextInt(10)) {
                0 -> append(TIMES.pick()).append(PEOPLE.pick()).append("と").append(topic()).append(PREDICATES.pick())
                1 -> append("案件番号").append(1_000 + random.nextInt(9_000)).append("の").append(topic()).append(PREDICATES.pick())
                2, 3 -> append(PEOPLE.pick()).append("が").append(topic()).append(PREDICATES.pick())
                else -> append(TIMES.pick()).append("の").append(topic()).append(PREDICATES.pick())
            }
        }
        append(ENDINGS.pick())
    }

    /** Mostly common topics; "鰻丼" is rare (about 1 in 5,000 clauses). */
    private fun topic(): String = if (random.nextInt(5_000) == 0) "鰻丼" else TOPICS.pick()

    private fun uuid(): String = UUID(random.nextLong(), random.nextLong()).toString()

    private fun <T> List<T>.pick(): T = this[random.nextInt(size)]

    private companion object {
        const val CHUNK_MS = 300_000L
        const val CHUNKS_PER_TRANSACTION = 500

        /** 16 worn hours a day (docs/spikes/m0-5-asr-desktop.md §5). */
        const val WORN_CHUNKS_PER_DAY = 192

        /** Same length as the Android engine id (`AsrSettings.JA_ENGINE_ID`). */
        const val ENGINE = "sherpa-zipformer-ja-reazonspeech-int8"

        val REPLIES = listOf(
            "はい", "うん", "ええ", "へえ", "うーん", "そうですね", "なるほど", "えっと", "お願いします", "わかりました",
            "ありがとうございます", "そうなんだ", "了解です", "ほんとに？", "大丈夫です", "いいですね", "すみません",
            "そうそう", "それはいいね", "どうしようかな",
        )
        val FILLERS = listOf("えーと", "あの", "そうですね", "なるほど", "うん", "まあ", "ちなみに", "そういえば")
        val TIMES = listOf(
            "今日", "明日", "昨日", "来週", "先週", "午後", "朝", "夕方", "今週末", "月曜", "金曜", "来月", "さっき", "後で",
        )
        val PEOPLE = listOf(
            "田中さん", "佐藤さん", "鈴木さん", "高橋さん", "部長", "課長", "お客さん", "妻", "子供", "母", "先生", "チーム",
        )
        val TOPICS = listOf(
            "会議", "会議の資料", "打ち合わせ", "ミーティング", "定例ミーティング", "見積もり", "企画書", "予算", "契約",
            "請求書", "締め切り", "プレゼン", "出張", "新幹線", "ホテルの予約", "歯医者の予約", "美容院の予約", "病院",
            "薬", "保育園のお迎え", "宿題", "夕飯", "買い物", "洗濯", "掃除", "ゴミ出し", "電車", "電車の遅延", "天気",
            "雨", "傘", "銀行の振り込み", "誕生日のプレゼント", "旅行の計画", "映画", "ラーメン", "カレー", "散歩", "ジム",
            "引っ越し", "車の点検", "保険", "税金", "年末調整", "健康診断", "ランチ", "コーヒー", "新しいプロジェクト",
            "リリース", "バグの修正", "レビュー", "面接", "研修", "飲み会", "週報",
        )
        val PREDICATES = listOf(
            "について話しました", "を確認しておいてください", "の件ですが", "はどうなりましたか", "を忘れないでね",
            "が遅れています", "に間に合いそうです", "を来週に変更しました", "は十時からです", "をお願いできますか",
            "はもう終わりました", "を準備しないといけない", "の連絡が来ていました", "で少し相談があります",
            "はキャンセルになりました", "を送っておきます",
        )
        val SHORT_PREDICATES = listOf(
            "です", "ですか", "は？", "どうする", "かな", "は十時から", "は終わった", "を送ります", "が遅れてる",
            "の件で", "お願い", "を確認して", "は明日", "まだ？", "大丈夫", "決まった",
        )
        val ENDINGS = listOf("", "", "。", "。", "ね", "よ", "かな")
        val UTTERANCE_ENDINGS = listOf("", "", "", "。", "ね", "よ")
    }
}
