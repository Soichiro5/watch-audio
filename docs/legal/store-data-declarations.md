# ストア申告の下書き（App Store・Google Play）

> **下書きです。運営者と法律の専門家のレビュー前で、申請フォームの項目名・要件は申請時点の App Store Connect / Play Console の表示で確認し直す必要があります。**
> プライバシーポリシー（[日本語](privacy-policy.ja.md) / [英語](privacy-policy.en.md)）と同じ内容になるように書いています。ポリシーと申告は必ず同時に更新してください（#36 の完了条件「ポリシー・ストアのデータ申告が『データは端末内のみ』と一致している」）。
> 内容は 2026-10-12 時点の `main`（5681119）の実装に基づきます。根拠のコード箇所は末尾の「[レビュー用メモ: 主張と根拠](#5-レビュー用メモ-主張と根拠)」にまとめています。

## 1. 申告の前提

- アプリは録音・文字起こし・その他のユーザーデータを**端末の外へ送らない**。運営者のサーバーはない。解析・広告・クラッシュ送信の SDK はない。アカウントもない。
- 端末外への通信は、Android スマホの文字起こしモデルのダウンロード（Hugging Face・GitHub の公開ファイル）と、iOS が行う音声認識モデルのダウンロード（OS 管理）だけ。ユーザーデータは送らない。
- Watch → スマホの転送は、ユーザー自身の端末間の直接通信（Wear OS: 近くで直接つながったノードだけ、Apple: WatchConnectivity）。開発者には届かない。
- ユーザーが自分で端末外に出せるのは、診断ログの書き出し（OS の共有シートで送り先をユーザーが選ぶ）だけ。データの書き出し（#155）・暗号化バックアップ（#40）・課金（#34）・AI 機能（P2）は未公開。公開時にこの文書とポリシーを更新する。

## 2. App Store

### 2.1 App のプライバシー（App Privacy ラベル）

| 質問 | 回答案 | 理由 |
| --- | --- | --- |
| このアプリからデータを収集しますか | **いいえ（データの収集なし / Data Not Collected）** | Apple の定義の「収集」は、アプリから端末の外へ送られ、開発者や第三者が一定期間以上アクセスできる状態になること。録音・文字起こし・診断ログは端末内にだけ保存し、開発者は受け取らない |
| トラッキング | なし（App Tracking Transparency の許可も求めない） | 広告・解析 SDK がなく、端末外に送るデータがない |
| サードパーティのパートナー（SDK）が集めるデータ | なし | Swift パッケージ・外部 SDK は使っていない。Kotlin 側も実行時の依存は許可リストで検査している |
| プライバシーポリシーの URL | ［公開 URL］ | 必須。アプリ内からも見られるようにする（§4 の残作業） |

要確認（レビューで判断）:

- **Watch → iPhone の転送**: 同じユーザーの端末間で、OS の WatchConnectivity が行う。開発者に届かないので「収集」に当たらない、と整理している。
- **診断ログの書き出し**: ユーザーが共有シートで送り先を選んだときだけ端末外に出る。ユーザーが問い合わせで運営者にメール送付した場合も、メールアプリからユーザーが送るもので、アプリが送信するのではない。そのため「収集なし」のままでよいと考えているが、問い合わせ窓口で診断ログを受け付ける運用にするなら、Apple の「任意の開示（optional disclosure）」の条件に当たるかを確認する。
- **OS のクラッシュ・解析共有**: ユーザーが「App デベロッパと共有」を許可した場合に Apple 経由で届くクラッシュ情報は、Apple が集めるもので、アプリの申告対象ではないと理解している（要確認）。

### 2.2 審査メモ（App Review への説明文）

審査担当者向けの「メモ」欄に英語で書く。日本語の案と英訳をセットで残す。

**バックグラウンドオーディオ（Apple Watch の `UIBackgroundModes: audio`）**

> 本アプリは、ユーザーが Apple Watch の画面で録音を開始すると、Watch を着けている間、画面が消えていても会話を録音し続けます（常時録音のボイスレコーダー）。バックグラウンドオーディオはこの録音の継続にだけ使います。録音はユーザーが Watch のボタンで止めるか、充電中・電池残量低下・空き容量不足・通話などの割り込みで自動的に止まります。録音中は Watch のアプリ画面とコンプリケーションに録音中であることを表示します。録音した音声はペアリングした iPhone にだけ WatchConnectivity で送られ、iPhone 上（SpeechAnalyzer、オンデバイス）で文字起こしされます。データはどのサーバーにも送られません。

> The app is a continuous voice recorder for Apple Watch. After the user starts recording on the watch, it keeps recording conversations while the watch is worn, including while the screen is off. The audio background mode is used only to continue this recording. Recording stops when the user taps Stop on the watch, or automatically while charging, at low battery, when storage is low, or during interruptions such as calls. While recording, the watch app and its complication show that recording is in progress. Audio is sent only to the user's paired iPhone via WatchConnectivity and is transcribed on the iPhone with SpeechAnalyzer (on-device). No data is sent to any server.

**バックグラウンド処理（iPhone の `UIBackgroundModes: processing`、`BGProcessingTask`）**

> 受け取った録音の文字起こしを、主に充電中に `BGProcessingTask`（識別子 `app.watchaudio.transcribe`）でまとめて行います。通信は行いません。

> Received recordings are transcribed on the device, mainly while charging, in a `BGProcessingTask` (`app.watchaudio.transcribe`). No network access is involved.

**録音についての同意と表示（ガイドライン 2.5.14・5.1.1 への対応）**

> 初回起動時のオンボーディングで「周りの人の声も録音されること」「地域によっては相手の同意が必要なこと」「録音が禁止の場所では止めること」を表示し、チェックを入れてから進みます。録音中は Watch の画面・コンプリケーションで状態を表示します。

> On first launch, the onboarding explains that the voices of people nearby are recorded too, that some regions require the consent of the people being recorded, and that recording must be stopped where it is prohibited; the user must tick a checkbox to continue. While recording, the watch screen and complication show the recording state.

**審査担当者が確認する手順（案）**

1. iPhone アプリを起動し、オンボーディング（同意・通知・音声認識の許可・モデルの準備）を進める
2. Apple Watch アプリで録音を開始し、手首を下ろして画面を消したまま 5〜10 分話す（録音は 5 分ごとに区切って iPhone に送られる）
3. iPhone の「タイムライン」で文字起こしと再生、「検索」でキーワード検索、「状態」タブで受信状況・保持期間・すべてのデータの削除・診断ログの書き出しを確認する

要確認: iPhone アプリの Info.plist に `NSMicrophoneUsageDescription` があるが、iPhone アプリはマイクを使っていない（録音は Watch のみ）。使わない権限の説明文は審査で質問されうるので、削除するか、残す理由を決める（§4 の残作業）。

### 2.3 プライバシーマニフェスト（`PrivacyInfo.xcprivacy`）

現在、iOS・watchOS のターゲットに `PrivacyInfo.xcprivacy` がない。App Store への提出では、「理由の申告が必要な API（Required Reason API）」を使う場合に理由の記載が必要になる。本アプリで該当しうるもの（実際の利用箇所の洗い出しが必要）:

| API の種類 | 使っていそうな箇所 | 理由コードの候補（要確認） |
| --- | --- | --- |
| `UserDefaults` | Watch アプリとコンプリケーションの App Group での状態共有（#41） | App Group 内での読み書き |
| 空き容量 | 状態タブの「端末の空き容量」（#33）、Watch の空き容量不足の判定 | 空き容量の表示・空き不足時の書き込み制御 |
| ファイルのタイムスタンプ | 音声ファイル・診断ログの管理（Kotlin/Native の `kotlinx-io` を含めて確認） | アプリのコンテナ内のファイル |
| システムの起動時刻 | 経過時間の計測に使っていれば | 経過時間の計測 |

マニフェストでは「データ収集なし」「トラッキングなし」と宣言する。追加する際は `tools/privacy-check/policy.py` の iOS の許可リストも見直す。

## 3. Google Play

### 3.1 データセーフティ

| 質問 | 回答案 | 理由 |
| --- | --- | --- |
| 必須のユーザーデータの種類について、アプリでデータを収集または共有していますか | **いいえ** | Google の定義の「収集」は、アプリから端末の外へデータを送ること。録音・文字起こし・診断ログは端末内にだけ保存する。Wear OS → スマホは同じユーザーの端末間で、`Node.isNearby` の直接接続だけを使い、開発者や第三者には届かない |
| （収集・共有がない場合、転送時の暗号化・削除リクエストの質問は表示されない） | — | — |
| 独立したセキュリティ審査（任意） | 受けない | — |

要確認:

- **モデルのダウンロード**: Hugging Face・GitHub への HTTP リクエストで、相手のサーバーには IP アドレスなど通信に必要な情報が届く。送るのは公開ファイルの取得要求だけで、ユーザーデータは送らず、開発者はそのログを受け取らないため「収集・共有なし」と整理している。
- **診断ログの書き出し**: ユーザーが共有シートで選んだ送り先に渡すもので、Google の定義の「ユーザーが開始した操作で、ユーザーが合理的に予期する転送」に当たり、「共有」の対象外と整理している。
- **Android vitals**: ユーザーが Google と使用状況・診断情報の共有を許可している場合に Play Console に届くクラッシュ統計は、Google（OS）が集めるもので、アプリの申告対象ではないと理解している。

### 3.2 前景サービスの申告（Play Console の「前景サービスの権限」）

**Wear OS アプリ: `FOREGROUND_SERVICE_MICROPHONE`（`RecordingService`、`foregroundServiceType="microphone"`）**

- 機能: 常時録音のボイスレコーダー。ユーザーがウォッチのアプリで録音を開始すると（タイル・コンプリケーションはアプリを開いて開始する）、ウォッチを着けている間、画面が消えていても会話を録音し続ける。
- 前景サービスが必要な理由: 録音はユーザーが開始し、画面消灯中も途切れず続く必要がある。録音中は継続中のアクティビティ（Ongoing Activity）と通知で録音中であることを常に表示し、ユーザーはいつでも止められる。充電中・ウォッチを外したとき・電池残量低下・空き容量不足・通話などでは自動的に一時停止する。
- 起動条件: ユーザーの操作（アプリの録音ボタン、またはタイル・コンプリケーションのタップで開いたアプリ）からだけ、アプリが前面にある状態で開始する。再起動後は自動で録音を始めず、「タップで再開」の通知を出す（Android 14 以降、`BOOT_COMPLETED` からマイクの前景サービスは開始できないため）。
- データの扱い: 音声はペアリングしたスマホにだけ直接接続で送られ、スマホの中で文字起こしされる。サーバーには送らない。
- 英文案: "The app is a continuous voice recorder for the watch. After the user starts recording in the app (the tile and complication open the app to start it), it keeps recording conversations while the watch is worn, including while the screen is off. A microphone foreground service is required so that user-started recording continues without interruption with the screen off. While recording, an ongoing activity and notification always show that recording is in progress, and the user can stop it at any time. Recording pauses automatically while charging, when the watch is taken off, at low battery, when storage is low and during calls. Recording is never started automatically after a reboot; the user is asked to tap to resume. Audio is sent only to the user's paired phone over a direct connection and transcribed on the phone; nothing is uploaded to any server."

**Android スマホアプリ: `FOREGROUND_SERVICE_DATA_SYNC`（WorkManager の `SystemForegroundService`、`foregroundServiceType="dataSync"`）**

- 機能: 端末内で文字起こしするための日本語音声認識モデル（数百 MB）のダウンロード。初回の準備時にユーザーが開始する（Wi-Fi 接続時だけに限る設定あり）。設定「英語も文字起こしする」をオンにしたときだけ、英語用のモデル（約 240 MB）も同じ方法でダウンロードする（#81）。
- 前景サービスが必要な理由: ファイルが大きく、アプリを閉じてもダウンロードを続ける必要がある。ダウンロード中は進み具合の通知を表示する。中断しても途中から再開し、SHA-256 で検証する。
- データの扱い: ダウンロードだけで、ユーザーデータは送らない。
- 英文案: "The app downloads the Japanese speech recognition model (several hundred MB) that it needs to transcribe recordings on the device. The user starts the download during setup and can restrict it to Wi-Fi. If the user turns on English transcription, an additional English-capable model (about 240 MB) is downloaded the same way. A data sync foreground service keeps the large download running when the app is closed, with a progress notification shown throughout. The download is resumable and verified with SHA-256. No user data is uploaded."
- 要確認: Android 15 以降の `dataSync` の実行時間の上限（1 日あたり）と、Google が代替として勧める「ユーザーが開始したデータ転送ジョブ」で足りるか。

### 3.3 そのほかの「アプリのコンテンツ」

| 項目 | 回答案 |
| --- | --- |
| プライバシーポリシー | ［公開 URL］（マイク権限を使うアプリは必須） |
| 広告 | 含まない |
| アプリのアクセス権（ログイン） | ログイン不要。審査用の手順（§2.2 と同じ流れ、Wear OS とスマホ）を記載する |
| 対象年齢 | ［運営者が決める］（子ども向けではない） |
| コンテンツのレーティング | 質問票に回答（暴力・課金などなし。ユーザー間のやり取りなし） |
| 健康アプリの申告 | 要確認: Wear OS アプリが `BODY_SENSORS`（Wear OS 4/5 の装着検出だけ、任意）を宣言しているため、健康関連の申告フォームで「健康機能はない」旨と用途を説明する必要があるか確認する |
| 権限の説明 | `RECORD_AUDIO`: 録音のため（Wear OS のみ。スマホはマイクを使わない）。`BODY_SENSORS`: ウォッチを外したことの検知だけ、値は保存しない |
| Wear OS | スマホアプリが必須（`com.google.android.wearable.standalone=false`）。Wear OS の品質ガイドラインの確認が必要（§4） |

### 3.4 Google Play のユーザーデータポリシー（目立つ開示）

端末外に送るデータがないため「目立つ開示と同意」の要件には当たらないと考えているが、初回のオンボーディング（「録音する前に」）で周りの人も録音されることを説明し、チェックで同意を得ている。

## 4. 審査用のスクリーンショット・動画

| 用途 | 内容 | 撮る端末 |
| --- | --- | --- |
| App Store スクリーンショット（iPhone） | タイムライン（1 日の録音と文字起こし）、検索、状態タブ（受信状況・保存データ・プライバシーの表示）、オンボーディングの「録音する前に」 | iPhone（シミュレータで可。App Store Connect が求めるサイズを確認） |
| App Store スクリーンショット（Apple Watch） | 録音中の画面、マーク、停止理由の表示、コンプリケーション | Apple Watch（シミュレータで可） |
| App Review 用の動画（バックグラウンドオーディオ） | Watch で録音開始 → 画面消灯のまま録音が続く → iPhone に届いて文字起こしされる → 停止。録音中の表示が見えること | 実機（Apple Watch + iPhone） |
| Google Play スクリーンショット（スマホ） | タイムライン、検索、状態タブ、オンボーディング | Android スマホ（エミュレータで可） |
| Google Play スクリーンショット（Wear OS） | 録音中の画面、タイル、コンプリケーション（Wear OS の要件どおり、端末の枠なし） | Wear OS（エミュレータで可） |
| 前景サービスの動画（microphone） | ユーザーが録音を開始 → Ongoing Activity / 通知の表示 → 画面消灯でも録音が続く → 停止ボタンで止まる | 実機の Wear OS ウォッチが望ましい（動画は YouTube などの URL で提出） |
| 前景サービスの動画（dataSync） | オンボーディングでモデルのダウンロードを開始 → 進み具合の通知 → 完了 | Android スマホ（エミュレータで可） |

## 5. レビュー用メモ: 主張と根拠

ポリシーの各主張と、実装・検査で確かめた箇所。ポリシー本文にはコードの場所を書かない。

| 主張 | 根拠 |
| --- | --- |
| 解析・広告・クラッシュ送信 SDK、HTTP クライアントのライブラリがない | `tools/privacy-check/policy.py` の `DENIED_DEPENDENCIES` と `ALLOWED_RESOLVED_DEPENDENCIES`（リリースの実行時クラスパスを CI で照合）。`gradle/libs.versions.toml`。iOS は `apps/ios/project.yml` に Swift パッケージなし |
| 通信はモデルのダウンロードだけ | `policy.py` の `ALLOWED_API_USES`（通信 API は `apps/android/src/main/kotlin/app/watchaudio/android/asr/ModelDownloader.kt` だけ）と `ALLOWED_URL_PREFIXES`（`https://huggingface.co/csukuangfj/reazonspeech-k2-v2`、`https://github.com/k2-fsa/sherpa-onnx/releases/download/`、英語用の `https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`）。URL は `ModelManifest.kt`。[docs/architecture/02 §7.2](../architecture/02-architecture.md#72-通信箇所の一覧) |
| iOS のモデルは OS がダウンロード、サーバー型の音声認識は使わない | `apps/ios/App/SpeechTranscription.swift`（`AssetInventory`、`SpeechAnalyzer`）。`policy.py` が `SF…RecognitionRequest` を禁止 |
| Wear OS アプリはインターネットを使わない | `apps/wear/src/main/AndroidManifest.xml` に `INTERNET` なし。`policy.py` の Wear OS の権限の許可リスト |
| Wear OS → スマホは直接接続のノードだけ、`DataClient` は使わない | `apps/wear/.../outbox/OutboxUploader.kt`（`isNearby` のノードだけ）、スマホ側も `apps/android/.../transfer/ChunkReceiver.kt` で `isNearby` を確認。`policy.py` が `DataClient` を禁止。[docs/platforms/wearos.md](../platforms/wearos.md) の手動確認 17 |
| Apple Watch → iPhone は WatchConnectivity | `apps/ios/Watch/Recording/ChunkTransport.swift`（`transferFile` / `transferUserInfo`）、`apps/ios/App/WatchReceiver.swift` |
| 転送が済んだチャンクは Watch から消える | `apps/wear/.../outbox/Outbox.kt`（最終 ACK で削除）、watchOS は [docs/platforms/ios-watchos.md](../platforms/ios-watchos.md) の送信キュー |
| OS バックアップの対象外、Android スマホは端末間の直接転送だけ許可 | `apps/android/src/main/AndroidManifest.xml`（`allowBackup="false"`）、`apps/android/src/main/res/xml/data_extraction_rules.xml`（`cloud-backup` で全ドメイン除外、`device-transfer` は許可）、Wear OS は両方除外。iOS・watchOS は `NSURLIsExcludedFromBackupKey`。[docs/architecture/02 §7.1](../architecture/02-architecture.md#71-保存場所と保護76) |
| iOS・watchOS のファイル保護 | `CompleteUntilFirstUserAuthentication` を `LocalDataDirectory`（shared/ui iosMain）と `LocalDataProtection`（watchOS）で明示。`policy.py` が保護なしの指定を禁止 |
| 診断ログに本文・音声・例外メッセージを含まない、ユーザーの書き出しでだけ外に出る | `shared/pipeline/src/commonMain/kotlin/app/watchaudio/core/pipeline/Diagnostics.kt`（ID・件数・時間・エラーの型だけ、`exportTo`）、`apps/android/.../diag/CrashRecorder.kt`（型と上位フレームだけ）、`apps/android/src/main/AndroidManifest.xml` の `FileProvider`（共有シート用）。Android の `app_start` は `version`・`sdk`・`device=Build.MODEL`、iOS は `version`・`os`。Watch の診断ログ（`apps/wear/.../diag/DiagnosticsLog.kt`、`apps/ios/Watch/Recording/DiagnosticsLog.swift`）は電池残量・充電状態も持つが、アプリからの書き出し機能はない |
| 識別子はランダム | `deviceId` は Wear OS がインストールごとの `UUID.randomUUID()`（`apps/wear/.../WearPrefs.kt`）、watchOS も `UUID`（`RecorderController.swift`）。チャンク・セッションも UUID |
| 保持期間（7/30/90 日・無期限、既定は無期限、文字起こし済みの音声だけ削除） | `shared/pipeline/.../AudioRetention.kt`、文言は `shared/ui/.../Strings.kt` |
| 日ごとの削除・すべてのデータの削除 | `shared/pipeline/.../StorageManager.kt`（`deleteAll` は全チャンク・音声を消し DB を `VACUUM`）、UI は `shared/ui/.../ui/StorageCard.kt`。**設定と診断ログは全削除の対象外**（ポリシーでは「すべてのデータ（録音・音声・文字起こし）」と書き、アンインストールで診断ログも消えると書いた） |
| 権限 | `apps/android/src/main/AndroidManifest.xml`、`apps/wear/src/main/AndroidManifest.xml`、`apps/ios/project.yml` の Info.plist。用途は `policy.py` の各権限の理由。iPhone の通知・音声認識の要求は `shared/ui/src/iosMain/.../IosPermissions.kt`、Watch の通知は `RecorderController.swift` |
| 身体センサーは装着検出だけ | `apps/wear/.../recording/ConditionMonitors.kt`（`TYPE_LOW_LATENCY_OFFBODY_DETECT`）。watchOS には手首検出 API がなく使っていない（[docs/platforms/ios-watchos.md](../platforms/ios-watchos.md)） |
| 通知はローカルだけ | リモート通知（APNs・FCM）の登録なし。`IosGapAlerts.kt`（予約通知）、Android の途絶アラートは WorkManager |
| 初回の同意画面・録音中の表示 | `Strings.kt` の `CONSENT_*`（オンボーディング #32）、Wear OS の Ongoing Activity・コンプリケーション・タイル（[docs/platforms/wearos.md](../platforms/wearos.md)）、watchOS の画面とコンプリケーション（#41） |

## 6. 運営者に決めてもらうこと（未確定事項）

1. 運営者名（個人名か屋号か）と連絡先（公開するメールアドレスまたはフォーム）。公開リポジトリには書かないため、公開ページを作るときに記入する
2. 施行日
3. ポリシーと LP の公開場所（独自ドメイン / GitHub Pages など）。アプリ内からポリシーへのリンク（App Store の要件）を置くため URL を先に決める
4. 対象年齢の方針（例: 16 歳未満は保護者の同意）と、問い合わせでいただく情報（メール・診断ログ）の保存期間
5. 課金（#34）の方針。決まったら、購入情報は App Store / Google Play が扱い開発者のサーバーは使わない旨をポリシーに追記する
6. 英語版の位置づけ（日本語を正とする前提で書いた）と、配信する国・地域（法令の確認範囲が変わる）
7. 法律の専門家のレビューを受けるか、受けるなら範囲（録音の告知・個人情報保護法の扱い・利用規約の免責）

## 7. #36 の残作業チェックリスト

**運営者の作業**

- [ ] §6 の未確定事項を決め、ポリシーの `［ ］` を記入する
- [ ] 法律の専門家によるポリシー・利用規約・録音の注意文のレビュー
- [ ] Apple Developer Program・Google Play Console のアカウントと、ストアの掲載情報（アプリ名・説明・カテゴリ・サポート URL）の最終確認
- [ ] 実機での審査用動画の撮影（Apple Watch の画面消灯中の録音、Wear OS の前景サービス）
- [ ] App Store Connect の App Privacy・審査メモ、Play Console のデータセーフティ・前景サービスの申告・アプリのコンテンツへの入力と申請

**Claude の作業（運営者の了承を得て Issue 化して進める）**

- [ ] 利用規約（日本語・英語）の下書き（録音の責任、無保証、データ消失時の免責、端末内保存のため運営者は復元できないこと）
- [ ] ランディングページ（ポリシー・利用規約・サポートへのリンク）の下書き
- [ ] アプリ内からプライバシーポリシーを開くリンク（状態タブの「プライバシー」）。URL リテラルを足すときは `tools/privacy-check/policy.py` の許可リストも更新する
- [ ] iOS・watchOS の `PrivacyInfo.xcprivacy` の追加（§2.3。理由が必要な API の洗い出し）
- [ ] iPhone アプリの使っていない `NSMicrophoneUsageDescription` の扱いを決める（§2.2）
- [ ] 「すべてのデータを削除」で診断ログも消すかを決める（現在は残る。ID と時刻だけだが、録音した時刻の記録にはなる）
- [ ] シミュレータ・エミュレータでのスクリーンショット作成（§4）
- [ ] Wear OS の品質ガイドラインとの照合
- [ ] 書き出し（#155）・暗号化バックアップ（#40）・課金（#34）を公開するときに、ポリシーとこの申告を更新する
