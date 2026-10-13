# iOS / watchOS 実装メモ

Apple Watch で装着中ずっと録音 → WatchConnectivity で iPhone へ（ローカルのみ）→ iPhone 上の Speech フレームワーク（SpeechAnalyzer / SpeechTranscriber, iOS 26, 日本語）でオンデバイス文字起こし → Kotlin 共有コードのローカル SQLite に保存、までの Apple 側の実装。充電中・睡眠中などの欠損は許容する。

関連 Issue: #15（watchOS 録音）、#17（転送 + ACK）、#21（iOS 文字起こし）、#41（コンプリケーション、§14）、#178（意味検索、§15）、#185（エージェント連携、§16）

## 1. コンポーネント

| 場所 | ファイル | 役割 |
| --- | --- | --- |
| watch / iPhone / テスト共通 | `apps/ios/SharedSwift/ChunkProtocol.swift` | `ChunkManifest` / `ChunkAck` の Codable 版（`shared/protocol` の Swift ミラー） |
| 〃 | `apps/ios/SharedSwift/FileDigest.swift` | ファイルの SHA-256（CryptoKit, 64 KiB ずつ）、WatchConnectivity の辞書キー |
| watchOS | `apps/ios/Watch/Recording/RecorderController.swift` | 録音本体（ローテーション・メータリング・判定結果の適用・通知） |
| 〃 | `apps/ios/Watch/Recording/RecorderPolicy.swift` | 停止理由・自動再開・通知・無音判定の純粋ロジック（WatchKit / AVFoundation に依存しない。Wear OS の `RecorderPolicy` に相当） |
| 〃 | `apps/ios/Watch/Recording/ChunkAudioInput.swift` | チャンクを書く入力のプロトコル。本番は `MicrophoneChunkInput`（`AVAudioRecorder` + メータリング） |
| 〃 | `apps/ios/Watch/Recording/DebugAudioFileInput.swift` | **Debug 構成のみ**（`#if DEBUG`）: マイクの代わりに音声ファイルを流す入力（§13） |
| 〃 | `apps/ios/Watch/Recording/ChunkOutbox.swift` | 永続アウトボックス（録音中チャンクの状態、送信待ちチャンク、異常終了からの復旧） |
| 〃 | `apps/ios/Watch/Recording/ChunkSendTracker.swift` | 送信キューの再送判断（ACK の処理、未 ACK の再送時刻、送信中の二重送信防止） |
| 〃 | `apps/ios/Watch/Recording/ChunkTransport.swift` | WCSession 送信・ACK 受信 |
| 〃 | `apps/ios/Watch/Recording/DiagnosticsLog.swift` | 上限付きのローカル診断ログ（イベント + 電池残量、CSV） |
| 〃 | `apps/ios/Watch/WatchAudioApp.swift`, `ContentView.swift` | 画面、scenePhase、WatchConnectivity のバックグラウンド起床 |
| iOS (Swift) | `apps/ios/App/iOSApp.swift` | 起動時に Kotlin グラフ → WatchConnectivity の順で開始、scenePhase を Kotlin に通知、Compose UI をホスト |
| 〃 | `apps/ios/App/WatchReceiver.swift` | WCSession 受信 → Kotlin `Ingestor` → ACK 返信 |
| 〃 | `apps/ios/App/SpeechTranscription.swift` | SpeechAnalyzer + SpeechTranscriber による文字起こし本体（モデルの準備と確保の解除・範囲の連結・時刻と確信度つきセグメント）。Kotlin に依存しない |
| 〃 | `apps/ios/App/SpeechAnalyzerTranscriber.swift` | 上記を Kotlin の `IosSpeechTranscriber` として公開するブリッジ（言語 `ja` / `en` → ロケール `ja_JP` / `en_US`） |
| iOS (Kotlin) | `shared/ui/src/iosMain/.../IosAppGraph.kt` | iOS の composition root（DB・音声置き場・BGTask）。受信は共通の `ChunkIntake`、文字起こし 1 回分は共通の `TranscriptionRun`（`shared/pipeline`、Android と同じコード）を呼び、結果を WatchConnectivity の ACK・前面実行・BGTask に対応づける |
| 〃 | `IosAppServices.kt` | 共有 UI 向け `AppServices` 実装（状態 Flow、AVAudioPlayer 再生、権限、モデル準備、通知設定の変更通知） |
| 〃 | `IosPermissions.kt` | オンボーディングで求める権限（通知 `UNUserNotificationCenter`、音声認識 `SFSpeechRecognizer`）の状態取得と要求 |
| 〃 | `IosGapAlerts.kt` | 録音の途絶アラート（#43）のローカル通知の予約・取り消し |
| 〃 | `IosSpeechTranscriber.kt` | Swift 実装を受けるコールバック型インターフェースと共通 `Transcriber` へのアダプタ（英語オンのときは共通の `SegmentRouting` で英語のパスを足す、#170） |
| 〃 | `IosTranscriptionCheck.kt` | シミュレータ検査用: 音声ファイルを一時 DB にチャンクとして取り込み、本番と同じアダプタと `TranscriptionPipeline` で文字起こしして保存されたセグメントを返す（`EnglishTranscriptionTests` が使う。アプリの DB には触れない） |
| 〃 | `MainViewController.kt` | Compose UI のエントリ（Swift から引数なしで呼ぶ） |
| iOS（意味検索） | `apps/ios/App/Embedding/*.swift`、`shared/ui/src/iosMain/.../IosEmbedding*.kt` | 埋め込みモデルの実行（ONNX Runtime の Swift パッケージ）と Wi-Fi のみのダウンロード（#178）。一覧は §15 |
| iOS（エージェント連携） | `apps/ios/App/Agent/*.swift`、`shared/ui/src/iosMain/.../IosAgent*.kt` | PC のエージェントへの LAN 内の受け渡し（#185）。一覧は §16 |
| テスト | `apps/ios/Tests/EmbeddingTests/EmbeddingTests.swift` | アプリをホストにした XCTest。アプリの ONNX Runtime で JVM・Python の参照ベクトルと一致、言い換えのクエリで会話が見つかる、ダウンロードが続きから再開して SHA-256 を確かめる（§15.5） |
| テスト | `apps/ios/Tests/AgentAccessTests/AgentAccessTests.swift` | アプリをホストにした XCTest。CI のホストの本物の橋渡し CLI と 127.0.0.1 でペアリング → ON の間だけ答える → OFF・画面から外れる・取り消しで止まる（§16） |
| テスト | `apps/ios/Tests/ProtocolTests/ChunkProtocolTests.swift` | Swift プロトコル実装と録音形式（`RecordingFormat.current`）を Kotlin のテストベクタ（`shared/protocol/testdata/*.json`）で検証 |
| 〃 | `apps/ios/Tests/WatchLogicTests/RecorderPolicyTests.swift` | 停止理由（充電・低電池・ストレージ不足・割り込み・ユーザー停止・レコーダー停止）の判定、ヒステリシス（10% 未満で停止・15% 以上で再開）、自動再開、通知、無音判定（ピーク −40 dBFS）、停止理由がマニフェストの `endReason` に書かれること（#15）、しきい値が共通の `RecorderRules` と一致すること、Mark でチャンクを閉じずオフセットが manifest に入ること（#137） |
| 〃 | `apps/ios/Tests/WatchLogicTests/ChunkOutboxTests.swift` | 送信キュー: 最終 ACK で削除、CORRUPT で再送（上限つき）、REJECTED は保持、ACK が無ければ 15 分後に再送、送信中は二重送信しない、再起動後も残り再送される、強制終了からの復旧（#17） |
| 〃 | `apps/ios/Tests/SpeechTests/SpeechTranscriptionTests.swift` | シミュレータで SpeechTranscriber（ja_JP）が既知の日本語音声（16 kHz AAC）を時刻付きで文字起こしできるか（#21）。使えなければ理由を記録してスキップ |
| 〃 | `shared/ui/src/iosTest/.../IosPlaybackTest.kt` | `IosAppServices.play` が正しいチャンクのファイルを AVAudioPlayer で開き、指定位置（±0.2 秒）に合わせ、長さも正しいこと。共有 UI で前日のタイムライン → 発話タップ、検索 → 1 タップ → その発話の位置（#19, #25）。テスト実行ファイルはアプリではないため `play()` は false を返し、`started=` は記録のみ |
| 〃 | `apps/ios/Tests/PlaybackTests/HostedPlaybackTests.swift` | iOS アプリをホストにした XCTest。アプリが起動時に作った本物の `IosAppServices`（ComposeApp フレームワーク経由）で Watch と同じ形式（AAC 16 kHz モノラル）の音声を 8 秒から再生し、再生位置がそこから始まり 2 秒後に 1 秒以上進むこと（#19） |
| 〃 | `shared/ui/src/iosTest/.../IosTranscriberBridgeTest.kt` | Swift 側の結果（`IosTimedText`）が `IosTranscriberAdapter` → `TranscriptionPipeline` で絶対時刻つきで DB と FTS に保存されること、Swift のエラーでチャンクが FAILED になること（#21）。英語オンで日本語 → 英語 → 日本語のチャンクが `ja, en, ja` で保存され、日本語だけのチャンクはオン・オフで同じ、英語のパスが失敗しても日本語が残ること（#170） |
| 〃 | `apps/ios/Tests/SpeechTests/EnglishTranscriptionTests.swift` | 実際の音声認識（`say` の日本語・英語の音声）を Swift の文字起こし → 共通の振り分け → パイプライン → SQLite まで通し、日本語 → 英語 → 日本語が `ja, en, ja` で保存されること、日本語だけの音声は英語のオン・オフで同じになること（#170、§7.5） |

## 2. データフロー

```mermaid
sequenceDiagram
  participant R as Watch: RecorderController
  participant O as Watch: ChunkOutbox (ファイル)
  participant T as Watch: ChunkTransport
  participant WC as WatchConnectivity (OS 管理)
  participant P as iPhone: WatchReceiver
  participant K as iPhone: IosAppGraph (Kotlin)
  participant S as SpeechAnalyzerTranscriber

  R->>O: 5 分ごと / 停止でチャンクを確定（音声移動・SHA-256・manifest.json。Mark はオフセットとして含む）
  R->>T: send(item)
  T->>WC: transferFile(audio, metadata: [manifest, chunkId])<br/>無音チャンクは transferUserInfo([manifest, chunkId])
  WC->>P: session(_:didReceive:) / didReceiveUserInfo
  P->>P: inbox へ同期的に move（メソッドを抜けると OS が削除するため）
  P->>K: ingest(manifestJson, filePath) { ackJson in ... }
  K->>K: Ingestor: サイズ・SHA-256 検証 → audio/yyyy/MM/dd/ へ移動 → SQLite
  K-->>P: ChunkAck JSON
  P->>WC: transferUserInfo(["ack": json])
  WC->>T: didReceiveUserInfo
  T->>R: ack
  R->>O: stored / duplicate なら削除、corrupt なら再送
  K->>S: 前景時すぐ / BGProcessingTask（充電中）で transcribe(parts)
  S-->>K: [IosTimedText]（音声内の相対時刻）
  K->>K: TranscriptionPipeline: transcript_segment + FTS に保存
```

## 3. ワイヤ形式（Kotlin との互換）

- 正は `shared/protocol`（`ChunkManifest.kt` / `ChunkAck.kt` / `ProtocolJson.kt`）。Swift 側は `apps/ios/SharedSwift/ChunkProtocol.swift` で同じ形を Codable で表現する。
- 時刻はすべて **Int64 のエポックミリ秒**、列挙値は Kotlin の `@SerialName` と同じ小文字文字列（`watchos`, `aac`, `rotation`, `user_stop`, `low_battery`, `stored`, `corrupt` …）。
- `audio` が無い（無音チャンク）場合はキーごと省略（Kotlin の `explicitNulls = false` と同じ）。`markOffsetsMs` は空でも `[]` を出力（Kotlin の `encodeDefaults = true` と同じ）。未知キーは両側で無視。
- `markOffsetsMs` は `0...durationMs` に収める必要がある（Kotlin 側の `init` で検証されるため、Swift 側で clamp している）。
- WatchConnectivity の辞書キー: `manifest`（manifest JSON）、`chunkId`（送信中転送の突き合わせ用に JSON 外にも複製）、`ack`（ChunkAck JSON）。
- 互換性は `ProtocolTests`（CI の `SwiftUnitTests` スキーム）で、Kotlin 側と同じテストベクタを decode → encode して JSON が一致することを確認している。

## 4. watchOS 録音

