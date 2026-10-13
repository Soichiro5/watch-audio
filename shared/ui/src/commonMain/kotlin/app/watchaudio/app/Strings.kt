package app.watchaudio.app

import app.watchaudio.app.licenses.NoticeApp
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.pipeline.AudioRetention
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.OffloadAge
import app.watchaudio.core.pipeline.PauseReason
import app.watchaudio.core.pipeline.StatusNotice
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.search.SemanticStatus

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
    /** Instead of the play button of an utterance whose audio was offloaded (#156). */
    const val AUDIO_IN_BACKUP = "バックアップにあり"
    fun audioInBackupNote(backupDate: String) =
        "「バックアップにあり」の音声は、この端末から外して暗号化バックアップ（$backupDate 作成）に入っています。" +
            "そのバックアップから戻すと再生できます（文字起こしは残っています）。"
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

    /** Under a failed row instead of the exception message, which stays in the DB and the diagnostics log (#144). */
    const val FAILED_CHUNKS_NOTE = "この時間の音声を文字起こしできませんでした。原因は診断ログに記録されています。"

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

    // Search by meaning (#24)
    const val SEARCH_PLACEHOLDER_SEMANTIC = "言葉や内容で検索（例: 先週話した旅行の件）"
    const val SEARCH_HINT_SEMANTIC = "文字起こし全体から、言葉が一致する発話と、意味が近い会話を探します"
    const val MATCH_BY_MEANING = "意味が近い"

    /** What the search screen says about searching by meaning; null once it covers everything. */
    fun semanticStatus(status: SemanticStatus): String? = when (status) {
        SemanticStatus.Ready -> null
        is SemanticStatus.Indexing ->
            "意味で探す準備中 ${(status.fraction * 100).toInt().coerceIn(0, 99)}%（準備できた会話から探します。古い会話は充電中に準備します）"
        is SemanticStatus.WaitingForModel -> status.downloading?.let { "意味で探すためのモデルをダウンロード中 ${(it * 100).toInt()}%（今は言葉の一致だけで探します）" }
            ?: "意味で探すためのモデルは Wi-Fi に接続したときにダウンロードします（今は言葉の一致だけで探します）"
    }

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
    /** No raw reason: it may be an English exception message (#135); the diagnostics log has it. */
    fun modelFailed(setup: ModelSetup) = when (setup) {
        ModelSetup.DOWNLOAD -> "文字起こしモデルをダウンロードできませんでした。通信状況を確認して、もう一度ダウンロードしてください。"
        ModelSetup.OS_MANAGED -> "文字起こしモデルを準備できませんでした。「モデルを準備」でもう一度試してください。"
    }
    const val DOWNLOAD_WIFI = "Wi-Fi でダウンロード"
    const val DOWNLOAD_ANY = "モバイル通信でも許可"
    const val TRANSCRIBING_NOW = "文字起こし中…"
    const val TRANSCRIBE_NOW = "今すぐ文字起こし"

    /** The status card's note about the background work (#135). */
    fun statusNotice(notice: StatusNotice): String = when (notice) {
        StatusNotice.ModelWaitingForWifi -> "Wi-Fi に接続されたときに、文字起こしモデルを自動でダウンロードします"
        StatusNotice.IngestFailed -> "Watch から届いた録音を保存できませんでした。Watch が自動で送り直します"
        StatusNotice.GapAlertScheduleFailed -> "録音の途絶通知を予約できませんでした。次に録音が届いたときに予約し直します"
        is StatusNotice.TranscriptionFailed ->
            "${notice.count}件のチャンクを文字起こしできませんでした。タイムラインから再試行できます"
        StatusNotice.RetentionFailed -> "保持期間を過ぎた音声を削除できませんでした。次の文字起こしの後にもう一度試します"
    }

    // Model download notification (Android foreground work)
    const val MODEL_DOWNLOAD_CHANNEL = "文字起こしモデルのダウンロード"
    const val MODEL_DOWNLOAD_NOTIFICATION_TITLE = "文字起こしモデルをダウンロード中"
    const val ENGLISH_MODEL_DOWNLOAD_NOTIFICATION_TITLE = "英語の文字起こしモデルをダウンロード中"
    const val EMBEDDING_MODEL_DOWNLOAD_NOTIFICATION_TITLE = "意味で探すためのモデルをダウンロード中"

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

    // Offloading backed-up audio (#156)
    const val OFFLOAD_TITLE = "バックアップ済みの音声"
    const val OFFLOAD_NO_BACKUP =
        "「暗号化バックアップ」で音声も含めてバックアップすると、バックアップに入れた音声をこの端末から外して空き容量を増やせます。"
    const val OFFLOADABLE_AUDIO = "端末から外せる音声"
    fun offloadableAudio(count: Int, size: String) = "$size（${count}件）"
    fun lastAudioBackup(date: String) = "音声を含む最後のバックアップ: $date"
    fun audioInBackupChunks(count: Int) = "端末から外し、バックアップにだけある音声 ${count}件（文字起こしは残っています）"
    const val OFFLOAD_NOTE =
        "バックアップ済みの音声だけを端末から外します。バックアップしていない音声と、文字起こしがまだの音声は外しません。" +
            "外した音声はタイムラインで「バックアップにあり」と表示され、文字起こしと検索はそのまま使えます。"
    const val OFFLOAD_BUTTON = "バックアップ済みの音声を端末から外す"
    const val OFFLOAD_CONFIRM = "端末から外す"
    fun offloadAge(age: OffloadAge): String = when (age) {
        OffloadAge.ALL -> "すべて"
        OffloadAge.DAYS_7 -> "7日より前"
        OffloadAge.DAYS_30 -> "30日より前"
    }
    fun confirmOffload(count: Int, size: String, backupDates: String) =
        "暗号化バックアップ（$backupDates 作成）に入っている音声 ${count}件（$size）を、この端末から削除します。" +
            "文字起こしは残ります。音声を再び聞くには、そのバックアップファイルとパスフレーズが必要です。" +
            "アプリはバックアップファイルがまだあるかを確かめられないので、ファイルを消していないことを確認してから進めてください。"
    const val OFFLOAD_NOTHING = "この条件で外せる音声はありません"
    fun audioOffloaded(count: Int, size: String) = "音声 ${count}件（$size）を端末から外しました"
    const val OFFLOADING = "端末から外しています…"
    /** No raw reason (#135); the diagnostics log has the error type. */
    const val OFFLOAD_FAILED = "音声を端末から外せませんでした。もう一度試してください。"
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
        "すべての録音・音声・文字起こし、診断ログとエージェント連携のアクセス履歴をこの端末から削除します（設定と PC のペアリングは残ります）。" +
            "元に戻せません。続けるには「削除」と入力してください。"
    const val DELETE_ALL_INPUT = "確認のため「削除」と入力"
    const val DELETE_ALL_CONFIRM = "すべて削除する"
    const val DELETED_ALL = "すべてのデータを削除しました"
    const val STORAGE_ACTION_FAILED = "削除できませんでした。もう一度試してください。"

    const val PRIVACY_TITLE = "プライバシー"
    const val PRIVACY_NOTE = "データはこの端末内にのみ保存され、外部に送信されません"
    /** Added below [PRIVACY_NOTE] where agent access exists (#184). */
    const val PRIVACY_NOTE_AGENT =
        "例外は「エージェント連携」を ON にしている間だけで、ペアリングした PC のエージェントが読んだ分が、同じネットワーク内のその PC に直接渡ります。"

    // Agent access (UC11, #184)
    const val AGENT_TITLE = "エージェント連携"
    const val AGENT_CARD_NOTE =
        "PC の AI エージェント（Claude など）から、ペアリングした PC だけが、ON にしている間だけ記録を読めます。"
    const val AGENT_OPEN = "エージェント連携を開く"
    const val AGENT_BACK = "戻る"
    const val AGENT_INTRO =
        "PC の AI エージェント（Claude Desktop・Claude Code、ローカル LLM のクライアントなど）が、このスマホの文字起こしを道具として読めるようにします。" +
            "データはスマホに置いたまま、同じネットワーク内の PC に直接つなぎます（中継サーバーはありません）。PC には watch-audio-mcp を入れておきます。"
    const val AGENT_CLOUD_NOTE =
        "クラウドの LLM で動くエージェントに渡した分は、その LLM の提供元に届きます。どこにも出したくない場合はローカル LLM のクライアントを使ってください。"
    const val AGENT_SWITCH_SECTION = "接続"
    const val AGENT_SWITCH = "エージェント接続"
    const val AGENT_SWITCH_DETAIL_OFF = "ON の間だけ、ペアリングした PC から読めます"
    fun agentOnUntil(time: String) = "$time に自動で OFF になります"
    const val AGENT_DURATION = "自動で OFF にするまで"
    fun agentMinutes(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60}時間" else "${minutes}分"
    const val AGENT_NO_PAIRINGS = "まだ PC をペアリングしていません。下の「PC をペアリング」から始めてください。"
    const val AGENT_LINK_CONNECTED = "接続中"
    const val AGENT_LINK_SEARCHING = "PC を探しています（PC でエージェントが動いていて、同じネットワークにいるか確認してください）"
    const val AGENT_PERMISSION_DENIED =
        "ローカル ネットワークへのアクセスが許可されていないため、PC につなげません。アプリの設定で「付近のデバイス」を許可してください。"

    const val AGENT_PAIR_TITLE = "PC をペアリング"
    const val AGENT_PAIR_HOWTO =
        "PC のターミナルで npx watch-audio-mcp pair を実行し、表示された QR コードを読み取ります（有効 5 分・使い捨て）。"
    const val AGENT_SCAN = "QR コードを撮影して読み取る"
    const val AGENT_SCAN_NOTE = "カメラのアプリで QR コードを撮影します。写真は読み取ったらすぐに削除します。"
    const val AGENT_ENTER = "文字列を入力"
    const val AGENT_ENTER_LABEL = "ターミナルに出た文字列（watchaudio-mcp://pair?…）"
    const val AGENT_ENTER_SUBMIT = "つなぐ"
    const val AGENT_CANCEL = "やめる"
    const val AGENT_CLOSE = "閉じる"
    const val AGENT_SCANNING = "QR コードを読み取っています…"
    fun agentConnecting(pc: String) = "「$pc」につないでいます…"
    fun agentConfirmTitle(pc: String) = "「$pc」とのペアリングの確認コード"
    const val AGENT_CONFIRM_NOTE =
        "PC のターミナルに同じ 6 桁の確認コードが出ていれば「一致する」を押し、PC でも y を入力してください。" +
            "違う場合は「一致しない」を押してください（同じネットワークの誰かが横取りしようとしている可能性があります）。"
    const val AGENT_CODE_MATCHES = "一致する"
    const val AGENT_CODE_DIFFERS = "一致しない"
    const val AGENT_WAITING_PC = "PC での確定を待っています…"
    fun agentPaired(pc: String) =
        "「$pc」とペアリングしました。PC のエージェントの MCP 設定に watch-audio-mcp を追加し、エージェント接続を ON にすると使えます。"
    const val AGENT_SCAN_NOT_FOUND = "写真から QR コードを読み取れませんでした。もう一度撮影するか、文字列を入力してください。"
    const val AGENT_SCAN_UNAVAILABLE = "カメラのアプリが見つかりません。ターミナルに出た文字列を入力してください。"
    const val AGENT_BAD_CODE = "PC のペアリング用の QR コード（watchaudio-mcp://pair?…）ではありません。"
    const val AGENT_EXPIRED = "ペアリング用の値の有効期限（5 分）が切れています。PC で pair をもう一度実行してください。"
    const val AGENT_UNREACHABLE =
        "PC につながりませんでした。PC とスマホが同じ Wi-Fi（またはスマホのテザリング）にいるか、" +
            "PC のファイアウォールで node の受信を許可しているかを確認してください。"
    const val AGENT_PAIR_DECLINED = "ペアリングを中止しました。"
    const val AGENT_PAIR_REJECTED_BY_PC = "PC 側で中止されたため、ペアリングできませんでした。確認コードが一致していたか確かめ、もう一度試してください。"
    const val AGENT_PAIR_USED = "このペアリング用の値は使用済みか無効です。PC で pair をもう一度実行してください。"
    const val AGENT_PAIR_FAILED = "ペアリングできませんでした。PC で pair をもう一度実行してから試してください。"

    const val AGENT_PAIRINGS = "ペアリングした PC"
    fun agentPairedAt(date: String) = "$date にペアリング"
    const val AGENT_SCOPE_PERIOD = "読める期間"
    fun agentScopeDays(days: Int) = "直近 ${days} 日"
    const val AGENT_ALLOW_AUDIO = "音声も読ませる"
    const val AGENT_ALLOW_AUDIO_DETAIL = "OFF の間は文字起こしのテキストだけ。ON にすると発話の音声（1 回 30 秒まで）も渡します。"
    const val AGENT_REVOKE = "ペアリングを取り消す"
    fun agentConfirmRevoke(pc: String) =
        "「$pc」のペアリングを取り消しますか？ すぐにつながらなくなります。また使うにはペアリングからやり直します。"
    const val AGENT_REVOKE_CONFIRM = "取り消す"

    const val AGENT_HISTORY = "アクセス履歴"
    fun agentHistoryNote(days: Int) =
        "いつ・どの PC が・何を読んだか（検索語を含む）の記録です。端末の外には出さず、${days} 日より前のものは自動で消えます。"
    const val AGENT_HISTORY_EMPTY = "まだアクセスはありません"
    fun agentTool(tool: String) = when (tool) {
        "search_transcripts" -> "検索"
        "get_day_timeline" -> "日のタイムライン"
        "get_context" -> "前後の発話"
        "get_audio_clip" -> "音声"
        else -> tool
    }
    fun agentOutcome(outcome: AgentAccessOutcome): String? = when (outcome) {
        AgentAccessOutcome.OK -> null
        AgentAccessOutcome.DENIED -> "範囲外のため拒否"
        AgentAccessOutcome.RATE_LIMITED -> "回数の上限で拒否"
        AgentAccessOutcome.REVOKED -> "取り消し済みのため拒否"
        AgentAccessOutcome.ERROR -> "エラー"
    }
    fun agentItems(count: Int) = "${count}件"

    // Agent access notification (Android foreground service)
    const val AGENT_NOTIFICATION_CHANNEL = "エージェント接続"
    const val AGENT_NOTIFICATION_TITLE = "エージェント接続: ON"
    fun agentNotificationText(connected: List<String>, offAt: String) =
        (if (connected.isEmpty()) "PC を探しています" else "${connected.joinToString("、")} に接続中") + " · $offAt に自動で OFF"
    const val AGENT_TURN_OFF = "OFF にする"

    // Diagnostics log (#77)
    const val DIAGNOSTICS_TITLE = "診断ログ"
    const val DIAGNOSTICS_NOTE =
        "不具合の調査用に、受信・文字起こし・モデルのダウンロード・異常終了などの記録（時刻、ID、処理時間、エラーの種類）を" +
            "この端末内に保存しています。文字起こしの本文や音声は含みません。自動では送信されず、書き出すと共有先を選べます。"
    const val EXPORT_DIAGNOSTICS = "診断ログを書き出す"
    const val DIAGNOSTICS_EMPTY = "書き出せる診断ログがありません"
    const val DIAGNOSTICS_EXPORT_FAILED = "診断ログを書き出せませんでした。もう一度試してください。"

    // Data export (#155)
    const val EXPORT_TITLE = "データの書き出し"
    const val EXPORT_NOTE =
        "文字起こし・マーク・録音できていない時間を、日ごとの Markdown（Obsidian などで読める形）と JSON にまとめた " +
            "ZIP ファイルにします。保存先はあなたが選びます。アプリが自動でどこかへ送ることはありません。"
    const val EXPORT_RANGE_ALL = "すべて"
    const val EXPORT_RANGE_DATES = "期間を指定"
    fun exportFrom(date: String) = "開始日 $date"
    fun exportTo(date: String) = "終了日 $date"
    const val EXPORT_INCLUDE_AUDIO = "音声ファイルを含める"
    fun exportIncludeAudioDetail(size: String) = "端末にある音声は全部で $size です。含めると ZIP ファイルが大きくなります。"
    const val EXPORT_BUTTON = "書き出す"
    const val EXPORTING = "書き出し中…"
    const val EXPORT_NOTHING = "この期間に書き出せる記録はありません"
    /** No raw reason (#135): it may be an English exception message; the diagnostics log has the type. */
    const val EXPORT_FAILED = "書き出せませんでした。保存先の空き容量を確認して、もう一度試してください。"
    fun exported(days: Int, segments: Int, audioFiles: Int) =
        "${days}日分を書き出しました（発話 ${segments}件" + (if (audioFiles > 0) "、音声 ${audioFiles}件" else "") + "）"
    fun exportMissingAudio(count: Int) = "音声ファイルが見つからなかった ${count}件は、文字起こしだけを書き出しました"
    const val DATE_PICKER_CONFIRM = "決定"

    // Encrypted backup (#40)
    const val BACKUP_TITLE = "暗号化バックアップ"
    const val BACKUP_NOTE =
        "機種変更や故障に備えて、記録をパスフレーズで暗号化したファイルにします。最初はすべてを、その後は前回からの差分だけを書き出せます。" +
            "保存先（PC・外部ストレージなど）はあなたが選びます。アプリが自動でクラウドなどへ送ることはありません。" +
            "このファイルから別の端末（iPhone と Android の間も）へ戻せます。"
    const val BACKUP_PASSPHRASE = "パスフレーズ"
    const val BACKUP_PASSPHRASE_CONFIRM = "パスフレーズ（確認）"
    const val BACKUP_PASSPHRASE_MISMATCH = "確認のパスフレーズが一致しません"
    const val BACKUP_FORGOTTEN_WARNING =
        "パスフレーズを忘れると、このバックアップは誰にも（開発者にも）戻せません。パスフレーズはどこにも保存されないので、" +
            "紙やパスワード管理アプリなど別の場所に控えてください。"
    const val PASSPHRASE_TOO_SHORT = "8文字以上にしてください"
    const val PASSPHRASE_WEAK = "強さ: 弱い（長くするか、文字の種類を増やしてください）"
    const val PASSPHRASE_FAIR = "強さ: ふつう"
    const val PASSPHRASE_STRONG = "強さ: 強い"
    const val BACKUP_INCLUDE_AUDIO = "音声もバックアップする"
    fun backupIncludeAudioDetail(size: String) =
        "端末にある音声は全部で $size です。含めないと、戻した端末では文字起こしだけになり、音声は再生できません。"
    // Diff backups (#164)
    const val BACKUP_NONE_SAVED = "まだバックアップを保存していません"
    fun lastBackupFull(date: String) = "前回のバックアップ: $date（全体）"
    fun lastBackupDiff(date: String, fullDate: String, diffs: Int) =
        "前回のバックアップ: $date（差分。$fullDate の全体のバックアップの後の ${diffs}個目）"
    const val BACKUP_KIND_FULL = "全体"
    const val BACKUP_KIND_DIFF = "差分"
    fun backupFullDetail(chunks: Int, size: String) = "すべての記録（チャンク ${chunks}件、約 $size）を 1 つのファイルにします。"
    fun backupDiffDetail(chunks: Int, audioFiles: Int, size: String) =
        "前回のバックアップの後に増えた・変わった記録だけ（チャンク ${chunks}件" + (if (audioFiles > 0) "、音声 ${audioFiles}件" else "") +
            "、約 $size）を書き出します。"
    const val BACKUP_DIFF_KEEP_FILES =
        "戻すときは、全体のバックアップとその後の差分のファイルをすべて戻してください（順番は問いません）。" +
            "差分には、保存済みのバックアップに入っている音声は入らないので、前のファイルも消さずに残してください。"
    const val BACKUP_DIFF_NOT_YET = "差分は、全体のバックアップを保存した後に作れます。"
    const val BACKUP_DIFF_AFTER_DELETE =
        "前回の全体のバックアップの後に記録を削除したので、次は全体のバックアップを作ってください" +
            "（差分では削除を伝えられず、前のファイルから戻すと消した記録も戻るため）。"
    const val BACKUP_NOTHING_CHANGED = "前回のバックアップの後に増えた・変わった記録はありません"
    fun backedUpDiff(days: Int, chunks: Int, audioFiles: Int) =
        "差分（${days}日分、チャンク ${chunks}件" + (if (audioFiles > 0) "、音声 ${audioFiles}件" else "") +
            "）をバックアップしました。全体のバックアップと前の差分のファイルも残してください。"
    const val BACKUP_CREATE_BUTTON = "バックアップを作成"
    const val BACKUP_CREATING = "暗号化して書き出し中…"
    const val BACKUP_NOTHING = "バックアップする記録がありません"
    /** No raw reason (#135); the diagnostics log has the error type. */
    const val BACKUP_FAILED = "バックアップを作成できませんでした。保存先の空き容量を確認して、もう一度試してください。"
    fun backedUp(days: Int, chunks: Int, audioFiles: Int) =
        "${days}日分（チャンク ${chunks}件" + (if (audioFiles > 0) "、音声 ${audioFiles}件" else "") +
            "）をバックアップしました。パスフレーズを忘れないようにしてください。"
    fun backupMissingAudio(count: Int) = "音声ファイルが見つからなかった ${count}件は、文字起こしだけをバックアップしました"
    const val RESTORE_TITLE = "バックアップから戻す"
    const val RESTORE_NOTE =
        "バックアップファイル（全体・差分のどちらも）を選び、作ったときのパスフレーズを入力します。この端末の記録は消えず、" +
            "ない分と新しい文字起こしだけが加わります（同じファイルを何度戻しても重複しません）。"
    const val RESTORE_PASSPHRASE = "バックアップのパスフレーズ"
    const val RESTORE_BUTTON = "ファイルを選んで戻す"
    const val RESTORE_VERIFYING = "パスフレーズとファイル全体を確認中…（この間は何も変更しません）"
    fun restoring(doneDays: Int, totalDays: Int) = "戻しています…（$doneDays / ${totalDays}日）"
    fun restored(added: Int, segments: Int, audioFiles: Int, alreadyPresent: Int, returnedAudio: Int = 0, updatedTranscripts: Int = 0) =
        "チャンク ${added}件を戻しました（発話 ${segments}件" + (if (audioFiles > 0) "、音声 ${audioFiles}件" else "") + "）。" +
            (if (alreadyPresent > 0) "すでにこの端末にあった ${alreadyPresent}件はそのままです。" else "") +
            (if (updatedTranscripts > 0) "そのうち ${updatedTranscripts}件は新しい文字起こしにしました。" else "") +
            (if (returnedAudio > 0) "端末から外していた音声 ${returnedAudio}件を戻しました。" else "")
    const val RESTORE_WRONG_PASSPHRASE = "パスフレーズが違います（またはファイルが壊れています）。何も変更していません。"
    const val RESTORE_CORRUPTED = "ファイルが壊れているか、途中までしかありません。何も変更していません。"
    const val RESTORE_NOT_A_BACKUP = "このアプリのバックアップファイルではありません。何も変更していません。"
    const val RESTORE_UNSUPPORTED = "新しいバージョンのアプリで作られたバックアップです。アプリを更新してから戻してください。何も変更していません。"
    /** Before anything was written (e.g. the file could not be read). */
    const val RESTORE_FAILED = "戻せませんでした。もう一度試してください。何も変更していません。"
    /** After some days were added (e.g. the storage is full): restoring again adds the rest. */
    const val RESTORE_FAILED_PARTWAY =
        "途中で戻せなくなりました。端末の空き容量を確認して、同じファイルでもう一度戻してください（戻した分は重複せず、残りが加わります）。"

    // Status screen: alert settings (#43)
    const val ALERTS_TITLE = "通知"
    const val GAP_ALERT_SWITCH = "録音が届かないときに通知"
    fun gapAlertSwitchDetail(minutes: Long) = "Watch で録音中のはずなのに${minutes}分以上データが届かないとき"
    const val QUIET_HOURS_SWITCH = "おやすみ時間は通知しない"
    const val QUIET_HOURS_DETAIL = "おやすみ時間中に途絶えた場合は、終了時刻に通知します"
    const val QUIET_HOURS_FROM = "開始"
    const val QUIET_HOURS_TO = "終了"

    // Status screen: when to transcribe (#75)
    const val TRANSCRIPTION_SETTINGS_TITLE = "文字起こしのタイミング"
    const val CHARGING_ONLY_SWITCH = "充電中のみ文字起こしする"
    const val CHARGING_ONLY_DETAIL = "スマホの電池を使わず、充電中にまとめて文字起こしします"
    fun batteryPolicyNote(minBatteryPercent: Int, audioMinutesPerHour: Long) =
        "充電していないときは、電池残量が${minBatteryPercent}%以上で省電力モードでない間だけ、" +
            "1 時間あたり録音${audioMinutesPerHour}分ぶんまで文字起こしします。残りは充電中に処理します。" +
            "スマホが熱いときは充電中でも休みます。"

    // Status screen: languages (#81)
    const val LANGUAGE_SETTINGS_TITLE = "文字起こしの言語"
    const val ENGLISH_SWITCH = "英語も文字起こしする"
    fun englishDetail(megabytes: Int) =
        "日本語に加えて、英語の発話を英語として文字起こしします。追加のモデル（約 $megabytes MB）を Wi-Fi でダウンロードし、" +
            "文字起こしにかかる時間と電池が増えます。1 つの発話の中で日本語と英語が混ざると、どちらか一方の言語として認識されます。" +
            "オンにした後に届いた録音から英語を認識します。オフにするとモデルを削除します。"

    /** The switch's description where the OS installs the English model (iOS, #170). */
    const val ENGLISH_DETAIL_OS_MANAGED =
        "日本語に加えて、英語の発話を英語として文字起こしします。iPhone の英語の音声認識モデルを使い（初回は OS がダウンロードします）、" +
            "日本語のモデルが聞き取れなかった部分を英語のモデルでも聞くため、文字起こしにかかる時間と電池が増えます。" +
            "1 つの発話の中で日本語と英語が混ざると、どちらか一方の言語として認識されます。" +
            "オンにした後に文字起こしする録音から英語を認識します。オフにすると、このアプリの英語モデルの確保を解除します（削除するかは OS が決めます）。"

    /** The extra English model's state under the switch (#81; [ModelSetup.OS_MANAGED]: iOS, #170). */
    fun englishModelState(model: ModelState, waitingForWifi: Boolean, setup: ModelSetup = ModelSetup.DOWNLOAD): String = when (model) {
        ModelState.Unknown -> "英語モデル: 確認中…"
        ModelState.Missing -> when {
            setup == ModelSetup.OS_MANAGED -> "英語モデル: 未準備"
            waitingForWifi -> "英語モデル: Wi-Fi に接続するとダウンロードします"
            else -> "英語モデル: 未ダウンロード"
        }
        is ModelState.Downloading -> when (setup) {
            ModelSetup.DOWNLOAD -> "英語モデルをダウンロード中… ${(model.progress * 100).toInt().coerceIn(0, 100)}%"
            ModelSetup.OS_MANAGED -> "英語モデルを準備中…（初回は OS がダウンロードします）"
        }
        ModelState.Ready -> "英語モデル: 準備完了（英語も端末内で文字起こしします）"
        is ModelState.Failed -> when (setup) {
            ModelSetup.DOWNLOAD -> "英語モデルのダウンロードに失敗しました。しばらくしてから自動で再試行します"
            ModelSetup.OS_MANAGED -> "英語モデルを準備できませんでした。次の文字起こしのときにもう一度試します"
        }
    }
    const val ENGLISH_DOWNLOAD_NOW = "モバイルデータ通信でもダウンロード"

    /** Retries the English model where the OS installs it (iOS, #170). */
    const val ENGLISH_PREPARE_AGAIN = "英語モデルをもう一度準備"

    /** Attribution of the English model under its switch (#167; the FunASR model license asks for it). */
    fun englishModelAttribution(name: String, author: String, url: String) = "英語のモデル: $name — $author（出典: $url）"

    // Licenses (#167). License texts stay in their original English.
    const val LICENSES_TITLE = "ライセンス"
    const val LICENSES_NOTE = "このアプリが使っているオープンソースのライブラリと、文字起こし・意味検索のモデルの作者とライセンスです。"
    const val SHOW_LICENSES = "ライセンスを表示"
    const val LICENSES_BACK = "戻る"
    const val LICENSES_INTRO =
        "このアプリが使っているライブラリと、ダウンロードして使う文字起こし・意味検索のモデルです。" +
            "ライセンスの本文は原文（英語）のまま載せています。URL は長押しで選択してコピーできます。"
    const val LICENSES_MODELS = "モデル（文字起こし・意味検索）"
    const val LICENSES_LIBRARIES = "ライブラリ"
    const val LICENSES_TEXTS = "ライセンスの本文"
    fun licenseAuthor(author: String) = "作者: $author"
    fun licenseName(license: String) = "ライセンス: $license"
    fun licenseUrl(url: String) = "URL: $url"
    fun licenseIncludedIn(component: String) = "$component に含まれています"
    fun licenseDownloadedFrom(url: String) = "ダウンロード元: $url"
    fun licenseApps(apps: String) = "使っているアプリ: $apps"
    fun licenseApp(app: NoticeApp) = when (app) {
        NoticeApp.ANDROID -> "スマホ（Android）"
        NoticeApp.WEAR_OS -> "腕時計（Wear OS）"
        NoticeApp.IOS -> "iPhone"
    }
    const val SHOW_LICENSE_TEXT = "本文を表示"
    const val HIDE_LICENSE_TEXT = "本文を閉じる"
    fun licenseTextAt(url: String) = "本文: $url"

    /** What the FunASR Model License (SenseVoice Small) asks, in short (#167); the original is at its URL. */
    const val FUNASR_TERMS_SUMMARY =
        "使用・複製・改変・共有が許可されています。条件は、出典と作者を示し、モデル名を残すことです。" +
            "提供者は「参考・学習目的」で提供しており、使用による損害の責任を負いません。" +
            "条項に違反するとライセンスは終了します。改訂版は公式リポジトリで公開された時点で効力を持ちます。" +
            "このモデルは「英語も文字起こしする」をオンにしたときだけダウンロードします。"

    /** Why transcription is waiting, for the status card (#75). */
    fun transcriptionPaused(reason: PauseReason, minBatteryPercent: Int): String = when (reason) {
        PauseReason.THERMAL -> "スマホが熱くなっているため、文字起こしを休んでいます。冷えたら再開します"
        PauseReason.CHARGING_ONLY -> "充電中のみ文字起こしする設定のため、充電を待っています"
        PauseReason.LOW_BATTERY -> "電池残量が${minBatteryPercent}%未満のため、充電を待っています"
        PauseReason.POWER_SAVE -> "省電力モードのため、充電を待っています"
        PauseReason.HOURLY_CAP -> "電池を節約するため、続きは少し後か充電中に文字起こしします"
    }
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
    const val OPEN_SETTINGS = "設定を開く"
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
