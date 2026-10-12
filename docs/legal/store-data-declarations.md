# ストア申告の下書き（App Store・Google Play）

> **下書きです。運営者と法律の専門家のレビュー前で、申請フォームの項目名・要件は申請時点の App Store Connect / Play Console の表示で確認し直す必要があります。**
> プライバシーポリシー（[日本語](privacy-policy.ja.md) / [英語](privacy-policy.en.md)）と同じ内容になるように書いています。ポリシーと申告は必ず同時に更新してください（#36 の完了条件「ポリシー・ストアのデータ申告が『データは端末内のみ』と一致している」）。
> 内容は 2026-10-12 時点の `main`（5681119）の実装に基づきます（プライバシーマニフェスト・iPhone のマイク・全削除の範囲・Google Play の確認は #159 で更新）。根拠のコード箇所は末尾の「[レビュー用メモ: 主張と根拠](#5-レビュー用メモ-主張と根拠)」にまとめています。

## 1. 申告の前提

- アプリは録音・文字起こし・その他のユーザーデータを**端末の外へ送らない**。運営者のサーバーはない。解析・広告・クラッシュ送信の SDK はない。アカウントもない。
- 端末外への通信は、Android スマホの文字起こしモデルのダウンロード（Hugging Face・GitHub の公開ファイル）と、iOS が行う音声認識モデルのダウンロード（OS 管理）だけ。ユーザーデータは送らない。
- Watch → スマホの転送は、ユーザー自身の端末間の直接通信（Wear OS: 近くで直接つながったノードだけ、Apple: WatchConnectivity）。開発者には届かない。
- ユーザーが自分で端末外に出せるのは、診断ログの書き出し（OS の共有シートで送り先をユーザーが選ぶ）、データの書き出し（#155。文字起こし・マーク・ギャップと任意で音声を ZIP にして、Android は SAF の保存ダイアログ、iOS は共有シートでユーザーが選んだ保存先にだけ書く）と、暗号化バックアップ（#40。同じ保存先の選び方で、ユーザーのパスフレーズで暗号化したファイルを書く。復元はユーザーが選んだファイルを読むだけ）だけ。パスフレーズと鍵は端末の外に出さない。課金（#34）・AI 機能（P2）は未公開。公開時にこの文書とポリシーを更新する。

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
- **データの書き出し（#155）**: 診断ログと同じく、ユーザーが自分で選んだ保存先（共有シート）に、ユーザーの操作でだけ書き出す。開発者には届かないので「収集なし」のままと整理している。
- **暗号化バックアップ（#40）**: データの書き出しと同じく、ユーザーが選んだ保存先にユーザーの操作でだけ書き出す（中身は暗号化済み）。開発者には届かないので「収集なし」のままと整理している。
- **暗号化の輸出コンプライアンス（App Store Connect の「暗号化の書類」、Info.plist の `ITSAppUsesNonExemptEncryption`）**: 暗号化バックアップは、標準の暗号（AES-256・HMAC-SHA-256・PBKDF2）を OS の API（iOS は CommonCrypto と Security、Android は `javax.crypto`）で使い、ユーザーのデータの保護（パスフレーズによるファイルの暗号化）にだけ使う。独自の暗号アルゴリズムはない。通信の暗号化（HTTPS）は OS のものだけ。米国の輸出規則では「標準の暗号を OS 経由で使う」アプリは多くの場合免除（Apple の質問では「免除の対象となる暗号のみ」）に当たるが、**データの暗号化（通信以外）が目的の場合の扱いは運営者の確認が必要**（Apple の「輸出コンプライアンス」の質問に答える、必要なら年次の自己分類報告）。決まったら `apps/ios/project.yml` の Info.plist に `ITSAppUsesNonExemptEncryption` を設定する（未設定だと申請のたびに質問される）。Google Play には同様の申告はないが、配信国の規制は運営者が確認する。
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
3. iPhone の「タイムライン」で文字起こしと再生、「検索」でキーワード検索、「状態」タブで受信状況・保持期間・すべてのデータの削除・データの書き出し・暗号化バックアップ・診断ログの書き出しを確認する