- **形式**: AAC-LC 16 kHz mono 24 kbps、5 分チャンク（`SharedSwift/RecordingFormat.swift` の `RecordingFormat.current`。Kotlin の `RecordingFormat.CURRENT` と同じ値であることを `shared/protocol/testdata/recording-format.json` で両側からテストする。`ChunkAudioFormat` はそれを `AVAudioRecorder` の設定に変換するだけ。約 10.8 MB/時）。`AVAudioSession(.record)` + `UIBackgroundModes: audio` で画面消灯中も継続（M0-3 で確認済みの方式）。
- **ローテーション**: 5 分ごと。次のファイルの `record()` を開始してから前のファイルを `stop()` する（数 ms 重なるが欠けない）。2 本同時に開けなかった場合は stop → start にフォールバックし、診断ログに `rotate_fallback` を残す。
- **Mark**: 現在チャンクの先頭からのオフセットを `markOffsetsMs` に記録し、すぐに録音中チャンクの状態ファイルに保存する。**チャンクは閉じない**（Wear OS と同じ。#137 までは Mark でチャンクを閉じて `endReason = mark` にしていた）。iPhone にはそのチャンクが閉じたとき（5 分ごとのローテーション・停止）に届く。
- **無音判定**: `isMeteringEnabled = true` にして 1 秒ごとに `updateMeters()` → `peakPower`（dBFS）をサンプリング。チャンク全体で一度もピークが −40 dBFS に届かず、かつサンプルがチャンク長の 80% 以上取れていた場合のみ「確実に無音」とし、音声を削除して manifest のみ送る。サンプル不足（タイマーが止まっていた等）の場合は音声を残す（安全側）。−40 dBFS と 80% は Wear OS と共通の `RecorderRules`（`SharedSwift/RecorderRules.swift`、#137 までは平均 −50 dBFS またはピーク −35 dBFS を超えたら音あり）。閾値は実機で要調整。
- **共通ルール**: 電池・無音・Mark の扱いは Wear OS と揃えている（[02 §6.1「両 Watch で共通の録音ルール」](../architecture/02-architecture.md#両-watch-で共通の録音ルール137)）。値は `RecorderRules.current` にあり、Kotlin の `RecorderRules.CURRENT` と同じであることを `shared/protocol/testdata/recorder-rules.json` で両側からテストする。
- **停止理由と自動再開**（`wantsRecording` を UserDefaults に保持し、「ユーザーが録音したい状態」なら条件が解けたときに再開する）:

| 状況 | 終了理由 | 再開 |
| --- | --- | --- |
| ユーザーが停止 | `user_stop` | しない |
| 充電開始（`WKInterfaceDevice.batteryState` を 30 秒ごとに確認） | `charging` | 充電器から外れ、アプリが動いている/前面に来たとき |
| 電池 10% 未満（`batteryLevel` を四捨五入した整数 % で比べる。15% 未満では録音を開始しない） | `low_battery` | 15% 以上に戻ったとき + ハプティクスと通知 |
| 空き容量 100 MB 未満 | `low_storage` | 空きが戻ったとき + 通知 |
| 通話・Siri など（`AVAudioSession.interruptionNotification`） | `interruption` | `.ended` で再開を試行（`shouldResume` が無くても試す）。失敗時はハプティクス + ローカル通知。中断時に 2 分後の通知を予約し、再開できたら取り消す（アプリがサスペンドされても通知が出るように） |
| レコーダーが通知なしに停止 | `error` | 最大 3 回まで自動再開、超えたら通知 |
| ローテーションで次のレコーダーを開始できない | `low_storage`（空き不足のとき）/ `error` | 通知 |
| アプリ強制終了 | `terminated`（次回起動時に確定） | 次に前面に来たとき |

- 判定（どのイベントでどの終了理由で止めるか・再開するか・通知するか）は `RecorderPolicy.decide(event, phase:, wantsRecording:)` に集約し、`RecorderController` は端末の値（電池・充電・空き容量）とイベント（停止ボタン・定期チェック・割り込み・レコーダー停止・ローテーション失敗）を渡して結果を適用するだけにした。判定は `WatchLogicTests` で単体テストしている。

- **充電後の自動再開の制約**: 録音を止めて音声セッションを無効にするとアプリはサスペンドされるため、バックグラウンドで「外れた」ことは検知できない。実際の再開は「アプリが前面に戻ったとき」（手首を上げてアプリが表示されている、またはユーザーが開いたとき）になる。watchOS の「アプリに戻る」設定次第。
- **装着検出**: watchOS にはサードパーティ向けの手首検出 API が無いため `off_body` は生成しない（外すとウォッチはロックされるが録音は続く）。
- **アウトボックス**（`Application Support/WatchAudio/`）:
  - `recording/<id>.m4a` と `recording/<id>.json`（録音中チャンクの状態: id・セッション・開始時刻・TZ・Mark）
  - `outbox/<id>.m4a` と `outbox/<id>.json`（確定済み manifest）。最終 ACK（`stored` / `duplicate`）で削除。
  - 起動時、`recording/` に残っているものは `terminated` として確定する。`AVAudioFile` で読める場合のみ音声を残し（長さは実サンプル数から算出）、読めない場合（moov 未書き込み等）は manifest のみ（長さは更新日時から推定）。
- **deviceId**: インストールごとの UUID（`watchos-…`）を UserDefaults に保存。ハードウェア識別子は使わない。
- **診断ログ**: `diagnostics.csv`（イベント・電池残量・充電状態）。512 KB を超えたら新しい半分だけ残す。ウォッチ内のみ（送信しない）。

## 5. 転送と ACK

- WatchConnectivity は Apple が管理するペアリング端末間のローカル転送（Bluetooth / P2P Wi-Fi 等、経路は OS が選択・キューイング・バックグラウンド配送）。自前のサーバーやクラウドには一切送らない。
- 送信条件: `activationState == .activated`（または `isReachable`）のときのみ。`transferFile` / `transferUserInfo` は相手が不在でも OS がキューに保持する。
- 二重送信防止: `outstandingFileTransfers` / `outstandingUserInfoTransfers` のメタデータ `chunkId` と照合。送信済みで ACK 待ちのものは 15 分間は再送しない（`ChunkSendTracker.resendAfter`。送信失敗の通知を受けたものはすぐ再送対象）。WatchConnectivity は転送を OS のキューに預けて届くまでに時間がかかることがあり、早く再送すると同じチャンクが二重に届くため、Wear OS（`ChannelClient`、3 分）より長くしている（[02 §6.1](../architecture/02-architecture.md#両-watch-で共通の録音ルール137)）。
- 再送: WCSession が activated になるたび（= アプリ起動ごと）に未 ACK の全チャンクを再送（iPhone は `duplicate` で最終 ACK を返すので安全）。`corrupt` は最大 3 回まで即再送。`rejected`（新しいスキーマ等）は保持して次回起動時に再提示。
- iPhone 側は `session(_:didReceive:)` 内でファイルを `Application Support/WatchAudio/inbox/` に**同期的に** move してから非同期で取り込む。取り込みは `UIApplication.beginBackgroundTask` で保護。取り込み前にプロセスが落ちた inbox の残骸は次回起動時に削除（Watch が再送する）。
- 取り込み自体は Android と共通の `ChunkIntake.receive`（#134）: manifest が読めないがチャンク ID は読めるなら `rejected`、ID も読めない・DB に書けないなら ACK なし（`nil`）。inbox のファイルは結果にかかわらずアーカイブへ移すか削除する。
- 取り込み・文字起こし・古い音声の削除・途絶通知の予約に失敗したときは、`IosAppGraph` が文言ではなく `ProcessingStatus.notice`（`StatusNotice.IngestFailed` / `TranscriptionFailed(件数)` / `RetentionFailed` / `GapAlertScheduleFailed`）を設定し、状態タブの日本語の文言は共通の `Strings.statusNotice` が作る（#135）。例外のメッセージは画面に出さない（エラーの種類は診断ログ）。
- Watch がサスペンド中に ACK が届いた場合は `.backgroundTask(.watchConnectivity)` で起こされ、`hasContentPending` が消えるまで起きている。

## 6. Kotlin ↔ Swift ブリッジ

Swift から見える Kotlin API（`ComposeApp` フレームワーク、静的）:

```swift
IosAppGraph.shared.start(transcriber: SpeechAnalyzerTranscriber(), agent: AgentPlatform(), embedding: EmbeddingPlatform())   // App.init で 1 回（BGTask 登録を含む。agent はエージェント連携 §16、embedding は意味検索 §15）
IosAppGraph.shared.inboxDirectoryPath()                               // 受信ファイルの退避先
IosAppGraph.shared.ingest(manifestJson: json, filePath: path) { ackJson in … }  // ackJson: String?（nil = ACK しない）
IosAppGraph.shared.setAppActive(active: true / false)                 // scenePhase .active / .background
MainViewControllerKt.MainViewController()                             // Compose UI
```

- Swift の `async` は Kotlin から呼べないため、文字起こしはコールバック型の Kotlin インターフェース `IosSpeechTranscriber`（`language`、`engineId(language:)`、`prepareModel(language:callback:)`、`releaseModel(language:callback:)`、`transcribe(parts:language:callback:)`、`cancel()`。`parts` は `IosAudioPart(path, fromMs, toMs)` のリスト、結果は `IosTimedText(startMs, endMs, text, confidence)`。言語は `ja`（主）と `en`（#170））を Swift で実装し、Kotlin 側 `IosTranscriberAdapter` が `suspendCancellableCoroutine` で共通 `Transcriber` に変換する。コルーチンのキャンセルは `cancel()` → Swift の `Task.cancel()` → `SpeechAnalyzer.cancelAndFinishNow()` に伝わる。
- Swift 6 の厳格な並行性チェック対策として、Kotlin のコールバック（非 Sendable）は `UncheckedSendableBox` に入れて Task に渡す。コールバックはどのスレッドから呼んでもよく、必ず 1 回だけ呼ぶ。
- `MainViewController()` は引数なしのまま `IosAppGraph.services` を取得し `App(services)` を表示する。オンボーディング・権限・途絶アラートはすべて Kotlin（iosMain）側で実装しており、Swift 側の変更はない。
- Kotlin 内部状態はメインディスパッチャ上でのみ変更する（`ingest` はどのスレッドから呼んでもよい）。

## 7. 文字起こし（iOS）

- 実装は `SpeechTranscription.swift`（Speech フレームワークだけに依存）にあり、`SpeechAnalyzerTranscriber.swift` はそれを Kotlin の `IosSpeechTranscriber` として渡す薄いブリッジ。分けてあるのは、シミュレータテスト（`SpeechTests`）が同じコードを自分のバンドルにコンパイルして直接動かせるようにするため（英語の検査 `EnglishTranscriptionTests` は Kotlin フレームワークのヘッダも参照する、§7.5）。
- `SpeechTranscriber(locale:transcriptionOptions: [], reportingOptions: [], attributeOptions: [.audioTimeRange, .transcriptionConfidence])`。確信度は結果の各ランの `transcriptionConfidence` を文字数で重み付けした平均（英語の振り分けに使う。DB には保存しない）。ロケールは `SpeechTranscriber.supportedLocale(equivalentTo: ja_JP)` で解決。`isAvailable` が false の端末ではエラー。
- モデルは OS 管理: `installedLocales` に無ければ `AssetInventory.reserve(locale:)` → `assetInstallationRequest(supporting:)` → `downloadAndInstall()`。Kotlin 側は各実行の前に `prepareModel` で確認し（共通の `TranscriptionRun` が `SpeechModel.ensureReady` として呼ぶ）、失敗したら実行しない（モデル未導入のまま全チャンクがリトライ上限に達するのを防ぐ）。失敗したチャンクがあった実行の後は状態を `Unknown` に戻し、次の実行で確認し直す（`SpeechModel.recheck`、Android と共通の扱い）。状態は `ProcessingStatus.asrModel`（Downloading / Ready / Failed）。
- `SpeechAnalyzer(modules:)` → `analyzeSequence(from: AVAudioFile)` → `finalizeAndFinish(through:)`。結果は `transcriber.results` を別 Task で収集し、`isFinal` の各結果を 1 セグメント（`result.range` の開始・終了をミリ秒に変換）とする。
- 入力は音声パーツのリスト。通常はチャンク 1 つ（先頭から）で、そのファイルをそのまま解析する。チャンク境界をまたぐ発話がある場合（[02 §6.3](../architecture/02-architecture.md#63-処理パイプラインphone)）は、隣のチャンクの境界付近を含む複数パーツが来るので、各ファイルを `AVAudioFile` の `processingFormat`（Float32）で読み、範囲を切り出して一時ディレクトリの 32 bit float CAF に書き出し（ファイルが短ければ無音で埋める）、その一時ファイルを解析して削除する。パーツ間でサンプルレート・チャンネル数が違うとエラーにし、共有パイプラインがそのチャンク単体で認識し直す（同じセッションのチャンクは同じ形式なので通常は起きない）。一時ファイルは最大で「チャンク + 前後 30 秒」分（16 kHz モノラルで約 23 MB）。
- エンジン ID: `apple-speechtranscriber-ja`。言語: `ja`。英語オン（§7.5）のときは `apple-speechtranscriber-ja+apple-speechtranscriber-en`（各セグメントの `lang` が書いたモデルを示す）。
- 誤認識の除去（#105）: `IosAppGraph` も Android と同じ `FalsePositiveFilter.DEFAULT` を `TranscriptionPipeline` に渡す。ただし判断の材料（Silero の確率の平均 `speechProbability`、雑音の床からの差 `levelAboveNoiseDb`）は sherpa-onnx の経路でしか付かず、SpeechAnalyzer の結果（`IosTimedText`）にはないので、現状は何も捨てない（信号がないときは捨てない規則。[02 §6.3](../architecture/02-architecture.md#会話以外の誤認識の除去105)）。SpeechAnalyzer は自前で区切るため、短い誤認識の出方は実機で確かめる（#61）。
- 実行タイミング:
  - 1 回分の処理（中断分の回復・モデルの確認・方針つきの文字起こし・保持期間の削除・診断ログ・次の予約の判定）は Android と共通の `TranscriptionRun`。iOS は返ってきた `FollowUp` を次のように扱う: `RunAgain`（失敗して再試行回数が残っている・実行中に届いた）→ 前面ならすぐ、BGTask なら時間切れまで続けて再実行、`Paused` → 下記の再試行・再予約、`Interrupted` / `WaitForModel` → 何もしない（バックグラウンドへ移るときの予約や、モデルの準備完了時の要求に任せる）。
  - アプリが前面（scenePhase `.active`）: すぐに処理。新しいチャンク受信時も再実行。ただし Android と同じ電池と発熱の方針（[02 §6.3「充電していないときの処理方針」](../architecture/02-architecture.md#充電していないときの処理方針75)、#75）に従う: チャンクを始める前に毎回 `IosDeviceConditions.read()`（`UIDevice` の電池の状態・残量、`ProcessInfo` の低電力モード・`thermalState`。`.serious` 以上で停止）を `ProcessingPolicy` で判定し、充電していなければ残量 50% 以上・低電力モードでない間だけ、1 時間に録音 10 分ぶんまで処理する。時間で再判定する止まり方（発熱 15 分・上限の枠の終わり）なら前面にいる間だけ再試行する。状態タブの「今すぐ文字起こし」は電池の条件を外す（発熱は外さない）。設定「充電中のみ文字起こしする」も同じ（`AppSettings`）。
  - バックグラウンドへ移るとき: 前面の実行をキャンセルし、`BGProcessingTaskRequest(app.watchaudio.transcribe)` を `requiresExternalPower = true`, `requiresNetworkConnectivity = false` で予約。
  - BGProcessingTask: 同じ方針でチャンク単位に進め（外部電源が条件なので、止まるのは通常は発熱のときだけ）、期限切れハンドラでは実行中のチャンクもキャンセルして速やかに `setTaskCompleted` する。期限切れなら再予約、発熱で止まったら `earliestBeginDate` を 15 分後にして再予約。
  - 判定は診断ログの `transcription_policy`、止まった理由は状態タブの「文字起こしモデル」欄に出る。
  - 実行は `TranscriptionRun` の Mutex で直列化し、毎回（モデルの確認より前に）`recoverInterrupted()` で前回中断分（TRANSCRIBING）をキューに戻す。
  - 文字起こしの後（モデル未準備で文字起こしをスキップした場合も）、同じ実行内で `StorageManager.applyRetention(shouldContinue)` により音声の保持期間を適用する（#33。文字起こし済みのチャンクの音声だけを削除）。容量表示の空き容量は `NSURLVolumeAvailableCapacityForImportantUsageKey`（取れなければ `NSFileSystemFreeSize`）、モデルサイズは OS 管理のため不明扱い（`IosStorageEnvironment`）。
- オンボーディング / 状態タブの「モデルを準備」（`AppServices.modelSetup = OS_MANAGED`、`downloadModels()`）は、モデル用の Mutex の中で `ensureModel()`（= Swift の `prepareModel`）を呼んでモデルのインストールを OS に依頼し、準備できたら文字起こしを要求する（実行中の確認と同時には走らない）。

### 7.1 録音の途絶アラート（#43）

アプリがサスペンドされていても届くよう、定期チェックではなく**予約通知**にしている（判定は共通コード、`phone-ui.md` §1.5）。

- チャンクを `STORED` で取り込むたびに `IosGapAlerts.reschedule(chunkArrived = true)`: 識別子 `app.watchaudio.gap-alert` の未配信の通知（と表示済みの古い通知）を取り消し、`GapAlertEvaluator.alertAt()` の時刻に `UNTimeIntervalNotificationTrigger` で 1 件だけ予約し直す。
- そのチャンクが停止理由付き（`user_stop` / `charging` / `off_body` など）で終わっていれば予約しない（取り消しのみ）。通常は最終受信 + 10 分。おやすみ時間（既定 23:00–7:00）にかかる場合は終了時刻に予約する。
- 状態タブで通知設定を変えると `gapAlertSettingsChanged()` → 予約し直し（すでに過ぎた時刻なら予約しない）。
- 本文「録音が届いていません（最後の受信: HH:mm）。Watch を確認してください」。予約の失敗は ACK を妨げない（診断ログに `gap_alert_blocked reason=schedule_failed` を書き、状態タブに `StatusNotice.GapAlertScheduleFailed` を出すだけ）。
- `UNUserNotificationCenterDelegate` を設定していないので、アプリが前面にある間は通知バナーは出ない（その間は状態タブの警告で分かる）。

### 7.2 診断ログ（#77）

Android と同じ共通の `DiagnosticsLog`（`shared/pipeline`、形式・上限・記録しないものは [android-phone.md §4.2](android-phone.md#42-診断ログ77)）を `IosAppGraph.start` で開く。

- 場所: `Application Support/WatchAudio/diagnostics/diagnostics.csv`（512 KB で `.1` に回す。最大約 1 MB。バックアップ対象外のディレクトリ内）。
- 記録: `app_start`（バージョン・OS）、`chunk_received` / `chunk_rejected`（共通の `ChunkIntake`。ACK の送信自体は Swift 側なので記録しない）、`worker_start` / `worker_stop`（共通の `TranscriptionRun`。`worker=foreground` / `worker=bgtask`、件数、BGTask の時間切れ）、`transcription_*`（`DiagnosticsPipelineObserver`、RTF 付き）、`retention_purge`、`model_download_start` / `model_ready` / `model_download_failed`（OS によるモデルのインストール。英語のモデルは `model=en`、#170）、`gap_alert_scheduled`（`IosGapAlerts` が予約した時刻）、`crash`。
- 異常終了: Kotlin の未捕捉例外は `setUnhandledExceptionHook` で型名とスタックの先頭を 1 行書いてから元のフック（終了）に渡す。Objective-C / Swift のクラッシュや OS による強制終了（メモリ・時間切れ）は記録できないので、OS のクラッシュレポート（設定 > プライバシーとセキュリティ > 解析と改善 > 解析データ、Xcode Organizer）を見る。`NSSetUncaughtExceptionHandler` は C 関数ポインタとグローバル状態が要るため使っていない。
- 書き出し: 状態タブの「診断ログを書き出す」→ `IosAppServices.exportDiagnostics()` が一時ディレクトリ（`tmp/diagnostics-export/`、前回分は削除）に 1 つの CSV をまとめ、`UIActivityViewController`（共有シート）を最前面の画面から表示する。送り先はユーザーが選ぶ（AirDrop・ファイルに保存など）。自動では送らない。
- 削除: 状態タブの「すべてのデータを削除」で、ログと `tmp/diagnostics-export/` のコピーも消える（`StorageManager.deleteAll` → `DiagnosticsLog.clear`、#159）。Apple Watch の `diagnostics.csv` は Watch 側にあり対象外。

### 7.3 データの書き出し（#155）

状態タブの「データの書き出し」→「書き出す」（ZIP の中身は共通コード、[phone-ui.md §1.9](phone-ui.md#19-データの書き出し155)）。`IosAppServices.saveExport()` が一時ディレクトリ（`tmp/data-export/`、前回分は削除）に ZIP を書き、`UIActivityViewController`（共有シート）を最前面の画面から表示する。保存先（「ファイルに保存」・AirDrop など）はユーザーが選ぶ。`saveExport()` はシートが閉じるまで待ち、`completionWithItemsHandler` の `completed`（選んだ保存先が完了したか）を返す。閉じたら（保存・送信・キャンセルのどれでも）一時ファイルを消す。ファイルを残さずに成功を返す宛先（コピー・プリント・連絡先に割り当て・リーディングリスト）は `excludedActivityTypes` でシートから外す。保存先を選ばずに閉じたらキャンセルの扱いで、書き出しの結果は出さず、暗号化バックアップでは音声を「バックアップ済み」と記録しない（#156）。書き込みに失敗したら一時ファイルを消す。Swift 側の変更はない。

### 7.4 暗号化バックアップ（#40）

状態タブの「暗号化バックアップ」（暗号化・復元の処理は共通コード、[phone-ui.md §1.10](phone-ui.md#110-暗号化バックアップ40)、形式は [02 §6.7](../architecture/02-architecture.md#67-暗号化バックアップ40)）。作成は §7.3 と同じ `saveExport()`（`tmp/data-export/` に暗号化済みのファイルを書いて共有シート、閉じたら削除）。復元は `IosAppServices.openBackup()` が `UIDocumentPickerViewController(forOpeningContentTypes: [UTTypeItem], asCopy: true)` を最前面の画面から表示し（delegate は UIKit が弱参照で持つので、選ぶまで `IosAppServices` が保持）、OS が `tmp/` に作ったコピーを 2 回（確認と追加）読み、終わったら（失敗でも）消す。暗号は CommonCrypto（`CCCryptorCreateWithMode` の CTR・`CCHmac`・`CCKeyDerivationPBKDF`）と Security（`SecRandomCopyBytes`）を Kotlin/Native から直接呼ぶ（`shared/platform` の `Crypto.ios.kt`）。CryptoKit（Swift のみ）は使わないので Swift 側の変更はない。標準の暗号化を OS の API で使っているため、App Store Connect の輸出コンプライアンスの申告が必要（[store-data-declarations.md](../legal/store-data-declarations.md)）。

### 7.5 英語（#170）

状態タブの「英語も文字起こしする」（`AppSettings.transcribeEnglish`、既定オフ、[phone-ui.md](phone-ui.md)）をオンにすると、日本語に加えて英語の `SpeechTranscriber`（`en_US`）も使う。方式の比較は [research/english-asr.md §7](../research/english-asr.md#7-ios170)。

- **振り分け**: `IosTranscriberAdapter` が、設定がオンで英語モデルが準備完了のときだけ、共通の `SegmentRouting`（`shared/pipeline`）で英語のパスを足す。日本語のパスはチャンク全体のまま（オフと同じ）。その結果のうち、Android と同じ `LanguageRouting` の規則で怪しいセグメント（1.5 文字/秒未満、またはカタカナ・ラテン文字が 8 割以上）と、日本語の出力がない 1 秒以上の間を、前後 0.3 秒広げてまとめ、1 秒の無音をはさんで 1 つの入力にして英語で 1 回だけ解析する（無音は「ファイルの終わりより後の範囲」で、`SpeechTranscription.concatenate` が無音で埋める）。英語の文は、区間ごとにまとめて確信度 0.5 以上（`LanguageRouting.minConfidence`、`IosTranscriberAdapter.ROUTING`）・3 語以上・ラテン文字 9 割以上なら、区間内の怪しい日本語のセグメントと置き換えて `lang = en` で保存する。広げた 0.3 秒で隣の日本語（置き換えないセグメント）を聞き直した英語の結果は捨てる。
- **エンジン ID**: 英語オンで処理したチャンクは `apple-speechtranscriber-ja+apple-speechtranscriber-en`（各セグメントの `lang` が書いたモデル）。英語のパスが失敗したら（OS がモデルを消したなど）、チャンクは日本語の文のまま `apple-speechtranscriber-ja` で保存し、診断ログに `transcription_failed pass=en fallback=ja` を残して英語モデルの状態を「確認中」に戻す（次の実行の前に準備し直す）。
- **モデル**: `IosAppGraph.syncEnglish()` が設定に合わせる。オン: `prepareModel("en")` → Swift の `makeTranscriber(en_US)`（未導入なら `AssetInventory.reserve` + `assetInstallationRequest` → `downloadAndInstall`）。状態は `ProcessingStatus.englishModel`（準備中 → 準備完了 / 失敗）。起動時・設定の変更時・各実行の前（`SpeechModel.ensureReady`、日本語のモデルの後）と、状態タブの「英語モデルをもう一度準備」で呼ぶので、失敗は次の実行で再試行される。オフ: `releaseModel("en")` → `AssetInventory.release(reservedLocale:)` でこのアプリの確保を解除する（モデルを消すかは OS が決める。日本語の確保はそのまま）。診断ログは `model_download_start` / `model_ready` / `model_download_failed` に `model=en`。
- **音声の形式**: 解析の前に `SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith:)` と入力の `processingFormat` を比べ、違えば `AVAudioConverter` で一時 CAF に変換してから渡す（`SpeechTranscription.analyzerInput`。同じなら変換しない）。
- **シミュレータ検査**: `IosTranscriptionCheck`（iosMain、公開）は音声ファイルを一時 DB にチャンクとして取り込み、本番と同じアダプタとパイプラインで文字起こしした結果を返す。`EnglishTranscriptionTests`（SpeechTests）がこれを Swift から呼ぶ。テストバンドルは PlaybackTests と同じく ComposeApp のヘッダだけを参照し、`SpeechAnalyzerTranscriber.swift` のコピーをコンパイルする。`SpeechTranscription.Engine.dictation`（DictationTranscriber）は SpeechTranscriber のないシミュレータのための検査用の選択肢で、アプリは使わない。

- iPhone: `Application Support/WatchAudio/`（`watch-audio.db`、`audio/yyyy/MM/dd/<chunkId>.m4a`、`inbox/`、`diagnostics/`（§7.2）、意味検索の `models/ruri-v3-30m/` と `search/`（§15））。`LocalDataDirectory`（shared/ui/iosMain）がディレクトリに `NSURLIsExcludedFromBackupKey` を設定して iCloud / PC バックアップに含めず、ファイル保護クラス `CompleteUntilFirstUserAuthentication`（BGTask・ロック中の取り込みから読める）を明示する（#76）。
- Watch: `Application Support/WatchAudio/`（録音中・送信待ちチャンク、診断ログ）。ACK 後に削除。`ChunkOutbox` が `LocalDataProtection`（apps/ios/SharedSwift）で同じくバックアップ除外と `completeUntilFirstUserAuthentication` を設定する（画面オフ・ロック中も録音・再送できる）。
- 属性は ios.yml のテスト（`LocalDataDirectoryTest`、`LocalDataProtectionTests`）で確認する（保護クラスの値はシミュレータが報告する場合だけ比較）。一覧は [02 §7.1](../architecture/02-architecture.md#71-保存場所と保護76)。
- 音声・文字起こしとも端末外（自前サーバー・クラウド）には送らない。Speech もオンデバイスモデル。

## 9. 権限・Info.plist

| ターゲット | キー | 値・目的 |
| --- | --- | --- |
| iosApp | `NSSpeechRecognitionUsageDescription` | 音声認識の用途説明。オンボーディングで `SFSpeechRecognizer.requestAuthorization` を念のため要求する（SpeechTranscriber に必要かは未確認。`SpeechTests` は許可状態をログに出す） |
| 〃 | `NSCameraUsageDescription` | エージェント連携（§16）の PC の QR コードを端末内で読む。「カメラで QR コードを読み取る」を押したときだけ求める |
| 〃 | `NSLocalNetworkUsageDescription`、`NSBonjourServices: [_watchaudio-mcp._tcp]` | エージェント連携（§16）: ON の間だけ、同じネットワーク内のペアリングした PC へつなぐ・探す |
| 〃 | `UIBackgroundModes: [processing]` | BGProcessingTask |
| 〃 | `BGTaskSchedulerPermittedIdentifiers: [app.watchaudio.transcribe]` | `IosAppGraph.PROCESSING_TASK_ID` と一致させること |
| WatchApp | `NSMicrophoneUsageDescription` | 録音 |
| 〃 | `UIBackgroundModes: [audio]` | 画面消灯中の録音継続 |
| 〃 | `WKApplication`, `WKCompanionAppBundleIdentifier` | iPhone アプリの付属 watch アプリ |
| 〃 | `CFBundleURLTypes`（スキーム `watchaudio`） | コンプリケーションのタップ（`watchaudio://record`）で開始・再開（§14） |
| WatchApp / WatchWidget | `WatchAudioAppGroup` + entitlement `com.apple.security.application-groups` | 録音状態を共有する App Group（`APP_GROUP_ID`、§14） |

iPhone アプリはマイクを使わない（録音は Watch だけ。再生は `AVAudioSessionCategoryPlayback`、文字起こしはファイル入力）ので `NSMicrophoneUsageDescription` を持たない（#159。ios.yml の Release ジョブが `IPHONE_MICROPHONE_CHECK` で確認）。

実行時の許可: Watch でマイク（初回開始時）と通知（初回録音開始時）。iPhone ではオンボーディングの「許可の設定」で通知（alert + sound、途絶アラート用）と音声認識を要求する（`IosPermissions`。拒否済みなら設定アプリへの案内を表示）。スパイク用の `UIFileSharingEnabled` / `LSSupportsOpeningDocumentsInPlace` は削除した。

**プライバシーマニフェスト**（#159）: `apps/ios/Privacy/{App,Watch,WatchWidget}/PrivacyInfo.xcprivacy` を各ターゲットの `sources` に `buildPhase: resources` で指定し、各バンドルの直下にコピーする（iosApp / WatchApp / WatchWidget）。追跡なし・収集データなし。理由の申告が必要な API と理由コード、その根拠のコード箇所は [docs/legal/store-data-declarations.md §2.3](../legal/store-data-declarations.md#23-プライバシーマニフェストprivacyinfoxcprivacy)。`tools/privacy-check` がアプリ・拡張ターゲットごとのマニフェストの有無と内容（追跡・収集なし、理由コードは `policy.IOS_REQUIRED_REASONS` の許可リスト）を検査し、ios.yml の Release ジョブがバンドル内の有無を `PRIVACY_MANIFEST_CHECK` で確認する。API の使い方を変えたら（`UserDefaults`・ファイルの属性・空き容量・`mach_absolute_time` / `systemUptime` など）マニフェストと §2.3 を一緒に直す。

## 10. 検証状況

| 項目 | 方法 | 状況 |
| --- | --- | --- |
| Kotlin iosMain（shared/platform / shared/ui）のコンパイル | Linux で klib クロスコンパイル `./gradlew :shared:ui:compileKotlinIosSimulatorArm64 :shared:ui:compileKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true` | 通過（リンクは不可） |
| 共通ロジック（Ingestor ほか） | `./gradlew jvmTest`、CI で `./gradlew iosSimulatorArm64Test`（SQLite を使う shared/db / Ingestor のテストを含む。ジョブサマリーにテスト一覧） | JVM は通過、iOS シミュレータは CI で確認 |
| iOS・watchOS アプリの起動 | CI `ios.yml` の Launch ステップ: ビルド済みの `.app` をシミュレータ（iPhone / Apple Watch）に入れて起動し、15 秒後もプロセスが生きていてクラッシュレポートがないことを確認（`.github/scripts/simulator-smoke.sh`、スクリーンショットは成果物 `simulator-smoke`） | CI で確認 |
| iOS の再生（`IosAppServices.play`、#19 / #25） | 2 段構え。(a) `shared/ui` の iOS シミュレータテスト `IosPlaybackTest`（`./gradlew iosSimulatorArm64Test`）: 本番の `Ingestor` で前日 10:00 開始の AAC チャンクを取り込み、(1) `play(path, 45 s)`、(2) 共有 Compose UI で「前の日」→ 発話をタップ → 「HH:mm から再生中」、(3) 検索 → 結果を 1 タップ、のそれぞれで、そのチャンクのファイル（音声ルートからの相対パスを解決したもの）が AVAudioPlayer に開かれ、再生位置が指定位置 ±0.2 秒、長さが正しいことを確認する。Kotlin/Native のテスト実行ファイルはアプリのプロセスではなく `AVAudioPlayer.play()` が false を返すため（CI で確認、シークは効く）、再生が始まったか（`started=`）は `PLAYBACK_CHECK` 行に記録するだけにしている。テストプロセスには UIApplication が無いため `IosAppGraph` ではなく、それが組み立てるのと同じ `IosAppServices` を一時 DB で作る。(b) 実際に再生が進むことは `PlaybackTests`（iOS アプリをホストにした XCTest、`HostedPlaybackTests.swift`）で確認: アプリが起動時に作った `IosAppGraph.shared.services`（本物の `IosAppServices`）に、音声ルート下に置いた AAC 16 kHz モノラル 20 秒のファイルを相対パスで渡して 8 秒から再生し、最初に観測した位置が 8 秒（-0.2〜+1 秒）、2 秒後に 1 秒以上進むことを確認（`PLAYBACK_CHECK check=hosted_player` 行）。ComposeApp は静的フレームワークでアプリにだけリンクされるので、テストバンドルはヘッダだけを参照し、シンボルは `BUNDLE_LOADER`（アプリ）から解決する（Kotlin ランタイムが二重にならないよう自動リンクを無効化）。ランナーに音声出力デバイスが無い場合に備え、CI は BlackHole（仮想デバイス）を入れる | CI で確認（ジョブサマリーの「Playback checks」と「Playback check in the app process」） |
| Swift プロトコル互換 | Linux の Swift 6.2 で同等のテストを実行 | 通過。CI では `ProtocolTests`（`SwiftUnitTests` スキーム）を iOS シミュレータで実行 |
| Watch の停止理由・状態判定（#15）、送信キュー（#17） | `WatchLogicTests`（`SwiftUnitTests` スキーム、ホストなし）を iPhone シミュレータで実行。対象の `RecorderPolicy` / `ChunkSendTracker` / `ChunkOutbox` は Foundation / AVFoundation だけを使うので watchOS 以外でもそのまま動く（WatchKit・WatchConnectivity・AVAudioRecorder を使う `RecorderController` / `ChunkTransport` は対象外で、実機確認は #60） | CI で確認 |
| 英語・日英混在（#170） | (a) 振り分けの規則: `SegmentRoutingTest` / `LanguageRoutingTest`（`jvmTest`、`iosSimulatorArm64Test`）。(b) iOS の Kotlin 側: `IosTranscriberBridgeTest`（シミュレータ）で、英語オンの日本語 → 英語 → 日本語のチャンクが `ja, en, ja` で保存され、日本語だけはオン・オフで同じ、英語のパスの失敗で日本語が残ること。(c) アプリのプロセスで Swift → Kotlin → SQLite まで: `EnglishTranscriptionTests`（SpeechTests）。CI が `say`（Kyoko / Samantha）で日本語 → 英語 → 日本語の音声（`make-mixed-speech.sh`）を作る。実際の認識器（SpeechTranscriber、なければ DictationTranscriber）の 2 件は、GitHub のシミュレータではどちらも動かない（SpeechTranscriber は `isAvailable` が false、DictationTranscriber は `bestAvailableAudioFormat` が nil で解析が「Audio format is not supported」）ため理由を記録してスキップし、認識器だけを文の時刻から答える `ScriptedTranscriber` に替えた 2 件で `ja, en, ja` と日本語だけのオン・オフ一致を確認。形式の変換が長さを保つことも確認 | CI で確認（実際の認識での精度は実機、§11 の 19） |
| SpeechTranscriber（ja_JP）の文字起こし（#21） | `SpeechTests`（iOS アプリをホストにした XCTest）。CI が macOS の `say`（日本語音声）→ `afconvert` で Watch と同じ AAC 16 kHz モノラルの音声と正解文を作り、`SpeechTranscription` で文字起こし → セグメントの時刻（0 以上・音声長以内・昇順）と文字の再現率 0.5 以上を確認。3 秒の無音を前に連結した場合に時刻がずれることも確認。DB への保存は `IosTranscriberBridgeTest`（Kotlin、シミュレータ）で確認。シミュレータで使えない（`isAvailable` が false、ja_JP 非対応、モデルを入れられない、解析がエラー）場合は理由をログ（`build/swift-evidence/`、ジョブサマリー）に残してスキップし、実機確認は #60 | CI で確認（結果はジョブサマリーの「Speech check logs」） |
| Swift の型・Swift 6 並行性チェック | Linux の Swift 6.2 + Apple フレームワークのスタブ（API 名は developer.apple.com で確認）で SIL 診断まで実行 | 通過（SwiftUI の画面ファイルは対象外） |
| 意味検索（#178） | `EmbeddingTests`（iOS アプリをホストにした XCTest）: アプリの ONNX Runtime（Swift パッケージ）で参照ベクトルとの一致、言い換えのクエリの検索、ダウンロードの再開と SHA-256 の確認（§15.5）。ダウンロードの手順は `ModelInstallerTest`（`jvmTest`・`iosSimulatorArm64Test`）、テスト用スクリプトのファイルは `RuriFilesScriptTest` | CI で確認（ジョブサマリーの「Semantic search checks in the app process」）。速度・電池・実際の Wi-Fi は実機（§11 の 21） |
| ComposeApp フレームワークのリンク（bundled SQLite を含む）、Xcode ビルド、SwiftUI、実 API との一致 | **CI（macOS）のみ** | 要確認 |
| 録音・転送・文字起こしの実動作、電池 | **実機のみ** | 下記チェックリスト |

## 11. 実機チェックリスト

1. Watch で「録音開始」→ マイク・通知の許可 → 画面消灯のまま 30 分以上録音が続く。
2. 5 分ごとにチャンクが確定し、iPhone 到着後に Watch の「未送信」が減る（ACK で削除）。
3. iPhone アプリがバックグラウンド / 未起動でも、チャンクが届き DB に入る（OS がアプリをバックグラウンド起動する）。アプリ切り替え画面でスワイプ終了した後の挙動も確認する。
4. iPhone を Watch から離して（機内モード等）録音 → 戻したときに未送信分がまとめて届く。重複が DB に入らない。
5. Mark を押してもチャンクは閉じない（診断ログに `mark: +…ms`、チャンクは 5 分のローテーションで `rotation` として閉じる）。そのチャンクが届くと manifest の `markOffsetsMs` に入り、iPhone の DB の `mark` 表に記録される。
6. 静かな部屋に 5 分以上置いたチャンクが「無音」になり音声が保存されない。小声の会話チャンクが無音扱いされない（閾値調整）。
7. 充電器に置く → `charging` で停止 → 外してアプリを開くと自動再開。
8. 録音中に電話を受ける / Siri を使う → `interruption` で区切られ、終了後に再開する。再開できない場合は通知が来る。
9. 録音中に Watch アプリを強制終了 → 再起動で直前チャンクが `terminated` として届く（音声が読めるかも確認）。
10. iPhone を初回起動 → オンボーディング（同意 → 通知・音声認識の許可ダイアログ → 「モデルを準備」で日本語モデルのダウンロード → Watch の案内）→ 前面で文字起こしが進む。
   - 途絶アラート: 録音中に Watch アプリを強制終了（`terminated` チャンクが届かない状態）→ iPhone をロックして 10 分後に「録音が届いていません（最後の受信: HH:mm）…」の通知が来る。次のチャンクが届くと予約が取り消される。Watch を充電器に置いた（`charging`）場合は通知が来ない。おやすみ時間中は終了時刻に来る。
11. iPhone を充電しながらロック → BGProcessingTask で文字起こしが進む（Xcode の `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"app.watchaudio.transcribe"]` で強制起動も可）。
12. Watch の `diagnostics.csv` で電池消費（%/時）を確認（M0-3 と比較）。
13. コンプリケーション（§14、#60）: 文字盤に「watch-audio / 録音状態」を追加 → 停止中は「停止中」、録音開始で「録音中」+ 経過時間、充電器に置くと「一時停止（充電中）」に変わる。停止中にタップ → アプリが開いて録音が始まる。通話などで中断した後にタップ → 1 タップで再開する。
14. iPhone の状態タブ「診断ログを書き出す」→ 共有シートが開き、ファイルに保存した CSV に受信・文字起こし（RTF）・BGTask の開始/終了が残っている（本文は含まれない）。長時間の検証ではこのファイルを証拠に添える。
15. 状態タブ「データの書き出し」（#155）→「期間を指定」で記録のある日を選び「音声ファイルを含める」→「書き出す」→ 共有シートの「ファイルに保存」で保存 → ファイル App で ZIP を開くと `markdown/`・`json/`・`audio/` があり、Markdown の文字起こしとマーク・録音できていない時間がタイムラインと一致し、音声が再生できる。シートを閉じた後、アプリの `tmp/data-export/` に ZIP が残っていない。
16. 状態タブ「暗号化バックアップ」（#40）→ パスフレーズと確認を入れ「音声もバックアップする」→「バックアップを作成」→ 共有シートの「ファイルに保存」で保存。別の端末（Android を含む）の「バックアップから戻す」でそのファイルを選んで同じパスフレーズを入れると、タイムラインのチャンク・文字起こし・マーク・録音できていない時間が元の端末と一致し、音声が再生できる。違うパスフレーズでは「パスフレーズが違います」と出て何も増えない。Android で作ったバックアップをこの iPhone で戻す逆方向も確かめる。終わった後、アプリの `tmp/` にバックアップのコピーが残っていない。
17. オフロード（#156）: 16 の音声入りバックアップを「ファイルに保存」で保存した後、状態タブの「バックアップ済みの音声」に件数と最後のバックアップの日付が出る → 「バックアップ済みの音声を端末から外す」→ 確認で「端末から外す」→ タイムラインの発話に ▶ の代わりに「バックアップにあり」、検索は今までどおり。同じバックアップを「バックアップから戻す」で戻すと再生できる。共有シートを保存先を選ばずに閉じたバックアップでは、外せる音声が増えない。
18. 差分バックアップ（#164）: 16 の全体のバックアップを保存した後、カードに「前回のバックアップ: <日時>（全体）」が出て「差分」が選ばれ、録音が増えると差分の件数と大きさが増える → 差分を作成して「ファイルに保存」（ファイル名が `…-diff.wabackup`）→ 前回の表示が「差分。… の後の 1個目」になる。共有シートを保存先なしで閉じた差分では前回の表示が変わらない。別の端末（Android を含む）で全体と差分を（逆順でも）戻すと、元の端末と同じチャンク・文字起こし・音声になる。タイムラインで 1 日を削除すると「差分」が選べなくなり、理由が出る。
19. 英語（#170、§7.5）: 状態タブ「英語も文字起こしする」をオン → 「英語モデルを準備中…」→「準備完了」（初回は OS がダウンロード。Wi-Fi・モバイル通信での挙動と所要時間を記録）。英語だけの会話と、日本語と英語が交互の会話を録音 → タイムラインで英語の発話が英語（`lang = en`）で、日本語はオフのときと同じに保存される。日本語だけの会話（相づち・小声・雑音を含む）でオンにしても英語に化けないこと。英語オンで文字起こし時間（診断ログの RTF）と電池がどれだけ増えるかを、日本語だけの録音（無音の多い 1 時間）と英語の録音で測る。確信度の閾値 0.5 が合っているかを、誤って英語になった・英語が残らなかった発話の数で確かめる。オフにすると英語のパスが止まり、`AssetInventory.reservedLocales` から `en_US` が消える（モデルの削除は OS 次第）。機内モードで初回にオン → 失敗の表示と「英語モデルをもう一度準備」、通信を戻すと準備できる。
20. エージェント連携（#185、§16）: PC で `npx watch-audio-mcp pair` → iPhone の状態タブ「エージェント連携を開く」→「カメラで QR コードを読み取る」→ カメラの許可（初回だけ）→ ターミナルの QR（明るいテーマ・暗いテーマ）が読める → ローカルネットワークの許可（初回だけ）→ 両方に同じ 6 桁 → ペアリングできる。家の Wi-Fi とテザリングで、ON にしてから PC のエージェントが読めるまでの時間（mDNS で見つかるか、前回のアドレスでつながるか）。ON のままホーム画面に戻る・画面を消すと OFF になり、PC 側の呼び出しが答えなくなる（戻っても OFF）。ローカルネットワークを設定アプリで OFF にしてから ON → 「ローカルネットワークへのアクセスが許可されていない」の表示。カメラを拒否してから読み取り → 文字列の入力の案内。アプリを削除して入れ直したときに鍵が作り直され、ペアリングのやり直しになること。AP アイソレーションの Wi-Fi での表示。ON の 30 分間の電池消費。

21. 意味検索（#178、§15）: Wi-Fi で検索タブを初めて開く → 「意味で探すためのモデルをダウンロード中 N%」→ 終わると「意味で探す準備中 N%」になり、言い回しの違うクエリ（例: 「サーバー代がいくら増えるって言ってた？」）で「意味が近い」の結果が出る。ダウンロードの所要時間、途中でアプリを裏に回して戻したときに続きから進むこと（診断ログの `model_download_start` の `bytes=` が減っている）を記録する。モバイル通信だけ・テザリング・省データモードの Wi-Fi では始まらず「Wi-Fi に接続したときにダウンロード」と出ること。初回の埋め込み（前面・電池では直近 48 時間の 50 チャンクまで、残りは充電中の BGProcessingTask）にかかる時間と電池、検索 1 回の時間（モデルの読み込みを含む初回と 2 回目）、モデルを読み込んだ間のメモリ（Xcode の Memory Report）、Release ビルドの App Store のサイズ（App Store Connect の「App のサイズ」）を記録する。

## 12. 既知のリスク・制約

- **CI でしか分からないこと**: ComposeApp 静的フレームワークのリンク（androidx.sqlite の bundled SQLite を含む初めてのリンク）、Kotlin が生成する ObjC ヘッダの Swift 名（`ingest(manifestJson:filePath:completion:)`、`IosSpeechTranscriber` の各メソッド、`transcribe(parts:language:callback:)` と `IosAudioPart` のプロパティ）、SwiftUI の `.backgroundTask(.watchConnectivity)` などのビルド。
- **SpeechAnalyzer とファイル形式**: 解析の前に `SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith:)` と比べ、違えば `AVAudioConverter` で一時 CAF に変換してから渡す（#170 で追加。シミュレータの DictationTranscriber が Float32 のままでは「Audio format is not supported」を返したため）。実機の SpeechTranscriber で変換が要るか（同じ形式ならそのまま）と、変換した場合の時間は実機で確かめる。
- **境界の連結（#45）は実機未確認**: 一時 CAF への書き出し（`AVAudioFile(forWriting:settings:commonFormat:interleaved:)`、`read(into:frameCount:)`、`write(from:)`）と、その結果の時刻が元のチャンクの時刻に正しく戻ることを実機で確認する。Linux ではスタブに対する Swift 6 の型チェックのみ。
- **英語（#170）の実際の精度は実機のみ**: SpeechTranscriber も DictationTranscriber も CI のシミュレータでは動かないので、日本語のモデルが英語に何を書くか（何も書かない・ラテン文字・カタカナ）、英語のモデルが日本語に付ける確信度、閾値（0.5、Android の規則の値）が合うかは実機で確かめる（§11 の 19、#60）。無音の多い録音では日本語の出力がない間がすべて英語のパスにかかるので、計算時間と電池も実機で測る。
- **音声認識の認可**: SpeechTranscriber が `SFSpeechRecognizer` の認可を必要とするかは未確認。念のためオンボーディングで要求している。不要と確認できたら `IosPermissions.all` から外す。
- **充電後の自動再開**はアプリが前面に来たときのみ（上記 4 章）。バックグラウンドで録音を開始できるかは watchOS の制約次第。
- **割り込み後の再開**: watchOS が中断後にバックグラウンドでの録音再開を許すかは M0-3 の検証課題。失敗時は通知でユーザーに促す。
- **無音判定の閾値**（ピーク −40 dBFS、Wear OS と共通）は仮の値。実機で誤判定（会話を捨てる）が無いことを最優先で確認する。`peakPower` は `updateMeters()` を呼んだ時点のメーターの値で、Wear OS の `getMaxAmplitude()`（1 秒間の最大値）とは集計の仕方が違うので、短い音を取りこぼさないかもあわせて確かめる。
- **BGProcessingTask の実行頻度**は OS 任せ（充電中・アイドル時）。前面で開いている間が最も確実。意味検索の古い会話の埋め込み（バックフィル）も同じで、BGProcessingTask が来ないと前面で開いている間に少しずつ進む（§15.4）。
- **意味検索（§15）**: アプリのサイズが ONNX Runtime の分だけ増える（§15.5）。シミュレータ（Apple Silicon の Mac）は実機と同じ arm64 の int8 演算を使うと見込むが、速度・メモリ・電池は実機でしか分からない（§11 の 21、#60）。ダウンロードは前面だけ（バックグラウンドの `URLSession` は使わない）。
- **エージェント連携（§16）は画面に出している間だけ**: iOS の制約。PC のエージェントに長く使わせるには iPhone の画面を出しておく必要がある（自動ロックで画面が消えると OFF）。mDNS で見つけたサービスを UDP の `NWConnection` でアドレスに解決する方法と、ローカルネットワークの許可を拒否したときの `NWConnection` の状態（`waiting` の理由）は、シミュレータではローカルネットワークの許可がないため実機でのみ確かめられる（#60）。
- 共有パイプラインの `TranscriptionPipeline` はキャンセル時の `resetInterrupted()` が（キャンセル済みのため）実行されない場合があるが、iOS では毎回の実行前に `recoverInterrupted()` を呼んで補っている。

## 13. 音声ファイル入力（開発用）

対象 Issue: [#57](https://github.com/Soichiro5/watch-audio/issues/57)。シミュレータでは決まった音声で録音処理を確かめられないため、**Debug 構成に限り**マイクの代わりに音声ファイルを流せる。ローテーション（5 分）・Mark・無音判定（メータリング相当）・manifest・アウトボックス・転送は本番と同じ `RecorderController` / `ChunkOutbox` のコードを通る。

### 13.1 仕組み

- `RecorderController` は各チャンクのファイルを `ChunkAudioInput` で書く。本番（Release を含む）は常に `MicrophoneChunkInput`（`AVAudioRecorder`）。
- `DebugAudioFileInput.swift` はファイル全体が `#if DEBUG` で囲まれており、`RecorderController` / `ContentView` 側の呼び出しも `#if DEBUG` の中だけにある。XcodeGen の Debug 構成は `SWIFT_ACTIVE_COMPILATION_CONDITIONS: DEBUG` を設定し、Release は設定しないため、Release ビルドにはファイル入力のコードが含まれない。
- `DebugAudioPlayback`: `AVAudioFile(forReading:)` で読み、チャンネル平均 + 線形補間で 16 kHz mono に変換。連続するチャンクで共有するので、ローテーションしても続きから流れる。
- `DebugAudioFileInput`: `record()` からの経過時間（壁時計）分のサンプルを、毎秒の `updateMeters()` と `stop()` のたびに `AVAudioFile(forWriting:settings: ChunkAudioFormat.recorderSettings, ...)` へ書き（本番と同じ AAC 16 kHz mono 24 kbps）、そのサンプルのピークを dBFS で返す（`peakPower`）。`stop()` で `close()` してから確定する。
- **実時間のみ**（watchOS 側は壁時計でチャンク長を測るため倍速はない。倍速で試したいときは Wear OS 版を使う）。
- ファイルの終わりで録音を停止し、最後のチャンクは `user_stop` で閉じる。以後の入力はマイクに戻る。

### 13.2 シミュレータでの使い方

```bash
# 1. Debug でビルドして watchOS シミュレータに入れる（例: Xcode で WatchApp スキームを実行、または）
cd apps/ios && xcodegen generate && cd ../..
xcodebuild -project apps/ios/WatchAudio.xcodeproj -scheme WatchApp -configuration Debug \
  -destination 'platform=watchOS Simulator,name=<watch のシミュレータ名>' -derivedDataPath build/dd build
WATCH=<watch シミュレータの UDID>      # xcrun simctl list devices | grep -i watch
xcrun simctl boot "$WATCH"
xcrun simctl install "$WATCH" build/dd/Build/Products/Debug-watchsimulator/WatchAudio.app

# 2. 入力ファイルをアプリのコンテナ（Documents/debug-input）に置く
DATA=$(xcrun simctl get_app_container "$WATCH" app.watchaudio.watchkitapp data)
mkdir -p "$DATA/Documents/debug-input"
cp test.wav "$DATA/Documents/debug-input/"

# 3. 起動引数で開始（ファイル名 = Documents/debug-input → アプリバンドルの順に探す、または絶対パス）
xcrun simctl launch "$WATCH" app.watchaudio.watchkitapp -debugAudioInput test.wav
xcrun simctl launch "$WATCH" app.watchaudio.watchkitapp -debugAudioInput test.wav -debugAudioInputLoop YES

# 4. 結果を見る
ls -l "$DATA/Library/Application Support/WatchAudio/recording" "$DATA/Library/Application Support/WatchAudio/outbox"
cat "$DATA/Library/Application Support/WatchAudio/outbox/"*.json
cat "$DATA/Library/Application Support/WatchAudio/diagnostics.csv"
```

- 初回はマイク権限のダイアログが出る（ファイル入力でも `AVAudioSession(.record)` を有効にする本番の経路を通るため）。許可すると開始する。
- Xcode から起動する場合は、スキームの Run → Arguments → Arguments Passed On Launch に `-debugAudioInput test.wav` を追加する。
- 画面からも操作できる: Debug ビルドの画面の一番下に `Documents/debug-input` のファイルが「▶ ファイル名」として並び、タップで開始（録音中なら一度止めてから）。「ファイル入力を止める」で停止してマイクに戻る。
- 充電中・電池残量低下の判定は本番どおり働く（シミュレータの電池状態によっては `charging` で止まる）。
- 期待される結果の例: 12 分 30 秒のファイル → 5 分・5 分・2 分 30 秒の 3 チャンク（`rotation`, `rotation`, `user_stop`）。5 分間ほぼ無音（ピーク −40 dBFS 未満）のチャンクは `audio` なしの manifest。

### 13.3 検証状況

- Linux では Swift をコンパイルできないため、API 名（`AVAudioFile(forReading:)`、`init(forWriting:settings:commonFormat:interleaved:)`、`read(into:frameCount:)`、`write(from:)`、`close()`（watchOS 11+）、`framePosition` / `length`、`AVAudioPCMBuffer.floatChannelData` / `frameLength`）は developer.apple.com の宣言で確認したのみ。Debug 構成のビルドは CI（`ios.yml`）で確認する。CI では watchOS アプリを Release 構成でもビルドし、バイナリに `DebugAudioFileInput` が含まれないこと（Debug 側には含まれること）を確かめる（ステップ「watchOS Release build has no debug audio input」）。さらに `release` ジョブで、iOS アプリ（watchOS アプリを同梱）と watchOS アプリを Release 構成・実機向けに署名なしでビルドし、同じ検査をする（#78、docs/dev/04 §7.1）。
- 実際にシミュレータで流してチャンク・manifest を確かめるのは macOS 上で行う（Wear OS 側のチャンク化・無音判定・manifest の流れは [wearos.md §11.4](wearos.md#114-開発環境でのテストエミュレータ不要) の JVM テストで確認している）。

## 14. コンプリケーション（#41）

文字盤（とスマートスタック）に録音状態を常時表示し、タップでアプリを開いて録音を開始・再開する。WidgetKit のウィジェット拡張（watchOS 26）。

### 14.1 構成

| ファイル | ターゲット | 役割 |
| --- | --- | --- |
| `apps/ios/Watch/Complication/RecorderStatus.swift` | WatchApp / WatchWidget / ProtocolTests | 共有する状態 `RecorderStatus`（`recording` + 開始時刻 / `paused` + 理由 / `stopped`）、App Group への保存 `RecorderStatusStore`、タップ URL `ComplicationLink`、状態 → 表示の純粋な対応 `ComplicationDisplay`、停止理由の文言 `RecorderStatusText`（アプリ画面と共通） |
| `apps/ios/Watch/Complication/ComplicationViews.swift` | WatchWidget / ProtocolTests（WatchApp にも含まれる） | 円形・長方形・インラインのレイアウト（ウィジェットファミリーに依存しない SwiftUI） |
| `apps/ios/Watch/Complication/RecorderStatusPublisher.swift` | WatchApp | `RecorderController` の `phase` / `recordingSince` を Observation で追い、変化したら App Group に書いて `WidgetCenter.reloadTimelines(ofKind:)`（バックグラウンドでの中断・充電にも追従） |
| `apps/ios/WatchWidget/RecorderWidget.swift` | WatchWidget（`app-extension`、watch アプリに埋め込み） | `StaticConfiguration`。ファミリー: `accessoryCircular` / `accessoryRectangular` / `accessoryInline` / `accessoryCorner`。タイムラインは 1 エントリ・`.never`（アプリが状態変化時に再読み込みを要求）、経過時間は `Text(_, style: .timer)` で自動更新 |
| `apps/ios/Tests/ProtocolTests/ComplicationDisplayTests.swift` | ProtocolTests | 対応表・保存・URL のテストと、レイアウトの PNG 書き出し |

### 14.2 表示とタップ

| 録音状態 | 円形 | 長方形（2 行目） | インライン |
| --- | --- | --- | --- |
| 録音中 | 赤 `record.circle` + 「録音中」 | 「経過 h:mm:ss」 | 「録音中」 |
| 一時停止（理由あり） | 橙 `pause.circle` + 「一時停止」 | 「充電中・タップで再開」など（充電中 / 電池不足 / 容量不足 / 通話等で中断 / エラー） | 「一時停止（充電中）」 |
| 停止中 | 灰 `mic.slash` + 「停止中」 | 「タップで録音開始」 | 「録音停止中」 |

- タップは全状態で `widgetURL(watchaudio://record)`。アプリの `onOpenURL` が `RecorderController.start()` を呼ぶ（録音中なら何もしない＝アプリが開くだけ、一時停止中・停止中なら開始。充電中などは従来どおり `paused` のまま理由を表示）。前面に来たときの自動再開（`appBecameActive`）とは競合しない（`start` 側で二重開始を防いでいる）。
- 状態はアプリが書いた最後の値。アプリが OS に終了された場合、次に起動するまで表示は古いまま（録音中の表示が残り得る）。次回起動時に `terminated` の確定と同時に正しい状態に戻る。
- Ultra のアクションボタン対応は未実装（完了条件外）。

### 14.3 App Group と署名

- `Configuration/Config.xcconfig` の `APP_GROUP_ID`（既定 `group.$(BUNDLE_ID_PREFIX)`）を、両ターゲットの entitlement（`com.apple.security.application-groups`）と Info.plist の `WatchAudioAppGroup` に入れる。コードは Info.plist から読む。
- **実機**: Apple Developer で App Group（例 `group.<自分の BUNDLE_ID_PREFIX>`）を作り、watch アプリ（`…watchkitapp`）とウィジェット（`…watchkitapp.widget`）の App ID に割り当てる。別の ID を使うときは `Configuration/Local.xcconfig` で `APP_GROUP_ID` を上書きする。entitlement が無いとアプリとウィジェットで値が共有されず、コンプリケーションは常に「停止中」になる。
- **CI**（`CODE_SIGNING_ALLOWED=NO`）: entitlement は適用されないが、ビルドは通る。シミュレータでは App Group の代わりにアプリ自身の領域の suite に書かれる（タップ経路とアプリ側の書き込みの確認には十分）。

### 14.4 検証状況

| 項目 | 方法 | 状況 |
| --- | --- | --- |
| 状態 → 表示の対応、保存の往復、タップ URL の判定 | `ComplicationDisplayTests`（`ProtocolTests`、iOS シミュレータ） | CI で確認 |
| 表示（録音中・一時停止・停止中） | 同テストの `testRendersPreviewImages` が各レイアウトを `ImageRenderer` で PNG に書き出す → 成果物 `watch-complication-previews`（`complication-recording.png` / `-paused-charging.png` / `-paused-interruption.png` / `-stopped.png` / `-all.png`）。文字盤へのコンプリケーション追加はコマンドラインから操作できないため、この描画を証跡とする | CI で確認 |
| ウィジェット拡張の埋め込みとタップ経路 | CI ステップ「watchOS complication tap on an Apple Watch simulator」（`.github/scripts/watch-complication-smoke.sh`）: `WatchAudio.app/PlugIns/WatchAudioWidget.appex`（`com.apple.widgetkit-extension`）の存在を確認 → 通常起動（停止中）のスクリーンショット → `simctl openurl watchaudio://record`（watchOS シミュレータはアプリの URL スキームを開けず LSApplicationWorkspaceErrorDomain 115 になるため、その場合は **Debug 限定**の起動引数 `-complicationURL watchaudio://record` で `onOpenURL` と同じ処理に URL を渡す）→ 録音開始（またはその理由付きの不可）が診断ログに記録されることを確認し、スクリーンショットとアプリが書いた共有状態を成果物 `simulator-smoke` に残す（`watchos-complication-*.png` / `-diagnostics.csv` / `-defaults.txt`）。タップ後のスクリーンショットは初回開始時の通知許可ダイアログが前面に出る（simctl では通知を事前許可できない）ため、開始の判定は診断ログの `start` と共有状態 `{"state":"recording",…}` で行う | CI で確認（[run 38113587458](https://github.com/Soichiro5/watch-audio/actions/runs/38113587458)） |
| 実機の文字盤での表示、システムからの `widgetURL` の受け渡し、中断後の 1 タップ再開 | 実機のみ（§11 の 13、#60） | 要確認 |


## 15. 意味検索（#24、#178）

iPhone でも Android と**同じモデル（ruri-v3-30m int8）・同じ共通コード**で意味検索が動く（#178）。共通コード（`shared/pipeline` の `UnigramTokenizer`・`EmbeddingPassages`・`Int8Vectors`・`VectorCache`・`SemanticIndex`・`HybridSearch`・`EmbeddingStep`・`ModelInstaller`、`shared/platform` の `PositionalFile`（iOS は POSIX の `pread` / `pwrite`））はそのままで、iOS に固有なのは埋め込みモデルの実行（ONNX Runtime）とモデルのダウンロード（`URLSession`）の 2 つだけ。検索・埋め込みの段・キャッシュの仕組みは [02 §6.3「埋め込み」](../architecture/02-architecture.md#埋め込み意味検索24) と [§6.4](../architecture/02-architecture.md#64-検索)。

### 15.1 構成

| 場所 | ファイル | 役割 |
| --- | --- | --- |
| iOS (Swift) | `apps/ios/App/Embedding/EmbeddingPlatform.swift` | Kotlin の `IosEmbeddingPlatform` の実装。`OrtEncoderSession` が ONNX Runtime の Objective-C API（`ORTSession`、CPU、intra-op 1 スレッド）で `input_ids` / `attention_mask` → `last_hidden_state` を計算する |
| 〃 | `apps/ios/App/Embedding/ModelDownload.swift` | モデルのファイル 1 つのダウンロード（`URLSession`、Wi-Fi のみ、HTTP の範囲指定で続きから）。**iPhone アプリがインターネットにつなぐ唯一の箇所** |
| iOS (Kotlin) | `shared/ui/src/iosMain/.../IosEmbeddingPlatform.kt` | Swift に実装させるインターフェース（`openEncoder`、`download`、`runtimeVersion`） |
| 〃 | `IosEmbedding.kt` | `OnnxEmbedder`（共通）に Swift のセッションを渡す `IosEncoderRuntime`（`LongArray` ↔ `NSData`）、`ModelFetcher` の実装 `IosModelFetcher`、検索タブが状態を聞いたときにダウンロードを始める `IosSemanticSearch` |
| 〃 | `IosAppGraph.kt` | `ModelInstaller`（`RuriV3.FILES`）・`SemanticIndex`・`EmbeddingStep` を作り、`TranscriptionRun`・`IosAppServices.semanticSearch`・エージェント連携の道具に渡す |
| 〃 | `IosEmbeddingCheck.kt` | シミュレータ検査用: アプリの `IosEmbeddingPlatform` で、参照ベクトルとの一致・言い換えの検索・ダウンロードの再開を一時ディレクトリで確かめる（`EmbeddingTests` が使う） |
| 共通 | `shared/pipeline/.../embedding/ModelInstaller.kt` | ダウンロードの手順（`.part` に取得 → 大きさと SHA-256 を確認 → 原子的に移動、失敗後 60 秒は再試行しない、状態の Flow）。`ModelInstallerTest`（`jvmTest` / `iosSimulatorArm64Test`） |
| 共通 | `RuriV3.FILES`（`Embedder.kt`） | 両スマホが取得するファイル（URL はコミット固定、大きさ、SHA-256）。Android の `ModelManifest.embeddingFiles` も、テスト用のスクリプト（`tools/harness/download-models.sh`、`.github/scripts/ios-embedding-model.sh`）もこれと一致させる（`RuriFilesScriptTest`） |
| テスト | `apps/ios/Tests/EmbeddingTests/EmbeddingTests.swift` | アプリをホストにした XCTest（§15.5） |

### 15.2 埋め込みモデルの実行: 方式の比較と選択

| 案 | 内容 | アプリのサイズ | 速度 | 保守 | JVM のベクトルとの一致 |
| --- | --- | --- | --- | --- | --- |
| **A. ONNX Runtime（SPM）を Swift で包む（採用）** | Microsoft の公式パッケージ `onnxruntime-swift-package-manager`（MIT）を `exactVersion: 1.24.2` で固定し、その Objective-C API を Swift から呼ぶ。Kotlin の `EncoderRuntime` に注入する（文字起こしの `IosSpeechTranscriber` と同じ形、§6） | 増える（フル版の CPU 実行環境の静的ライブラリ。Release のバンドルで +30.7 MB、§15.5） | CPU（int8、1 スレッド）。シミュレータで passage 1 件 5〜9 ms、読み込み 1.3〜1.7 秒（§15.5）。実機は #60 | フォークなし。上流の版を上げるときは `project.yml` の版を変えるだけ。モデル・トークナイザ・前後処理は Android と共通コード | **同じモデルファイル・同じ前後処理**。違いは CPU ごとの int8 演算（x86 の AVX2 / VNNI と ARM）だけ。JVM の参照とのコサインは最小 0.998662・平均 0.999524（§15.5） |
| B. ONNX Runtime の C API を cinterop | `onnxruntime_c_api.h` を Kotlin/Native から呼ぶ（Android の `shared/ort` と同じ関数表） | A と同じ | A と同じ | ヘッダの同梱・cinterop・静的リンクの設定を自前で持ち、macOS の CI でしか確かめられない。SPM のパッケージは cinterop から直接は使えない | A と同じ |
| C. Core ML に変換 | coremltools で ModernBERT（ruri-v3）を `.mlpackage` に変換し、Neural Engine で動かす | 実行環境は OS にあるので小さい | 速い・電池に優しい可能性 | **変換したモデルの置き場が要る**（このプロジェクトは上流のファイルをそのまま取得する方針。変換の手順と変換物の配布を自前で保守することになる）。局所・大域アテンションの変換が通るかも未確認 | **別のモデル**になる（int8 の動的量子化は変換されず fp16 になる）。Android と同じベクトルにならず、端末の間で検索結果が揃わない。一致の確認も別に要る |
| D. `NLContextualEmbedding`（Apple） | OS のトークン単位の埋め込みを平均する | 増えない | 速い | ライブラリもダウンロードも無い | 別のモデル（検索用に学習されていない）で、品質が未評価（M0-6 §6.3） |

**A を採用**した。フォークも変換物の保守もせず、上流のパッケージ（Microsoft の公式、MIT）と上流のモデルファイルをそのまま使える。Android と同じファイル・同じ共通コードなので、品質の評価（[m0-6 §9.2](../research/m0-6-embedding-desktop.md#92-検索品質キーワードのみ-と-ハイブリッド上位-5-件)）・`tools/harness` の評価がそのまま当てはまり、埋め込みモデルの id（`ruri-v3-30m-int8@1b41a27`）も同じ。代償はアプリのサイズで、気になるようなら B（同じランタイム）でも変わらず、C は保守の方針に合わない。ライセンスは MIT（ライセンス画面の「ONNX Runtime for iOS」、`OpenSourceNotices`）。パッケージはバイナリ（`pod-archive-onnxruntime-c-1.24.2.zip`、SHA-256 は上流の `Package.swift` が固定）を `download.onnxruntime.ai` から取るが、これはビルド時だけで、アプリは通信しない。

### 15.3 モデルのダウンロード

- **いつ**: 検索タブを初めて開いたとき（共通の `SearchViewModel` が `semanticStatus` を聞く → `IosSemanticSearch.status()` → `ModelInstaller.requestDownload()`）。Android と同じく、検索しない人は何もダウンロードしない。失敗したら、検索タブを開いている間に 60 秒おきに再試行する。
- **何を**: `RuriV3.FILES`（Android と同じ 2 ファイル、計 約 44 MB）。URL は Hugging Face のコミット固定（`onnx-community/ruri-v3-30m-ONNX@1b41a27`、`cl-nagoya/ruri-v3-30m@24899e5`）。
- **Wi-Fi のみ**: `ModelDownload` の `URLSessionConfiguration.ephemeral`（Cookie・キャッシュを残さない）で `allowsCellularAccess = false`・`allowsExpensiveNetworkAccess = false`（iPhone のテザリングなど）・`allowsConstrainedNetworkAccess = false`（省データモード）。条件に合う通信が無い間は `waitsForConnectivity` で待ち、その間は検索画面に「意味で探すためのモデルは Wi-Fi に接続したときにダウンロードします」と出る（`NWPathMonitor` は使わず、`URLSession` に任せる）。
- **続きから**: ファイルは `<名前>.part` に書き、中断（アプリが裏に回る・通信が切れる）しても次の試行で `Range: bytes=<既存の長さ>-` で続きを取る。Hugging Face は CDN へリダイレクトするので、リダイレクト先にも範囲を付け直す（HTTPS 以外へは従わない）。サーバーが全体を返したら最初から書き直す。
- **検証**: 取り終えたら大きさと SHA-256（`sha256Of`、共通）を確かめてから本来の名前に原子的に移す。違えば `.part` を消して最初から。
- **進み具合**: 状態（`ModelState.Downloading`）を検索画面の「意味で探すためのモデルをダウンロード中 N%」に出す。取り終えると、受信済みの文字起こしの埋め込みがすぐ始まる（`requestTranscription()`）。
- **置き場**: `Application Support/WatchAudio/models/ruri-v3-30m/`。`LocalDataDirectory` でバックアップ対象外・保護クラス `CompleteUntilFirstUserAuthentication`（§8、[02 §7.1](../architecture/02-architecture.md#71-保存場所と保護76)）。ベクトルのキャッシュは `search/`（同じ扱い）。「すべてのデータを削除」でもモデルは消さない（Android と同じ）。
- **バックグラウンドの `URLSession` は使わない**: 約 44 MB で、Wi-Fi なら画面を開いている間に終わる。裏に回って止まっても、次に検索タブを開いたときに続きから取る。
- **検査**: `tools/privacy-check` が `URLSession` を `ModelDownload.swift` だけに許し、そこに上の 3 つの制限・ephemeral・HTTPS だけであることが残っているか、`IosAppGraph.kt` が `RuriV3.FILES` だけを渡しているかを検査する（`ALLOWED_API_REQUIREMENTS`）。URL は Android と同じ許可済みの接頭辞だけ。診断ログには `model_download_start` / `model_download_done` / `model_ready` / `model_download_failed`（`model=embedding`、理由は `http_404`・`url_error_-1009` のような短い記号だけで URL は書かない）。

### 15.4 組み込み

- `IosAppGraph.start(…, embedding: EmbeddingPlatform())` で Swift の実装を受け取る（§6）。
- `TranscriptionRun(embedding = EmbeddingStep(…))`: Android と同じく、文字起こし待ちが無くなってから埋め込む。前面の実行では、電池なら受信から 48 時間以内のチャンクを 1 回 50 件まで、充電中なら 1 回 300 件まで。埋め込みの段が「続きがある」（`RunAgain`）と言えば前面ではそのまま次の実行へ、BGProcessingTask でも期限まで続ける。「古い分は充電中に」（`OnCharge`）なら BGProcessingTask（`requiresExternalPower`）を予約する。
- 日・全データの削除の後は `StorageManager.onDeleted` でベクトルのキャッシュからも消す。
- エージェント連携（§16）の検索も意味検索を使う（モデルが準備できていれば）。

### 15.5 検証

- **CI（ios.yml「Semantic search on the iOS simulator」、`EmbeddingTests`）**: アプリのプロセスで、アプリ自身の `EmbeddingPlatform`（Swift パッケージの ONNX Runtime、`URLSession`）を `IosEmbeddingCheck` 経由で使う。モデルは `.github/scripts/ios-embedding-model.sh` がアプリと同じ固定のファイルを取り、`actions/cache`（スクリプトのハッシュがキー。固定のコミットが変わったときだけ取り直す）に置く。
  1. Android と同じ参照ベクトル（`embedding-reference.json`、JVM が計算、`DeviceEmbeddingFixtureTest`）の 8 文と、コサインで比べる。言い換えのクエリ 4 件が（JVM で 0.05 以上の差がある）正しい会話を 1 位にする。
  2. M0-6 の Python の参照（`tools/harness/testdata/ruri-reference.json`、「うん」・絵文字・半角カナを含む 10 文）とも比べる。
  3. 一時 DB に 4 つの会話を入れ、`EmbeddingStep` と `HybridSearch` で「サーバー代がいくら増えるって言ってた？」（会話と共通の語なし）がその会話を意味で 1 位に見つける。
  4. アプリのダウンロード（`ModelInstaller` + `ModelDownload`）でトークナイザ（6.7 MB）を固定のコミットから取り、2 MB で中断 → `.part` の続きから取り直して SHA-256 を確認して置く。置き場がバックアップ対象外。
- **計測**（[run 38402593169](https://github.com/Soichiro5/watch-audio/actions/runs/38402593169) と、しきい値を下の形にした後の [run 38407251069](https://github.com/Soichiro5/watch-audio/actions/runs/38407251069)（全件成功）で同じ値。macOS 26 ランナーの iPhone シミュレータ = arm64。参照は x86 の Linux で計算。ONNX Runtime 1.24.2）:

  | 参照 | テキスト（コサイン） | 最小 / 平均 |
  | --- | --- | --- |
  | JVM（`embedding-reference.json`） | 沖縄の passage 1.000000、歯医者の passage 1.000000、サーバーの passage 1.000000、札幌の passage **0.998662**、「沖縄旅行の飛行機とホテル」**0.998769**、「歯医者の予約いつだっけ」1.000000、「サーバー代がいくら増えるって言ってた？」0.999313、「年末に札幌の実家へ帰るかもって話」0.999450 | 0.998662 / 0.999524（0.999 以上は 8 件中 6 件） |
  | Python（`ruri-reference.json`） | 「えーと、あのー 来週の木曜…」**0.998910**、「会議の結論どうなった」0.999588、「🍣 寿司 ｶﾀｶﾅ と Wi-Fi」0.999702、ほかの 7 件（「うん」を含む）1.000000 | 0.998910 / 0.999820 |

  順位: 言い換えのクエリ 4 件とも正しい会話が 1 位（2 位との差 0.115〜0.184）。言い換えの検索: 「サーバー代がいくら増えるって言ってた？」が `passage-2` を意味だけで 1 位に見つけた（キーワードの一致なし）。速度（シミュレータ）: モデルの読み込みと最初のクエリ 1.3〜1.7 秒、passage 1 件 5〜9 ms、クエリ 1 件 4〜5 ms。ダウンロード: トークナイザを 2,269,264 バイトで中断 → 続きから（206）取り直して SHA-256 一致、計 1.5 秒、置き場はバックアップ対象外。アプリのサイズ: Release の端末向けビルド（署名なし、`xcodebuild build` のバンドル。シンボルを落とす前）が 50,408 KB → 81,876 KB（**+30.7 MB**、ios.yml の `RELEASE_BUILD_CHECK`、main の [run 38399441244](https://github.com/Soichiro5/watch-audio/actions/runs/38399441244) と比較）。App Store でのダウンロードの大きさは圧縮・シンボル除去後なので小さくなる（TestFlight で確かめる、#60）。
- **しきい値**: Issue の完了条件は「コサイン 0.999 以上」だが、**テキストごとには満たさない**（最小 0.998662）。半分の文は 1.000000 で完全に一致し、ずれるのは一部の文だけなので、モデル・トークナイザ・前後処理の違いではなく、int8 の演算（`DynamicQuantizeLinear` / `MatMulInteger`）の CPU ごとの実装の違い（x86 の AVX2 / VNNI と ARM）による（同じ x86 でも CPU が違う CI のランナーの JVM で、Python の参照に対して文で 0.998、「うん」で 0.994 になった。`EmbedderParityTest`）。そこで `EmbeddingTests` は、リポジトリの CPU 間の基準 `CROSS_MACHINE_COSINE = 0.99`（Android の `EmbeddingDeviceTest`・`EmbedderParityTest` と同じ）を**テキストごと**に、Issue の 0.999 を**平均**に課す。間違ったトークナイザやモデルなら 0.99 を大きく下回る。順位はすべて JVM と同じ。
- 実機（#60）: 速度・電池・メモリ・アプリのサイズ・実際の Wi-Fi でのダウンロード（§11 の 21）。

## 16. エージェント連携（#185）

PC の AI エージェントが、本人が ON にしている間だけ、ペアリングした PC の橋渡し CLI（`tools/agent-bridge`）を通してこの iPhone の文字起こしを読む（方針は [research/agent-access.md](../research/agent-access.md)、共通の仕組みは [02 §6.9](../architecture/02-architecture.md#69-エージェント連携uc11182183184185)、画面は [phone-ui.md §1.13](phone-ui.md#113-エージェント連携184)、Android は [android-phone.md §4.6](android-phone.md#46-エージェント連携184)）。画面（`AgentScreen`）・道具・範囲・量の上限・アクセス履歴・接続の段取り（`AgentConnector`・`pairOver`）は Android と共通で、iOS はつなぎ方と鍵とカメラだけを持つ。

**Android との違い: アプリを画面に出している間だけ答える。** iOS には前景サービスがなく、裏に回ったアプリは数秒で止められて接続を保てない。そこで `IosAppGraph.setAppActive(false)`（`scenePhase` が `.background`: ホーム画面に戻る・別のアプリに切り替える・画面を消す）で **OFF にする**（各 PC に `bye(access_off)` を送って閉じる。送り終えるまでの数秒はバックグラウンドタスクで保つ）。戻っても OFF のまま（本人がもう一度 ON にする）。止まったまま「ON」と表示し続けないため、一時停止ではなく OFF にした。画面には「iPhone では、このアプリを画面に出している間だけ PC に答えます…」（`Strings.AGENT_FOREGROUND_ONLY`、`AgentAccess.foregroundOnly`）を出す。コントロールセンターを引き出したなどの `.inactive` では止めない。自動 OFF（既定 30 分、15 分〜2 時間）は Android と同じで、タイマーで OFF にし、タイマーが遅れても期限後の呼び出しには答えない（`AgentConnector` の `allowed`）。

| 部分 | ファイル | 内容 |
| --- | --- | --- |
| 状態と段取り（Kotlin） | `shared/ui/src/iosMain/.../IosAgentAccess.kt` | `AgentAccess` の実装。ON の間 `AgentConnector` を `Dispatchers.Default` で動かす（道具の実行をメインスレッドに載せない）、期限のタイマー、画面から外れたら OFF、ローカルネットワークの拒否の表示（`AgentAccessState.localNetworkDenied`）。音声の切り出し `IosAudioClipSource` も同じファイル |
| 接続の規則（Kotlin） | `IosAgentTransport.kt` | `AgentTransport` の実装。宛先は LAN の IP リテラルだけ（`isLanHost`）、フレーム（4 バイトの長さ + JSON）の読み書き、鍵と証明書の用意（`SelfSignedCertificate` で作り Keychain の鍵で署名） |
| Swift へのつなぎ（Kotlin） | `IosAgentPlatform.kt` | Swift が実装するコールバック型のインターフェース（`IosSpeechTranscriber` と同じ形）と結果のクラス |
| 鍵（Swift） | `apps/ios/App/Agent/AgentKeychain.swift` | Keychain の EC P-256 の秘密鍵（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`、iCloud キーチェーンにも端末のバックアップにも乗らない、取り出さない）と自己署名の証明書、両者を組にした `SecIdentity`。鍵の指紋（SPKI の SHA-256、base64url）の計算 |
| 通信（Swift） | `AgentNetwork.swift` | Network.framework。`NWConnection` の相互 TLS 1.3（最小・最大とも 1.3、`sec_protocol_options_set_local_identity` で自分の鍵、`sec_protocol_options_set_verify_block` で PC の鍵の指紋だけを通す。認証局もホスト名も使わない）。宛先は `IPv4Address(host)` / `IPv6Address(host)` で解釈できる IP リテラルだけで、DNS を引かない。mDNS は `NWBrowser`（`_watchaudio-mcp._tcp` を探すだけで告知しない）。スマホは待ち受けない（`NWListener` は `tools/privacy-check` が禁止） |
| QR（Swift） | `QrScannerViewController.swift` | カメラの映像から AVFoundation の QR 検出（`AVCaptureMetadataOutput`、`.qr` だけ）で読む。読めたらすぐ止める |
| 音声（Swift） | `AgentAudioClip.swift` | `get_audio_clip`: チャンクの AAC を `AVAudioFile` + `AVAudioConverter` で 16 kHz モノラルに戻して切り出す（最長 30 秒、WAV にするのは `shared/agent`）。音声を許可したペアリングだけ |
| まとめ（Swift） | `AgentPlatform.swift` | 上の 4 つを Kotlin の `IosAgentPlatform` として公開し、`IosAppGraph.start(transcriber:agent:)` に渡す |

- **鍵を Secure Enclave に置かない理由**: シミュレータに Secure Enclave がなく、CI（`ios.yml`）でアプリと同じ経路を確かめられないため。Network.framework に渡すのはどちらにしても Keychain の `SecIdentity`。鍵が守る文字起こしそのものは同じ保護クラス（初回ロック解除後に読める）のファイルにあるので、Keychain の `ThisDeviceOnly` で守りの水準は揃う。鍵が消えれば（アプリの削除後の再インストールで Keychain が残らない場合など）作り直し、ペアリングもやり直しになる。
- **QR を VisionKit の `DataScannerViewController` で読まない理由**: Apple のドキュメントは DataScanner の処理が端末内で完結するかを明記していない（VisionKit は Live Text や「画像を調べる」も含む枠組み）。AVFoundation の QR 検出はカメラの取り込みの仕組みに入っている端末内の機能で、映像はどこにも保存・送信しない。A12 以降という DataScanner の条件も要らない。カメラの許可（`NSCameraUsageDescription`）は「カメラで QR コードを読み取る」を押したときにだけ求める。拒否されていれば「カメラを使えません…」と出し、ターミナルの文字列の入力（共通の画面にある）を案内する。シミュレータにはカメラがないので、CI は文字列の入力の経路で確かめる。
- **ローカルネットワークの許可**: 最初に LAN の PC へつなぐとき（ペアリング、または ON のとき）と mDNS の探索で iOS が尋ねる（`NSLocalNetworkUsageDescription`、`NSBonjourServices: [_watchaudio-mcp._tcp]`）。iOS には許可の状態を前もって調べる API がないので、接続が拒否された（`NWPath.UnsatisfiedReason.localNetworkDenied`、または `kDNSServiceErr_PolicyDenied`）ときに、ペアリング中なら「ローカルネットワークへのアクセスが許可されていないため…設定アプリの「プライバシーとセキュリティ」→「ローカルネットワーク」で watch-audio を許可してください」、ON の間なら同じ文をスイッチの下に出す。
- **mDNS で見つけた PC のアドレス**: `NWBrowser` の結果（サービスの名前と TXT の `n`・`h`）を、UDP の `NWConnection` で名前の解決だけさせて IPv4 のアドレスとポートにする（UDP は接続の準備で何も送らないので、PC には何も届かない）。TCP で解決すると PC の CLI が「ペアリングしていない相手の接続を拒否しました」と記録するため使わない。見つからなければ前回のアドレス（ペアリング時は QR のアドレス）へつなぐ。どちらでも最後に鍵の指紋で本物か確かめる。
- **端末名**: ペアリングのとき PC に `UIDevice.model`（「iPhone」）を名乗る。利用者が付けた端末名は送らない。
- **アクセス履歴の保持**: 90 日より前を消す（起動時の `IosAppGraph.start` と画面を開いたとき）。
- **意味検索**: iOS には埋め込みモデルがまだない（§15）ので、`search_transcripts` はキーワード検索だけ（道具の答えにもそう書かれる）。

**検証**:

- 画面の文言（iOS の表示: 前面にある間だけの注意・カメラのボタン・ローカルネットワークの案内）: `AppUiTest.agentAccessOnIos`、`AgentViewModelTest.iosWording`（`jvmTest`）。
- Kotlin の iosMain: Linux の klib クロスコンパイル。
- 実際の接続（CI `ios.yml` の「Agent access on the iOS simulator」、`.github/scripts/ios-agent-access.sh`）: CI のホストで本物の CLI（`tools/agent-bridge/e2e/emulator-host.ts`）を動かし、アプリをホストにした XCTest（`apps/ios/Tests/AgentAccessTests`）がアプリの `IosAgentAccess`（Keychain の鍵、Network.framework、共通の接続と道具）を画面と同じ呼び方で動かす。シミュレータはホストのネットワークを共有するので、CLI には `127.0.0.1` でつなぐ。他のステップは未署名（`CODE_SIGNING_ALLOWED=NO`）でビルドするが、シミュレータは未署名のアプリに Keychain を使わせない（`errSecMissingEntitlement`、-34018）ので、このステップだけ ad hoc 署名し、スクリプトがその場で書く Keychain のアクセスグループ（`keychain-access-groups`）を付ける（CI だけ。リポジトリに entitlements は置かない。署名した実機のアプリは自分のアプリ ID がアクセスグループになるので追加の entitlement は要らない）。(1) `testPair`: 文字起こしを入れ、CLI が出した文字列でペアリング、6 桁の確認コードが CLI と一致（スクリプトが比べる）、範囲の既定はテキストのみ。(2) `testServe`: ON → PC のエージェントの `search_transcripts` に答える → OFF で答えない → ON → **アプリが画面から外れると OFF**（戻っても OFF）→ ON → 取り消しで答えない。CLI 側は「答える / 答えない」の 6 回の切り替わりを順に確かめ、外向きの接続がないこと（no-egress のガード）も確かめる。`get_audio_clip` は音声を許可したペアリングの写しで WAV が返ることを確かめる（本物のペアリングの既定はテキストのみなので、CLI からの呼び出しは拒否される）。mDNS は CLI に告知させ、シミュレータの `NWBrowser` で見えたか（`AGENT_CHECK phase=mdns`）を記録する（合否には使わない。GitHub の macOS ランナーのシミュレータでは 10 秒で何も見えなかった（`seen=0`）ので、接続は前回のアドレス（ペアリング時の 127.0.0.1 + CLI のポート）の経路で確かめている。mDNS の経路は実機で確かめる）。
- Kotlin 側の規則: `IosAgentTransportTest`（`shared/ui` の iosTest、CI の「Shared tests (iOS simulator)」）が、偽の Swift 側で、証明書を Keychain の鍵に 1 回だけ作ること・LAN の IP リテラル以外を Swift に渡さないこと（`8.8.8.8`・ホスト名は拒否、`[fe80::1]` は括弧を外して渡す）・ローカルネットワークの拒否を知らせること・フレームの往復を確かめる。
- 実機でのみ確かめること（#60）: 下の「実機チェックリスト」20。
