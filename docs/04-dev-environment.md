# 04. 開発・検証環境

## 1. 役割分担

| 作業 | どこで | 備考 |
| --- | --- | --- |
| 共通ロジック・Android・Wear OS のビルド/テスト | クラウド（Claude Code）・ローカル・CI | Linux で完結 |
| iOS / watchOS のビルド | **Mac** または CI（GitHub Actions macOS） | Xcode が必要なため Linux 不可 |
| 実機での録音・電池・転送の検証 | **手元の実機（人が操作）** | M0-3 / M0-4 / M1 以降の受け入れ |
| ASR・埋め込みのモデル比較（精度の当たり付け） | クラウド（Linux CPU） | 実機での速度/精度は別途確認 |

## 2. 必要なもの

### ハードウェア

| 用途 | 推奨 | 最低限 |
| --- | --- | --- |
| Mac | Apple Silicon（M1 以降）、メモリ 16GB 以上 | Xcode 26 以降が動く Mac |
| iPhone | iOS 26 以降（SpeechAnalyzer 利用）。将来のオンデバイス LLM を見据えるなら iPhone 15 Pro 以降 | iOS 26 対応機 |
| Apple Watch | Series 10 / 11 / Ultra 2 以降（watchOS 26） | watchOS 26 対応機 |
| Android スマホ | Pixel 8 以降（Android 15+） | Android 10 以降 |
| Wear OS ウォッチ | **Pixel Watch 3/4 と Galaxy Watch 7/8 の両方**（Samsung は省電力挙動が独特で、シェアも大きい） | どちらか 1 台（Wear OS 4 以降） |

> 電池計測は機種ごとの差が大きいため、可能なら「録音検証専用」に 1 日使える個体を用意する。

### アカウント・費用

| 項目 | 必要になる時期 | 費用 |
| --- | --- | --- |
| Apple Developer Program | M0-3（Watch 実機に長期インストール）、M4（TestFlight） | 年 $99（無料アカウントでも実機インストールは可能だが 7 日で失効） |
| Google Play Console | M4（内部テスト配信） | 初回 $25 |
| AI プロバイダの API キー（OpenAI / Anthropic / Gemini のいずれか） | M3 | 従量課金 |
| GitHub Actions | 常時（PR と main） | 非公開リポジトリは無料枠 2,000 分/月。**macOS は 10 倍消費**（iOS CI 1 回 ≈ 3〜6 分 → 30〜60 分相当。Apple 側・共通コードの変更時のみ実行） |

### ソフトウェア