iPhone アプリの `NSMicrophoneUsageDescription` は削除した（#159）。iPhone アプリはマイクを使わない（録音は Watch のみ）: `apps/ios/App` と `shared/ui/src/iosMain` に `AVAudioRecorder`・`AVAudioEngine` の入力・録音の許可の要求はなく、`AVAudioSession` は再生用の `AVAudioSessionCategoryPlayback`（`IosAppServices.kt`）だけ、文字起こしは受け取ったファイルを `AVAudioFile` で読む（`SpeechTranscription.swift`）。iPhone が要求する許可は通知と音声認識だけ（`IosPermissions.kt`）。Watch アプリの `NSMicrophoneUsageDescription` は録音に必要なので残す。ios.yml の Release ジョブが、iPhone アプリの Info.plist にこのキーがないことを `IPHONE_MICROPHONE_CHECK` で確かめる。

### 2.3 プライバシーマニフェスト（`PrivacyInfo.xcprivacy`）

バンドルごとに 1 つ入れた（#159）。`apps/ios/project.yml` で各ターゲットの `sources` に `buildPhase: resources` で指定し、バンドルの直下にコピーする。

| ファイル | 入るバンドル |
| --- | --- |
| `apps/ios/Privacy/App/PrivacyInfo.xcprivacy` | iPhone アプリ（`iosApp`）。Kotlin の `ComposeApp` フレームワーク（Compose Multiplatform・skiko・SQLite を含む）は静的にリンクされるので、その分もここで申告する |
| `apps/ios/Privacy/Watch/PrivacyInfo.xcprivacy` | Apple Watch アプリ（`WatchApp`） |
| `apps/ios/Privacy/WatchWidget/PrivacyInfo.xcprivacy` | コンプリケーションの拡張（`WatchWidget`） |

3 つとも `NSPrivacyTracking` は `false`、`NSPrivacyTrackingDomains` と `NSPrivacyCollectedDataTypes` は空（データを端末の外へ送らないため、§2.1 の「データの収集なし」と一致）。

**理由の申告が必要な API と理由コード**（理由コードの定義は Apple の [NSPrivacyAccessedAPITypeReasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)）:

| ターゲット | 区分 | 使っている箇所 | 理由コード |
| --- | --- | --- | --- |
| iosApp | ファイルのタイムスタンプ（`FileTimestamp`） | 同梱の SQLite（`androidx.sqlite:sqlite-bundled`）が DB ファイルに `stat` / `fstat` / `lstat`、skiko が `stat` / `fstat` を使う。どちらもアプリのコンテナ（とアプリのバンドル）内のファイルの大きさなどの属性 | `C617.1`（アプリ・App Group のコンテナ内のファイルの属性） |
| iosApp | 空き容量（`DiskSpace`） | `IosStorageEnvironment.freeBytes`（`NSURLVolumeAvailableCapacityForImportantUsageKey`、取れなければ `NSFileSystemFreeSize`）。状態タブの「端末の空き容量」と空き容量の警告に表示する（#33）。同梱の SQLite には `statfs` / `fstatfs` もある（ファイルシステムの種類を見てロック方式を選ぶため。空き容量は読まない） | `85F4.1`（空き容量をユーザーに表示する） |
| iosApp | システムの起動時刻（`SystemBootTime`） | skiko（Compose Multiplatform の描画）の `mach_absolute_time`。フレームの経過時間の計測 | `35F9.1`（アプリ内の経過時間の計測） |
| WatchApp | `UserDefaults` | `RecorderController.swift`（録音を続けるかの状態 `wantsRecording`、端末 ID）、`WatchAudioApp.swift`（`complicationURL`）、`DebugAudioFileInput.swift`（Debug ビルドのみ） | `CA92.1`（アプリ自身だけが読み書きする） |
| WatchApp | `UserDefaults`（App Group） | `RecorderStatusPublisher.swift` → `RecorderStatusStore.sharedDefaults()`（`RecorderStatus.swift`）。録音状態をコンプリケーションと共有（#41） | `1C8F.1`（同じ App Group のアプリ・拡張だけが読み書きする） |
| WatchApp | ファイルのタイムスタンプ | `ChunkOutbox.swift` の `modificationDate(_:)`（異常終了で閉じられなかったチャンクの長さを、音声ファイルの更新時刻から求める）と `fileSize(_:)`。どちらもアプリのコンテナ内 | `C617.1` |
| WatchApp | 空き容量 | `ChunkOutbox.freeBytes()`（`attributesOfFileSystem` の `systemFreeSize`）。空きが足りなければ録音を始めない・止める（`RecorderPolicy` の `lowStorage`、Watch の画面とコンプリケーションに「空き容量不足」と表示） | `E174.1`（書き込める空きがあるかの確認。動作の違いがユーザーに見える） |
| WatchWidget | `UserDefaults`（App Group） | `RecorderStatusStore.load(from: sharedDefaults())`（Watch アプリが書いた録音状態を読む） | `1C8F.1` |
| WatchWidget | `UserDefaults` | 同じ関数で、App Group が使えないとき（署名なしのビルド）は自分の `UserDefaults.standard` に戻る | `CA92.1` |

