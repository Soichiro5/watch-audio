package app.watchaudio.app

import app.watchaudio.core.protocol.ChunkEndReason

/**
 * Every user-visible string of the phone UI, in one place so it can be moved to
 * Compose resources / localized later. Japanese only for now.
 */
object Strings {
    // Navigation
    const val TAB_TIMELINE = "タイムライン"
    const val TAB_SEARCH = "検索"
    const val TAB_STATUS = "状態"

    // Day screen
    const val PREV_DAY = "前の日"
    const val NEXT_DAY = "次の日"
    const val TODAY = "今日"
    const val COVERAGE = "録音カバー率"
    const val LEGEND_AUDIO = "音声"
    const val LEGEND_SILENT = "無音"
    const val LEGEND_STOPPED = "停止"
    const val LEGEND_MISSING = "欠落"
    const val LEGEND_NOT_RECORDED = "記録なし"
    const val GAPS = "録音できていない時間"
    const val TRANSCRIPT = "文字起こし"
    const val NO_RECORDING_THIS_DAY = "この日の録音はありません"
    const val NO_TRANSCRIPT_YET = "文字起こしされた発話はまだありません"
    const val RETRY = "再試行"

    fun coverageDetail(covered: String, day: String) = "$covered / $day"
    fun audioAndSilent(audio: String, silent: String) = "音声 $audio ・ 無音 $silent"
    fun pendingChunks(range: String, count: Int) = "文字起こし待ち $range（${count}件）"
    fun transcribingChunks(range: String, count: Int) = "文字起こし中 $range（${count}件）"
    fun failedChunks(range: String, count: Int) = "文字起こし失敗 $range（${count}件）"

    // Gap causes
    fun endReason(reason: ChunkEndReason): String = when (reason) {
        ChunkEndReason.ROTATION -> "継続"
        ChunkEndReason.MARK -> "マーク"
        ChunkEndReason.USER_STOP -> "手動で停止"
        ChunkEndReason.INTERRUPTION -> "割り込み（通話など）"
        ChunkEndReason.CHARGING -> "充電中"
        ChunkEndReason.OFF_BODY -> "非装着"
        ChunkEndReason.LOW_BATTERY -> "電池残量低下"
        ChunkEndReason.LOW_STORAGE -> "ストレージ不足"
        ChunkEndReason.TERMINATED -> "アプリ終了"
        ChunkEndReason.ERROR -> "エラー"
    }
    const val GAP_MISSING = "データ欠落（未受信）"
    const val GAP_AWAITING = "転送待ち"
    const val GAP_NO_RECORDING = "記録なし"

    // Playback
    fun playingFrom(time: String) = "$time から再生中"
    const val STOP = "停止"

    // Search
    const val SEARCH_PLACEHOLDER = "キーワードで検索（例: 会議）"
    const val SEARCH_HINT = "文字起こし全体から部分一致で検索します"
    const val SEARCH_NO_RESULTS = "見つかりませんでした"
    const val CLEAR = "クリア"
    fun searchResultCount(count: Int, limited: Boolean) = if (limited) "${count}件以上" else "${count}件"

    // Status screen
    const val ARRIVAL = "Watch からの受信"
    const val NO_CHUNKS_YET = "まだ録音データを受信していません"
    fun lastReceived(time: String, ago: String) = "最終受信 $time（$ago）"
    fun arrivalLate(minutes: Long) =
        "${minutes}分以上チャンクが届いていません。Watch の録音状態と、スマホとの接続を確認してください。"
    const val ARRIVAL_OK = "録音データを受信中です"
    fun recordingStopped(reason: String) = "Watch の録音は停止しています（理由: $reason）"

    const val ASR_MODEL = "文字起こしモデル"
    const val MODEL_UNKNOWN = "確認中…"
    const val MODEL_MISSING = "モデルが未ダウンロードです。文字起こしにはダウンロードが必要です。"
    fun modelDownloading(percent: Int) = "ダウンロード中… $percent%"
    const val MODEL_READY = "準備完了（端末内で文字起こしします）"
    fun modelFailed(reason: String) = "エラー: $reason"
    const val DOWNLOAD_WIFI = "Wi-Fi でダウンロード"
    const val DOWNLOAD_ANY = "モバイル通信でも許可"
    const val TRANSCRIBING_NOW = "文字起こし中…"
    const val TRANSCRIBE_NOW = "今すぐ文字起こし"

    const val STORAGE = "保存データ"
    const val STAT_CHUNKS = "チャンク"
    const val STAT_SEGMENTS = "発話"
    const val STAT_AUDIO = "音声データ"
    const val STAT_RECORDED = "録音時間"
    const val STAT_PENDING = "文字起こし待ち"
    const val STAT_FAILED = "失敗"

    const val PRIVACY_TITLE = "プライバシー"
    const val PRIVACY_NOTE = "データはこの端末内にのみ保存され、外部に送信されません"

    // Units and formats
    val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日") // ISO order, Monday first
    fun date(year: Int, month: Int, day: Int, weekday: String) = "${year}年${month}月${day}日($weekday)"
    fun dateShort(month: Int, day: Int, weekday: String) = "${month}月${day}日($weekday)"
    fun hours(h: Long) = "${h}時間"
    fun hoursMinutes(h: Long, m: Long) = "${h}時間${m}分"
    fun minutes(m: Long) = "${m}分"
    fun seconds(s: Long) = "${s}秒"
    const val JUST_NOW = "たった今"
    fun ago(duration: String) = "${duration}前"
    fun count(n: Int) = "${n}件"
}