- JDK 21、Android Studio（最新安定版）
- Xcode 26 以降、[XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）

## 3. ビルド手順

```bash
# Android / Wear OS / 共通テスト
./gradlew testAndroidHostTest
./gradlew :androidApp:installDebug   # スマホ
./gradlew :wearApp:installDebug      # Wear OS（adb でウォッチを接続）

# iOS / watchOS（Mac）
cd iosApp
echo 'TEAM_ID = XXXXXXXXXX' > Configuration/Local.xcconfig   # 自分の Team ID
echo 'BUNDLE_ID_PREFIX = com.example.watchaudio' >> Configuration/Local.xcconfig  # 一意な Bundle ID
xcodegen generate
open WatchAudio.xcodeproj   # iosApp スキームを実機に Run（Watch アプリも一緒に入る）
```

Linux でも Kotlin の iOS 向けコード（`iosMain`）の**コンパイルまで**は確認できる（klib クロスコンパイル。リンクとフレームワーク生成は Mac のみ）:

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

iOS / watchOS 側の構成は [docs/impl/ios-watchos.md](impl/ios-watchos.md) を参照。

Wear OS 実機への接続は「開発者向けオプション → ワイヤレスデバッグ」→ `adb pair` / `adb connect`。

## 4. 実機なしでの通し検証（Linux）

Watch の役割をシミュレートし、「チャンク化 → 取り込み → オンデバイス文字起こし → ローカル SQLite に蓄積 → 検索」までを Linux 上で動かせる（#44）。Android アプリと同じ sherpa-onnx・同じモデル・同じ VAD 設定を使うので、精度の確認に使える（速度は実機と異なる）。

```bash
tools/harness/download-models.sh          # モデルを tools/harness/models/ に取得（受信のみ）
./gradlew :tools:harness:test             # E2E テスト（日本語の参照音声 5 文、チャンク境界をまたぐ発話）

# 任意の音声ファイルで試す（mp3/wav/m4a など。ffmpeg が必要）
./gradlew :tools:harness:run --args="record /tmp/wa-data path/to/audio.mp3"
./gradlew :tools:harness:run --args="search /tmp/wa-data 会議"
./gradlew :tools:harness:run --args="show /tmp/wa-data"
```

正解テキスト付きの日本語会話（オフライン TTS）を Watch 録音を模して加工した検証用音声セットと、それをハーネスに流して条件ごとの CER・検出漏れ・処理時間を出す `evaluate` コマンドがある（#56）。生成手順・条件・ライセンス・結果は [docs/test-audio.md](test-audio.md)。

## 5. 検索の性能ベンチマーク（#23）

1 年分相当（約 50 万セグメント）の日本語の文字起こしを決定的に生成し（仕事・家庭・用事の語彙を組み合わせた会話文、5 分チャンク × 約 10 発話）、`StoreTransaction.search` の応答時間を測る。通常の `jvmTest` には含めない（データ投入に約 30 秒かかるため）。

```bash
./gradlew --max-workers=2 :shared:core-db:jvmBenchmark                       # 50 万セグメント、各クエリ 40 回
./gradlew --max-workers=2 :shared:core-db:jvmBenchmark -Pbenchmark.segments=50000 -Pbenchmark.rounds=5   # 手早く試す
```

- 対象クエリ: 3 文字以上（FTS5 trigram）は頻出語・フレーズ・まれな語・ヒットなし、2 文字以下（LIKE フォールバック）は頻出語・1 文字・まれな語・ヒットなし。いずれも上限 50 件・スニペット生成込み。
- 出力: 標準出力に `SEARCH_BENCHMARK segments=… fts_p50_ms=… fts_p95_ms=… like_p50_ms=… like_p95_ms=… all_p95_ms=… max_ms=… target_ms=300 …` の 1 行（`key=value` 区切り）と、クエリ別の表（Markdown、`shared/core-db/build/reports/search-benchmark/summary.md`）。
- 判定: どれか 1 つのクエリの p95 が 300 ms を超えるとテストが失敗する。
- CI: `android.yml` の「Search benchmark (500k segments, target 300 ms)」ステップで毎回実行し、表をジョブサマリーに出す。

計測例（クラウドの Linux、4 CPU、JVM 21。2026-10-10）:

| | FTS（3 文字以上）p50 / p95 | LIKE（2 文字以下）p50 / p95 | 最悪のクエリ p95 |
| --- | --- | --- | --- |
| 改善前 | 139 / 260 ms | 0.8 / 212 ms | 327 ms（2 文字・ヒットなし）、301 ms（「ミーティング」） |
| 改善後 | 30 / 48 ms | 0.8 / 128 ms | 148 ms（2 文字・ヒットなし） |

改善内容（`StoreTransaction.search`、マイグレーション 4）:

- FTS5 の `snippet()` は ORDER BY / LIMIT の前に**全ヒット行**で計算されるため、頻出語で検索時間の大半を占めていた。FTS ではセグメント ID だけを新しい順に並べて上位 50 件に絞り、スニペット（一致箇所の `[` `]` と前後の切り詰め）は返す行だけ Kotlin で作る（`highlightSnippet`）。
- LIKE フォールバックは `(start_at, text)` の索引を新しい順に走査し、表本体を読まずに絞り込む（旧 `idx_segment_start_at` を置き換え。DB は 1 年分で約 60 MB 増える）。2 文字のヒットなし検索は全件走査になるため、これが最悪ケース。

実機（中位機種）での値は未計測。スマホの CPU・ストレージは上の環境より遅いので、実機入手後に同じクエリで確認する。

## 6. CI での自動確認（実機なし）

| ワークフロー | ステップ | 確認できること | 証拠の場所 |
| --- | --- | --- | --- |
| `android.yml` | Unit tests | 共通ロジック（JVM・Android ホスト）。SQLite を使う core-db / Ingestor / パイプラインのテストは JVM で実行 | テスト結果 |
| `android.yml` | Search benchmark | 50 万セグメントでの検索応答時間（§5） | ジョブサマリーの表と `SEARCH_BENCHMARK` 行 |
| `android.yml` | Unit tests（composeApp の `AppUiTest`） | 画面の描画（タイムライン・検索・状態・オンボーディング。権限拒否時の「設定を開く」#32 を含む） | 成果物 `ui-screenshots`（`composeApp/build/screenshots/*.png`） |
| `emulator.yml`（phone / wear） | Install, launch and check for crashes | スマホ・Wear OS アプリがエミュレータで起動し、15 秒後もクラッシュしていない | ジョブのログ、成果物 `logcat-*` |
| `emulator.yml`（phone） | 同上（続けて実行） | 計装テスト: 実際の音声（AAC）を MediaPlayer で指定位置から再生できる・検索結果を 1 タップでその発話から再生できる（`PlaybackTest`）、SQLite のテストを Android 上で実行（core-db / core-pipeline） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK` 行、成果物 `logcat-phone`（テストレポート） |
| `emulator.yml`（phone） | 同上（計装テスト） | 実モデルでの文字起こし: `AndroidSherpaTranscriber`（MediaCodec + sherpa-onnx AAR）が日本語の参照音声チャンクを文字起こしして DB に保存・検索できる（`SherpaTranscriberTest`、#22） | `ASR_CHECK` / `ASR_SEGMENT` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | モデルなしでも受信・再生でき文字起こしは待機（チャンクは処理待ちのまま・失敗扱いにしない）、モデルを置くと自動で処理が進む（`ModelWaitTest`、#22） | `MODEL_CHECK` 行、スクリーンショット `status-model-missing.png` / `status-model-ready.png` |
| `emulator.yml`（phone） | 同上（計装テスト） | 定期ジョブ（6 時間ごと・充電中のみ）は充電されるまで動かず、充電条件を満たすと溜まったチャンクを文字起こしする（WorkManager の `TestDriver`、`ChargingWorkTest`、#20） | `CHARGING_CHECK` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | 録音の途絶アラートが本番の `GapAlertMonitor` から通知され、通知シェードに表示される（`GapAlertNotificationTest`、#43） | `GAP_ALERT_CHECK` 行、スクリーンショット `gap-alert-notification.png` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 通知権限をシステムのダイアログで拒否 → 理由と「設定を開く」が表示され、押すとアプリの設定画面が開く（`PermissionDeniedTest`、#32） | `PERMISSION_CHECK` 行、スクリーンショット `permission-system-dialog.png` / `onboarding-permission-denied.png` / `app-settings-opened.png` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 文字起こしの途中で `am force-stop` → 再起動で全チャンクが 1 回ずつ文字起こしされる（欠落・重複 0、中断したチャンクは 2 回目で完了）（`ResumeAfterKillTest`、#20） | `RESUME_CHECK` 行、成果物 `logcat-phone` の `scenario-logs/` |
| `emulator.yml`（wear） | 同上（`wear-surfaces.sh`） | Tile とコンプリケーションの表示（停止中 → Tile のタップでアプリが開き録音開始 → 録音中）（#41） | `WEAR_SURFACE_CHECK` 行、スクリーンショット `wear-tile-*.png` / `wear-complication-*.png` |
| `ios.yml` | Shared tests (iOS simulator) | 共通ロジックを iOS シミュレータで実行（SQLite を使う core-db / Ingestor のテストを含む）。composeApp の `IosPlaybackTest`: `IosAppServices.play` 直接・共有 UI で前日のタイムラインの発話タップ／検索結果の 1 タップのそれぞれで、そのチャンクの AAC が AVAudioPlayer に指定位置（±0.2 秒）・正しい長さで用意される（#19, #25。テスト実行ファイルはアプリではないので再生開始 `started=` は記録のみ）。`IosTranscriberBridgeTest`: Swift の文字起こし結果が絶対時刻つきで DB・FTS に入る（#21） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK` 行 |
| `ios.yml` | Launch iOS app / Launch watchOS app | ビルドした iOS・watchOS アプリをシミュレータに入れて起動し、15 秒後もプロセスが生きていてクラッシュレポートがない | ジョブサマリー、成果物 `simulator-smoke`（スクリーンショット・ログ） |
| `ios.yml` | Swift unit tests (iOS Simulator) | `SwiftUnitTests` スキーム（ホストなし）: Swift のワイヤ形式が Kotlin のテストベクタと一致（`ProtocolTests`）、Watch の停止理由・状態判定と終了理由のマニフェストへの記録（`RecorderPolicyTests`、#15）、送信キューの ACK・再送・再起動後の再送・強制終了からの復旧（`ChunkOutboxTests`、#17） | ジョブサマリーのテスト一覧（`.github/scripts/xcresult-summary.py`） |
| `ios.yml` | Japanese test speech / Speech transcription on the iOS simulator | macOS の `say` で作った日本語音声（Watch と同じ AAC 16 kHz モノラル）を SpeechTranscriber（ja_JP）で文字起こしし、時刻と文字の再現率を確認（`SpeechTests`、iOS アプリがホスト、#21）。シミュレータで使えなければ理由を残してスキップ（実機確認は #60） | ジョブサマリーのテスト一覧と「Speech check logs」、成果物 `swift-evidence` |
| `ios.yml` | Playback in the iOS app (simulator) | `PlaybackTests`（iOS アプリがホストの XCTest）: アプリが起動時に作った本物の `IosAppServices` で AAC 16 kHz の音声を 8 秒から再生し、再生位置がそこから始まり 2 秒後に 1 秒以上進む（#19） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK check=hosted_player` 行、成果物 `swift-evidence` |

スクリーンショットは成果物 `screenshots-phone` / `screenshots-wear`（30 日保存）、`*_CHECK` 行はジョブサマリーにまとめて出す。

SQLite（bundled ドライバ）を開くテストは `src/sqliteTest` に置く（`watchaudio.kmp.sqlite-tests` プラグイン）。`jvmTest`・`iosTest`・`androidDeviceTest` から共有され、JVM・iOS シミュレータ・Android 端末/エミュレータで実行される。Android のホストテスト（`testAndroidHostTest`）では実行しない: Android 版の `sqlite-bundled` は Android の ABI 向けネイティブライブラリしか含まず、ホストの JVM では読み込めないため。

- core-db（`LocalStoreTest`・`StorageQueriesTest`）と core-pipeline（`IngestorTest`・`TranscriptionPipelineTest`・`StorageManagerTest`）: JVM・iOS シミュレータ・Android 端末/エミュレータ。
- composeApp（`GapAlertEvaluatorTest`）: JVM・iOS シミュレータ（`watchaudio.kmp.sqlite-tests-jvm-ios`）。Compose Multiplatform のリソースプラグインが KMP ライブラリの Android デバイステスト用バリアントを構成できないため、Android 端末では実行しない。
- composeApp の `ViewModelTest`・`AppUiTest` は JVM のみ（`Dispatchers.Main` に Swing、描画にデスクトップの Skia を使うため）。iOS では `src/iosTest` の `IosPlaybackTest` が実際の `IosAppServices` で同じ流れ（タイムライン・検索 → 再生の準備）を確かめる。Kotlin/Native のテスト実行ファイルはアプリのプロセスではなく `AVAudioPlayer.play()` が false を返すため、再生が進むことはアプリをホストにした XCTest（`iosApp/PlaybackTests`）で確かめる。Kotlin/Native のテストはメインスレッドで動き、`Dispatchers.Main` はメインのランループが回るときだけ進むので、待ち合わせ中にランループを回している（`IosPlaybackFixture.pumpMainRunLoop`）。

手元での実行:

```bash
# iOS 向けテストのコンパイル確認（Linux、リンクなし）
./gradlew --max-workers=2 :shared:core-db:compileTestKotlinIosSimulatorArm64 :shared:core-pipeline:compileTestKotlinIosSimulatorArm64 \
  :composeApp:compileTestKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
# Android エミュレータ / 実機を接続して（KVM のある環境）
./gradlew :androidApp:connectedDebugAndroidTest :shared:core-db:connectedAndroidDeviceTest :shared:core-pipeline:connectedAndroidDeviceTest
adb logcat -d | grep -E "[A-Z_]+_CHECK"
# Mac: ビルド済みアプリをシミュレータで起動確認
.github/scripts/simulator-smoke.sh iOS iPhone build/DerivedData/Build/Products/Debug-iphonesimulator/WatchAudio.app app.watchaudio
# Mac: Swift のテスト（cd iosApp && xcodegen generate の後）
.github/scripts/xcode-test.sh SwiftUnitTests "Swift unit tests"
.github/scripts/make-ja-speech.sh build/ja-speech && .github/scripts/xcode-test.sh SpeechTests "SpeechTranscriber"
ERASE_SIMULATOR=1 .github/scripts/xcode-test.sh PlaybackTests "Playback in the iOS app"
```

Swift のテストを Linux で実行する手段はない（Xcode が必要）。変更したら CI（macOS、10 倍課金）で確認する前に、Kotlin 側は上のクロスコンパイル、Swift 側は差分の読み直しで型・並行性のエラーを潰しておく。

### 6.1 エミュレータでの音声認識モデル

phone ジョブは `android.yml` と同じキャッシュ（`tools/harness/models`、キーは `download-models.sh` のハッシュ）からモデルを復元し、なければ `download-models.sh` で取得する（int8 の約 160 MB と参照音声のみ展開）。`phone-scenarios.sh` が `adb push` で `/data/local/tmp/watchaudio-test/models/` に置き、計装テスト（`SpeechFixtures.ensureModels`）がそれをアプリの `files/models/`（ダウンロード先と同じ場所）にコピーしてから使う。`/data/local/tmp` を経由するのは、テストエンジンが計装テストの前にアプリをアンインストール・再インストールしてアプリの領域を消すため。コピーはシェル権限の `cat` をパイプで読む（アプリから `/data/local/tmp` を直接読めるかは SELinux の設定に依存するため）。

- モデルがない環境（手元の `connectedDebugAndroidTest` など）では、モデルを使うテストはスキップされる。CI は `-Pandroid.testInstrumentationRunnerArguments.requireModels=true` を渡すので、モデルがなければ失敗する。
- テスト中はアプリの自動モデルダウンロード（`model-download`）を取り消す（テスト中にファイルが置き換わらないように）。
- エミュレータは x86_64 で CPU 推論のみ。`ASR_CHECK` の `rtf`（処理時間 ÷ 音声長）は実機の目安にはならない。

### 6.2 1 回の計装テストでは確かめられないもの（`phone-scenarios.sh`）

- **権限の拒否（#32）**: 権限を取り消すとアプリのプロセスが終了するため、計装テストの中ではリセットできない。インストール直後（権限が一度も付与されていない状態）に `am instrument -e scenario permission-denied` で最初に実行する。
- **強制終了からの再開（#20）**: 計装テストは強制終了されるプロセスの中で動くため、3 段階に分ける。① `ResumeAfterKillTest#seed` が参照音声のチャンクを 8 件保存（文字起こしは要求しない）、② スクリプトがアプリを起動（起動時に未処理チャンクの処理を要求）し、`TranscriptionWorker` のログに最初の `transcribed chunk` が出たら `adb shell am force-stop app.watchaudio`、③ `ResumeAfterKillTest#verify` が再起動後に全件の完了を待ち、各チャンクの発話が重複なく揃っていること・強制終了時に処理中だったチャンクの試行回数が 2 であることを確認する。

手元で同じことをする場合（KVM のある環境、`tools/harness/download-models.sh` 済み）:

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
.github/scripts/phone-scenarios.sh            # モデルの push、権限拒否、強制終了からの再開
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireModels=true
adb pull /data/local/tmp/watchaudio-screenshots
```

## 7. 実機検証の手順

- M0-3 / M0-4 電池計測: [docs/spikes/m0-3-m0-4-battery-protocol.md](spikes/m0-3-m0-4-battery-protocol.md)
- Wear OS 録音アプリ（常時録音・転送）の手動テスト: [docs/impl/wearos.md §10](impl/wearos.md#10-実機での手動テスト-チェックリスト)

## 8. 検証データの扱い

- 録音検証では、**参加者全員に録音・文字起こしの目的を説明し同意を得る**。
- 実際の会話音声はリポジトリにコミットしない（個人情報）。評価用の正解テキストと集計結果のみ共有する。
