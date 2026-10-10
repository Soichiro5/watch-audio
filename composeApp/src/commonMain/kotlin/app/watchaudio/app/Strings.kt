package app.watchaudio.app

import app.watchaudio.core.pipeline.AudioRetention
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
    const val PLAY = "再生"
    const val AUDIO_PURGED_NOTE = "保持期間を過ぎた音声は削除済みです（文字起こしは残っています）"
    const val DELETE_DAY = "この日のデータを削除"
    fun confirmDeleteDay(date: String, count: Int) =
        "$date に始まった録音 ${count}件の音声と文字起こしを、この端末から削除します。元に戻せません。"
    const val DELETE = "削除する"
    const val CANCEL = "キャンセル"
    const val DELETING = "削除中…"

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

    // Gap alert notification (#43)
    const val GAP_ALERT_TITLE = "録音の途絶"
    fun gapAlert(lastReceived: String) = "録音が届いていません（最後の受信: $lastReceived）。Watch を確認してください"
    const val GAP_ALERT_CHANNEL = "録音の途絶アラート"

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
    const val STAT_RECORDED = "録音時間"
    const val STAT_PENDING = "文字起こし待ち"
    const val STAT_FAILED = "失敗"

    // Storage (#33)
    const val STORAGE_USAGE = "ストレージ"
    const val USAGE_AUDIO = "音声"
    const val USAGE_DATABASE = "文字起こし・索引"
    const val USAGE_MODELS = "文字起こしモデル"
    const val USAGE_MODELS_BY_OS = "OS が管理"
    const val USAGE_TOTAL = "合計"
    const val USAGE_FREE = "端末の空き容量"
    fun lowFreeSpace(free: String) =
        "端末の空き容量が残り $free です。録音を保存できなくなる前に、音声の保持期間を短くするか、不要な日のデータを削除してください。"
    fun audioPurgedChunks(count: Int) = "保持期間を過ぎて音声を削除したチャンク ${count}件（文字起こしは残っています）"
    const val RETENTION = "音声の保持期間"
    const val RETENTION_NOTE =
        "文字起こしは常に無期限で保存します。保持期間を過ぎた音声は、文字起こしが終わったものだけ自動で削除します。"
    fun retention(retention: AudioRetention): String = when (retention) {
        AudioRetention.DAYS_7 -> "7日"
        AudioRetention.DAYS_30 -> "30日"
        AudioRetention.DAYS_90 -> "90日"
        AudioRetention.UNLIMITED -> "無期限"
    }
    fun confirmRetention(label: String, count: Int, size: String) =
        "保持期間を「$label」にすると、文字起こし済みの音声 ${count}件（$size）を今すぐ削除し、" +
            "以後も $label を過ぎた音声を自動で削除します。文字起こしは残ります。削除した音声は元に戻せません。"
    const val CHANGE = "変更する"
    fun audioPurged(count: Int, size: String) = "音声 ${count}件（$size）を削除しました"
    const val DELETE_ALL = "すべてのデータを削除"
    const val DELETE_ALL_WORD = "削除"
    const val DELETE_ALL_NOTE =
        "すべての録音・音声・文字起こしをこの端末から削除します（設定は残ります）。元に戻せません。続けるには「削除」と入力してください。"
    const val DELETE_ALL_INPUT = "確認のため「削除」と入力"
    const val DELETE_ALL_CONFIRM = "すべて削除する"
    const val DELETED_ALL = "すべてのデータを削除しました"
    fun storageActionFailed(reason: String) = "削除できませんでした: $reason"

    const val PRIVACY_TITLE = "プライバシー"
    const val PRIVACY_NOTE = "データはこの端末内にのみ保存され、外部に送信されません"

    // Status screen: alert settings (#43)
    const val ALERTS_TITLE = "通知"
    const val GAP_ALERT_SWITCH = "録音が届かないときに通知"
    fun gapAlertSwitchDetail(minutes: Long) = "Watch で録音中のはずなのに${minutes}分以上データが届かないとき"
    const val QUIET_HOURS_SWITCH = "おやすみ時間は通知しない"
    const val QUIET_HOURS_DETAIL = "おやすみ時間中に途絶えた場合は、終了時刻に通知します"
    const val QUIET_HOURS_FROM = "開始"
    const val QUIET_HOURS_TO = "終了"
    const val GUIDE_TITLE = "はじめに"
    const val SHOW_ONBOARDING_AGAIN = "初回の説明をもう一度見る"

    // Onboarding (#32)
    fun onboardingProgress(current: Int, total: Int) = "$current / $total"
    const val ONBOARDING_BACK = "戻る"
    const val ONBOARDING_NEXT = "次へ"
    const val ONBOARDING_SKIP = "後で設定する"
    const val ONBOARDING_START = "はじめる"

    const val WELCOME_TITLE = "watch-audio へようこそ"
    val WELCOME_POINTS = listOf(
        "Watch を着けている間、周りの会話を録音し続けます。",
        "録音は数分ごとにこのスマホへ届き、スマホの中で文字起こしされます。",
        "あとからタイムラインで一日を振り返ったり、キーワードで発言を探したりできます。",
    )
    const val WELCOME_PRIVACY_DETAIL =
        "録音・文字起こしはどこにもアップロードせず、バックアップにも含めません。ネットワークは文字起こしモデルのダウンロードにだけ使います。"

    const val CONSENT_TITLE = "録音する前に"
    val CONSENT_POINTS = listOf(
        "このアプリは常に録音します。あなたの声だけでなく、周りの人の声も録音されます。",
        "国・地域によっては、会話を録音するときに相手の同意が必要な場合があります。録音していることを周りの人に伝え、必要に応じて同意を得てください。",
        "会議・医療機関・職場など、録音が禁止・制限されている場所や場面では、Watch で録音を止めてください。",
    )
    const val CONSENT_DISCLAIMER = "この説明は一般的な注意であり、法的助言ではありません。お住まいの地域の法令や所属先のルールを確認してください。"
    const val CONSENT_CHECKBOX = "内容を理解し、周りの人に配慮して録音します"

    const val PERMISSIONS_TITLE = "許可の設定"
    const val PERMISSIONS_INTRO = "あとから端末の設定アプリでも変更できます。"
    fun permissionName(permission: AppPermission) = when (permission) {
        AppPermission.NOTIFICATIONS -> "通知"
        AppPermission.SPEECH_RECOGNITION -> "音声認識"
    }
    fun permissionReason(permission: AppPermission) = when (permission) {
        AppPermission.NOTIFICATIONS -> "録音データが届かなくなったときや、モデルのダウンロード状況をお知らせします。"
        AppPermission.SPEECH_RECOGNITION -> "録音をこの端末の中で文字起こしするために使います。音声は外部に送信されません。"
    }
    const val PERMISSION_ALLOW = "許可する"
    const val PERMISSION_GRANTED = "許可済み"
    const val PERMISSION_DENIED = "許可されていません。端末の設定アプリから変更できます。"
    const val PERMISSION_CHECKING = "確認中…"

    const val MODEL_SETUP_TITLE = "文字起こしの準備"
    const val MODEL_SETUP_DOWNLOAD =
        "日本語の音声認識モデル（数百 MB）をダウンロードします。一度ダウンロードすれば、文字起こしはオフラインで端末内だけで行います。"
    const val MODEL_SETUP_DOWNLOAD_HINT = "Wi-Fi を選ぶと、Wi-Fi に接続されたときに自動でダウンロードします。"
    const val MODEL_SETUP_OS =
        "iPhone の日本語音声認識モデルを使います。入っていない場合は iOS がダウンロードします（文字起こしは端末内で行います）。"
    const val MODEL_PREPARE = "モデルを準備"

    const val WATCH_TITLE = "Watch で録音を開始"
    const val WATCH_INSTRUCTION = "Watch アプリを開いて録音を開始してください"
    val WATCH_POINTS = listOf(
        "録音データは自動でこのスマホに届きます。「状態」タブで受信状況を確認できます。",
        "充電中・Watch を外している間・通話中などは録音が止まります。止まった時間と理由はタイムラインに表示されます。",
        "録音中のはずなのにデータが届かないときは、スマホに通知します。",
    )

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