使っていないもの: iPhone アプリの `UserDefaults`（Swift の `App/` にも Kotlin の `iosMain` にも依存ライブラリにもない）、入力中のキーボード（`activeInputModes`）、`systemUptime`。Watch アプリ・拡張の起動時刻（`Date` だけを使う）。

**洗い出しの方法**（2026-10-12）:

- Swift: `apps/ios/{App,SharedSwift,Watch,WatchWidget}`（テストを除く）を、Apple の一覧の API 名・キー名（`UserDefaults`、`creationDate` / `modificationDate`、`attributesOfItem`、`stat` 系、`volumeAvailableCapacity*`、`systemFreeSize`、`statfs` 系、`systemUptime`、`mach_absolute_time`、`activeInputModes`）で検索した。
- Kotlin: `shared/*/src/iosMain` を同じ名前で検索した（該当は `IosStorageEnvironment.kt` だけ）。
- 依存ライブラリ: iOS 向けの klib（Gradle キャッシュの `*-iossimulatorarm64`。Compose Multiplatform 1.12.1、skiko 0.150.1、androidx.sqlite 2.7.1、kotlinx-io 0.9.1、kotlinx-coroutines、lifecycle など）を開き、中のシンボル名を同じ一覧で照合した（JetBrains の [required-reason-finder](https://kotlinlang.org/docs/multiplatform/multiplatform-privacy-manifest.html) と同じ考え方）。該当は sqlite-bundled（`stat` / `fstat` / `lstat` / `statfs` / `fstatfs`）と skiko（`stat` / `fstat` / `mach_absolute_time`）だけ。kotlinx-io はファイルの種類と大きさを `NSFileManager` で読むだけで、タイムスタンプは読まない。
- Kotlin/Native のランタイム: 1.9.20 以降は既定のメモリアロケータが `mach_absolute_time` を使わない（JetBrains の同じ資料。本リポジトリは Kotlin 2.4）。

補足:

- JetBrains の資料は Compose Multiplatform の `stat` / `fstat` に `0A2A.1` を勧めているが、Apple の定義では `0A2A.1` は「サードパーティ SDK が自分のマニフェストで宣言する」理由。本アプリでは Kotlin のフレームワークをアプリの一部としてリンクし、アプリのマニフェストで申告するため、実際の用途（コンテナ内のファイルの属性）の `C617.1` にした。
- App Store Connect は提出（アップロード）時にバイナリを解析し、申告のない区分があれば「ITMS-91053: Missing API declaration」のメールが届く。最初の TestFlight へのアップロード（#35）でメールを確認し、指摘があれば区分を追加する。
- マニフェストの内容は `tools/privacy-check` が検査する（アプリ・拡張のターゲットごとに 1 つ、追跡・収集なし、理由コードは `policy.IOS_REQUIRED_REASONS` の許可リストだけ）。バンドルに入ったかは ios.yml の Release ジョブの `PRIVACY_MANIFEST_CHECK`（Xcode の環境が必要なので CI だけで確認できる）。
- 上の表の API の使い方を変えたら、マニフェスト・この表・`policy.py` を一緒に直す。

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
- **データの書き出し（#155）**: ユーザーが SAF の保存ダイアログで作った文書にだけ書く（アプリは通信しない）。診断ログと同じく「ユーザーが開始した操作で、ユーザーが合理的に予期する転送」で、「共有」の対象外と整理している。
- **Android vitals**: ユーザーが Google と使用状況・診断情報の共有を許可している場合に Play Console に届くクラッシュ統計は、Google（OS）が集めるもので、アプリの申告対象ではないと理解している。

### 3.2 前景サービスの申告（Play Console の「前景サービスの権限」）

**Wear OS アプリ: `FOREGROUND_SERVICE_MICROPHONE`（`RecordingService`、`foregroundServiceType="microphone"`）**

- 機能: 常時録音のボイスレコーダー。ユーザーがウォッチのアプリで録音を開始すると（タイル・コンプリケーションはアプリを開いて開始する）、ウォッチを着けている間、画面が消えていても会話を録音し続ける。
- 前景サービスが必要な理由: 録音はユーザーが開始し、画面消灯中も途切れず続く必要がある。録音中は継続中のアクティビティ（Ongoing Activity）と通知で録音中であることを常に表示し、ユーザーはいつでも止められる。充電中・ウォッチを外したとき・電池残量低下・空き容量不足・通話などでは自動的に一時停止する。
- 起動条件: ユーザーの操作（アプリの録音ボタン、またはタイル・コンプリケーションのタップで開いたアプリ）からだけ、アプリが前面にある状態で開始する。再起動後は自動で録音を始めず、「タップで再開」の通知を出す（Android 14 以降、`BOOT_COMPLETED` からマイクの前景サービスは開始できないため）。
- データの扱い: 音声はペアリングしたスマホにだけ直接接続で送られ、スマホの中で文字起こしされる。サーバーには送らない。
- 英文案: "The app is a continuous voice recorder for the watch. After the user starts recording in the app (the tile and complication open the app to start it), it keeps recording conversations while the watch is worn, including while the screen is off. A microphone foreground service is required so that user-started recording continues without interruption with the screen off. While recording, an ongoing activity and notification always show that recording is in progress, and the user can stop it at any time. Recording pauses automatically while charging, when the watch is taken off, at low battery, when storage is low and during calls. Recording is never started automatically after a reboot; the user is asked to tap to resume. Audio is sent only to the user's paired phone over a direct connection and transcribed on the phone; nothing is uploaded to any server."

**Android スマホアプリ: `FOREGROUND_SERVICE_DATA_SYNC`（WorkManager の `SystemForegroundService`、`foregroundServiceType="dataSync"`）**

- 機能: 端末内で文字起こしするための日本語音声認識モデル（数百 MB）のダウンロード。初回の準備時にユーザーが開始する（Wi-Fi 接続時だけに限る設定あり）。
- 前景サービスが必要な理由: ファイルが大きく、アプリを閉じてもダウンロードを続ける必要がある。ダウンロード中は進み具合の通知を表示する。中断しても途中から再開し、SHA-256 で検証する。
- データの扱い: ダウンロードだけで、ユーザーデータは送らない。
- 英文案: "The app downloads the Japanese speech recognition model (several hundred MB) that it needs to transcribe recordings on the device. The user starts the download during setup and can restrict it to Wi-Fi. A data sync foreground service keeps the large download running when the app is closed, with a progress notification shown throughout. The download is resumable and verified with SHA-256. No user data is uploaded."
- **Android 15 以降の実行時間の上限（#159 で確認）**:
  - 公式の基準: `targetSdk` 35 以上のアプリ（本アプリは 37）では、`dataSync` の前景サービスは **24 時間のうち合計 6 時間**まで（アプリのすべての `dataSync` で共有）。上限に達するとシステムが `Service.onTimeout(int, int)` を呼び、数秒以内に `stopSelf()` しないと `RemoteServiceException` で異常終了する。ユーザーがアプリを前面に出すと時間はリセットされる。使い切った後に `dataSync` を始めようとすると `ForegroundServiceStartNotAllowedException` になる（[Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)）。
  - 本アプリの使い方: `dataSync` を使うのは `ModelDownloadWorker`（WorkManager の `CoroutineWorker` が `setForeground` で `FOREGROUND_SERVICE_TYPE_DATA_SYNC` を指定）だけで、モデル（約 160 MB）のダウンロード中だけ動く。開始はユーザーの操作（オンボーディング・状態タブの「ダウンロード」、`ModelManager.download`）と、アプリ起動時の未取得分の再開（`downloadIfMissing`、Wi-Fi 時のみ）。6 時間かかるのは平均 60 kbps 程度より遅い回線のときだけ。
  - 上限に達したとき: `onTimeout` は WorkManager の `SystemForegroundService` が処理する（2.10.0-alpha03 で「`dataSync` の前景ワーカーが `stopSelf()` されず ANR になる」問題を修正、2.10.0-alpha04 で停止理由 `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` を追加。[WorkManager のリリースノート](https://developer.android.com/jetpack/androidx/releases/work)。本アプリは 2.11.2）。ワーカーは止められて再実行の予約に戻り、ダウンロードは `Range` で続きから再開する（`ModelDownloader.kt`）。再実行でアプリが背面にあれば `setForeground` が `IllegalStateException`（`ForegroundServiceStartNotAllowedException`）になり、ワーカーはそれを捕まえて通常のワーカーとして続ける（`ModelDownloadWorker.kt`）。
  - 結論: **コードの変更は不要**。申告の用途は Play Console の一覧の「Network transfer: Upload or download」を選ぶ（[前景サービスの要件](https://support.google.com/googleplay/android-developer/answer/13392821)）。
  - 推奨（任意）: (1) エミュレータで上限を短くして（`adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS app.watchaudio`、`adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000`）異常終了せず続きから再開することを確かめる。(2) Google はユーザーが求めた通信には「ユーザーが開始するデータ転送ジョブ（user-initiated data transfer job）」を勧めている（同ページの FAQ）。WorkManager からは使えず JobScheduler を直接使う必要があるため、今は採らない。前景サービスの申告が通らなかった場合の代替として残す。

### 3.3 そのほかの「アプリのコンテンツ」

| 項目 | 回答案 |
| --- | --- |
| プライバシーポリシー | ［公開 URL］（マイク権限を使うアプリは必須） |
| 広告 | 含まない |
| アプリのアクセス権（ログイン） | ログイン不要。審査用の手順（§2.2 と同じ流れ、Wear OS とスマホ）を記載する |
| 対象年齢 | ［運営者が決める］（子ども向けではない） |
| コンテンツのレーティング | 質問票に回答（暴力・課金などなし。ユーザー間のやり取りなし） |
| 健康アプリの申告 | **「健康機能はない（My app doesn't provide any health features）」と申告する**。根拠と確認内容は下の §3.3.1 |
| 権限の説明 | `RECORD_AUDIO`: 録音のため（Wear OS のみ。スマホはマイクを使わない）。`BODY_SENSORS`: ウォッチを外したことの検知だけ、値は保存しない |
| Wear OS | スマホアプリが必須（`com.google.android.wearable.standalone=false`）。Wear OS の品質ガイドラインの確認が必要（§4） |

#### 3.3.1 健康アプリの申告と `BODY_SENSORS`（#159 で確認）

- **公式の基準**: 健康アプリの申告（Health apps declaration）は、Google Play に公開する**すべてのアプリ**が提出する（クローズド・オープンテストを含む）。健康機能がないアプリも、健康機能がないことを申告する（2024-08-31 以降必須）。対象の機能の分類は「Activity and Fitness」「Sleep Management」「Diseases and Conditions Management」などの健康・医療の機能（[Provide information for the Health apps declaration form](https://support.google.com/googleplay/android-developer/answer/14738291)）。この申告のページは権限（`BODY_SENSORS`）には触れていない。
- 一方、Google Play の「健康に関するデータ」のポリシーは `BODY_SENSORS` を健康関連の機密データに当たる権限の例に挙げ、使う場合は**目立つ開示とユーザーの同意**を求める（[Health apps / health-related sensitive data](https://support.google.com/googleplay/android-developer/answer/13996367)。一覧は例示）。
- **本アプリの使い方**: Wear OS アプリは `BODY_SENSORS` を `maxSdkVersion="35"`（Wear OS 4/5）でだけ宣言し、ユーザーが録音ボタンを押したときに、マイク・通知と一緒に任意の許可として 1 回だけ求める（`MainActivity.requestStart` / `optionalPermissions`。拒否しても録音できる）。用途は `Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT` による「ウォッチを外した」ことの検知だけで、録音の一時停止に使う（`ConditionMonitors.kt` の `OffBodyMonitor`）。心拍などの値は読まず、検知の結果も保存・送信しない。健康・フィットネスの機能はない。Wear OS 6（API 36）以降は `BODY_SENSORS` を宣言せず、細かい健康の権限も求めない（許可なしで装着検出を試み、イベントが来なければ装着中とみなす。[wearos.md](../platforms/wearos.md) の権限の表）。
- **推奨する対応**:
  1. 健康アプリの申告では「健康機能はない」を選ぶ（装着検出は健康機能の分類のどれにも当たらない）。
  2. 権限の説明（Play Console のアプリのコンテンツ・ストアの説明・審査メモ）に「`BODY_SENSORS` はウォッチを外したことの検知だけに使い、値は保存も送信もしない（Wear OS 5 以前のみ）」と書く（§3.3 の「権限の説明」）。
  3. 目立つ開示: 今は録音ボタンを押したときに OS の許可ダイアログ（「身体センサー」）が出るだけで、装着検出のために使うというアプリ自身の説明がない。ポリシーの「目立つ開示と同意」に合わせるため、許可を求める前に「ウォッチを外したら録音を止めるために使います（値は保存・送信しません）」と一文を出すことを勧める（Wear OS の作業として別 Issue にする。リリース前に必要かは運営者が判断）。
  4. 審査で権限について質問されたら、装着検出だけに使うことを説明する。指摘が続く場合の代替は、`BODY_SENSORS` の宣言をやめる（Wear OS 4/5 では、許可がないと装着検出が働かない機種で「外したら一時停止」がなくなる。充電中の一時停止などは残る）。
- データセーフティ: センサーの値は端末から出ないので「健康情報」の収集・共有には当たらない（§3.1）。

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
| 通信はモデルのダウンロードだけ | `policy.py` の `ALLOWED_API_USES`（通信 API は `apps/android/src/main/kotlin/app/watchaudio/android/asr/ModelDownloader.kt` だけ）と `ALLOWED_URL_PREFIXES`（`https://huggingface.co/csukuangfj/reazonspeech-k2-v2`、`https://github.com/k2-fsa/sherpa-onnx/releases/download/`）。URL は `ModelManifest.kt`。[docs/architecture/02 §7.2](../architecture/02-architecture.md#72-通信箇所の一覧) |
| iOS のモデルは OS がダウンロード、サーバー型の音声認識は使わない | `apps/ios/App/SpeechTranscription.swift`（`AssetInventory`、`SpeechAnalyzer`）。`policy.py` が `SF…RecognitionRequest` を禁止 |
| Wear OS アプリはインターネットを使わない | `apps/wear/src/main/AndroidManifest.xml` に `INTERNET` なし。`policy.py` の Wear OS の権限の許可リスト |
| Wear OS → スマホは直接接続のノードだけ、`DataClient` は使わない | `apps/wear/.../outbox/OutboxUploader.kt`（`isNearby` のノードだけ）、スマホ側も `apps/android/.../transfer/ChunkReceiver.kt` で `isNearby` を確認。`policy.py` が `DataClient` を禁止。[docs/platforms/wearos.md](../platforms/wearos.md) の手動確認 17 |
| Apple Watch → iPhone は WatchConnectivity | `apps/ios/Watch/Recording/ChunkTransport.swift`（`transferFile` / `transferUserInfo`）、`apps/ios/App/WatchReceiver.swift` |
| 転送が済んだチャンクは Watch から消える | `apps/wear/.../outbox/Outbox.kt`（最終 ACK で削除）、watchOS は [docs/platforms/ios-watchos.md](../platforms/ios-watchos.md) の送信キュー |
| OS バックアップの対象外、Android スマホは端末間の直接転送だけ許可 | `apps/android/src/main/AndroidManifest.xml`（`allowBackup="false"`）、`apps/android/src/main/res/xml/data_extraction_rules.xml`（`cloud-backup` で全ドメイン除外、`device-transfer` は許可）、Wear OS は両方除外。iOS・watchOS は `NSURLIsExcludedFromBackupKey`。[docs/architecture/02 §7.1](../architecture/02-architecture.md#71-保存場所と保護76) |
| iOS・watchOS のファイル保護 | `CompleteUntilFirstUserAuthentication` を `LocalDataDirectory`（shared/ui iosMain）と `LocalDataProtection`（watchOS）で明示。`policy.py` が保護なしの指定を禁止 |
| 診断ログに本文・音声・例外メッセージを含まない、ユーザーの書き出しでだけ外に出る | `shared/pipeline/src/commonMain/kotlin/app/watchaudio/core/pipeline/Diagnostics.kt`（ID・件数・時間・エラーの型だけ、`exportTo`）、`apps/android/.../diag/CrashRecorder.kt`（型と上位フレームだけ）、`apps/android/src/main/AndroidManifest.xml` の `FileProvider`（共有シート用）。Android の `app_start` は `version`・`sdk`・`device=Build.MODEL`、iOS は `version`・`os`。Watch の診断ログ（`apps/wear/.../diag/DiagnosticsLog.kt`、`apps/ios/Watch/Recording/DiagnosticsLog.swift`）は電池残量・充電状態も持つが、アプリからの書き出し機能はない |
| データの書き出しはユーザーが選んだ保存先にだけ書き、通信しない（#155） | 共通処理 `shared/pipeline/src/commonMain/kotlin/app/watchaudio/core/export/DataExporter.kt`（渡された出力先に ZIP を書くだけ）、保存先は `apps/android/.../AndroidAppServices.kt` の `saveExport`（SAF の `CreateDocument` で作った文書だけ）と `shared/ui/src/iosMain/.../IosAppServices.kt` の `saveExport`（`tmp/data-export/` → 共有シート、閉じたら削除）。ボタンを押したときだけ（`ExportViewModel`）。`tools/privacy-check` は通信 API の追加がないことを確認 |
| 暗号化バックアップはユーザーが選んだ保存先にだけ書き、パスフレーズと鍵は端末の外に出さない（#40） | 共通処理 `shared/pipeline/src/commonMain/kotlin/app/watchaudio/core/backup/`（`BackupWriter` は渡された出力先に暗号化したファイルを書くだけ、`BackupCipher` が鍵を導出してメモリ上でだけ使い、使い終えたら配列をゼロで消す、`BackupRestorer` はユーザーが選んだファイルを読むだけ）、暗号の基本操作は `shared/platform/src/*/kotlin/app/watchaudio/core/platform/Crypto*.kt`（OS の API だけ）。保存先は `saveExport`（上と同じ）、読み込みは `AndroidAppServices.openBackup`（SAF の `OpenDocument`）と `IosAppServices.openBackup`（ドキュメントピッカーのコピー、読んだら削除）。パスフレーズは画面の状態（`BackupViewModel`）にだけあり、診断ログには件数とエラーの型だけ（`BackupViewModelTest` がログにパスフレーズ・本文がないことを確認）。`tools/privacy-check` は通信 API の追加がないことを確認 |
| 識別子はランダム | `deviceId` は Wear OS がインストールごとの `UUID.randomUUID()`（`apps/wear/.../WearPrefs.kt`）、watchOS も `UUID`（`RecorderController.swift`）。チャンク・セッションも UUID |
| 保持期間（7/30/90 日・無期限、既定は無期限、文字起こし済みの音声だけ削除） | `shared/pipeline/.../AudioRetention.kt`、文言は `shared/ui/.../Strings.kt` |
| 日ごとの削除・すべてのデータの削除 | `shared/pipeline/.../StorageManager.kt`（`deleteAll` は全チャンク・音声を消し DB を `VACUUM` し、スマホの診断ログも `DiagnosticsLog.clear` で消す。ローテーション分と共有用の書き出しのコピーも含む。#159）。テストは `StorageManagerTest.deleteAllAlsoClearsTheDiagnosticsLogAndItsExports`・`DiagnosticsLogTest.clearRemovesTheLogTheRotatedPartAndExportsOnly`。UI は `shared/ui/.../ui/StorageCard.kt`（確認の文言 `Strings.DELETE_ALL_NOTE` に「診断ログ」を明記）。**設定は残す**（オンボーディングをやり直さないため）。**Watch の診断ログは Watch 側にあり対象外**（大きさの上限で古い記録から消え、アンインストールで消える） |
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
- [x] iOS・watchOS の `PrivacyInfo.xcprivacy` の追加（§2.3。理由が必要な API の洗い出し）（#159。CI でのビルドの確認は #159 の完了条件）
- [x] iPhone アプリの使っていない `NSMicrophoneUsageDescription` を削除（§2.2、#159）
- [x] 「すべてのデータを削除」でスマホの診断ログも消す（#159。設定は残す）
- [x] Google Play の健康アプリの申告と `dataSync` の時間制限の確認（§3.2・§3.3.1、#159）
- [ ] シミュレータ・エミュレータでのスクリーンショット作成（§4）
- [ ] Wear OS の品質ガイドラインとの照合
- [x] データの書き出し（#155）をポリシー（第 7 条）とこの申告に追記した
- [x] 暗号化バックアップ（#40）をポリシー（第 7 条）とこの申告に追記した
- [ ] 暗号化の輸出コンプライアンスの回答を運営者が決め、`ITSAppUsesNonExemptEncryption` を設定する（§2.1）
- [ ] 課金（#34）を公開するときに、ポリシーとこの申告を更新する
