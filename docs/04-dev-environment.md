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

## 5. 検索の性能ベンチマーク（#23, #72）

1 年分相当の日本語の文字起こしを決定的に生成し、`StoreTransaction.search` の応答時間を測る。通常の `jvmTest` には含めない（1 年分の投入に数分かかるため）。

```bash
./gradlew --max-workers=2 :shared:core-db:jvmBenchmark                       # 1 年分（320 万セグメント、約 1 GB）、各クエリ 40 回
./gradlew --max-workers=2 :shared:core-db:jvmBenchmark -Pbenchmark.segments=50000 -Pbenchmark.rounds=5   # 手早く試す（約 17 MB）
./gradlew --max-workers=2 :shared:core-db:jvmBenchmark -Pbenchmark.profile=sentences -Pbenchmark.segments=500000   # #23 の前提（長めのセグメント）
```

1 年分の DB は約 1 GB になる。ディスクの空きが少ない環境では件数を減らして試し、1 年分は CI で確認する。

### 生成するデータ（`TranscriptGenerator`）

| プロファイル | 内容 | 1 日（装着 16 時間）| 1 年 |
| --- | --- | --- | --- |
| `utterances`（既定、#72） | #68（PR #70）以降のパイプラインと同じ「話者交代の間で切った発話」単位。1 行あたり平均 11.8 文字・長さの中央値 1.75 秒。会話のあるチャンク（42%）に 70〜120 発話を詰め、残りのチャンクは 0〜12 発話 | 約 8,600 セグメント | 約 314 万 → **320 万**で測る |
| `sentences`（#23） | 5 分チャンク × 約 10 発話、1 行 1〜3 文（約 30 文字） | 約 1,400 セグメント | 約 50 万 |

`utterances` の件数の根拠（[02 §9.3](02-architecture.md#93-データ量の見積もり)）: 装着 16 時間のうち発話は約 31%（約 5 時間、[M0-5 §5](spikes/m0-5-asr-desktop.md#5-1-日分の処理時間の見積もりスマホ)）。検証用音声セット（[test-audio.md](test-audio.md)）の会話は発話 24 分で 505 発話・688 文・約 8,000 文字、PR #70 の評価では保存されたセグメントの長さの中央値が clean 1.7 秒・wrist 1.9 秒で、ほぼ 1 文 = 1 セグメントになる。発話 1 時間あたり約 1,720 セグメント × 5 時間 = 1 日約 8,600。雑音のある条件（office・cafe・day など）では話者交代で切れず中央値 5〜10 秒なので、行数は少なくなる。静かな条件の値を上限として使う。

チャンク ID は UUID、エンジン ID は実際と同じ長さにして、1 行あたりの容量もアプリと同じになるようにしている。

### 対象クエリと出力

- 対象クエリ: 3 文字以上（FTS5 trigram）は頻出語・フレーズ・まれな語・ヒットなし、2 文字以下は頻出語・1 文字・まれな語・ヒットなし。いずれも上限 50 件・スニペット生成込み。
- 出力: 標準出力に `SEARCH_BENCHMARK profile=… segments=… days=… chars_per_segment=… median_segment_s=… db_mb=… bytes_per_segment=… fts_p50_ms=… fts_p95_ms=… like_p50_ms=… like_p95_ms=… all_p95_ms=… max_ms=… target_ms=300 …` の 1 行（`key=value` 区切り）と、クエリ別の表（Markdown、`shared/core-db/build/reports/search-benchmark/summary.md`）。`like_*` は 2 文字以下の経路（下記の通り LIKE と語彙の組み合わせ）。
- 判定: どれか 1 つのクエリの p95 が 300 ms を超えるとテストが失敗する。
- CI: `android.yml` の「Search benchmark (3.2M segments, target 300 ms)」ステップで毎回 1 年分（`-Pbenchmark.segments=3200000`）を実行し、表をジョブサマリーに出す。

### 計測結果

クラウドの Linux（4 CPU、JVM 21）と GitHub Actions の ubuntu-latest（4 CPU、JVM 21。PR #84 の [android.yml の実行](https://github.com/Soichiro5/watch-audio/actions/runs/38114188178)）。#23 は 2026-10-10、#72 は 2026-10-11。

| データ | 対策 | FTS（3 文字以上）p50 / p95 | 2 文字以下 p50 / p95 | 最悪のクエリ p95 | DB |
| --- | --- | --- | --- | --- | --- |
| `sentences` 50 万 | #23 の改善前 | 139 / 260 ms | 0.8 / 212 ms | 327 ms（2 文字・ヒットなし） | - |
| `sentences` 50 万 | #23 の改善後 | 30 / 48 ms | 0.8 / 128 ms | 148 ms（2 文字・ヒットなし） | 281 MB |
| `utterances` 30 万 | #72 の改善前 | 7.2 / 13.5 ms | 1.0 / 49 ms | 62 ms（2 文字・まれ / ヒットなし） | 111 MB（371 B/行） |
| `utterances` 30 万 | #72 の改善後 | 7.6 / 13.9 ms | 2.0 / 8.9 ms | 22 ms（「ミーティング」） | 101 MB（335 B/行） |
| **`utterances` 320 万（1 年 384 日分、CI）** | #72 の改善後 | **56.6 / 98.2 ms** | **0.9 / 7.1 ms** | **101 ms（「ミーティング」）** | **1,018 MB（318 B/行）** |

#72 の改善前の 2 文字以下の検索は全文を走査するので、件数に比例して 320 万行では 0.5〜0.7 秒になる見込みだった（30 万行で 62 ms）。

### 改善内容

#23（`StoreTransaction.search`、マイグレーション 4）:

- FTS5 の `snippet()` は ORDER BY / LIMIT の前に**全ヒット行**で計算されるため、頻出語で検索時間の大半を占めていた。FTS ではセグメント ID だけを新しい順に並べて上位 50 件に絞り、スニペット（一致箇所の `[` `]` と前後の切り詰め）は返す行だけ Kotlin で作る（`highlightSnippet`）。

#72（マイグレーション 5）:

- 2 文字以下の検索は、まず**新しい 20,000 セグメント**（発話 2 日分ほど）だけを LIKE で調べる。頻出語はここで 50 件そろう。
- そろわなければ、trigram 索引の**語彙**（`fts5vocab` の instance テーブル `segment_fts_terms`）から「クエリで始まる trigram」を範囲検索し、そのセグメントを新しい順に返す。全文の走査はしない。ヒットなし・まれな語は数 ms で終わる。
- 末尾の文字も必ずどれかの trigram の先頭になるよう、索引には本文の後ろに改行 2 つを付けて登録する。そのため FTS は本文を持たない contentless 表（`contentless_delete=1`、行 ID で削除）にした。
- LIKE で全件を走査しなくなったので、#23 で追加した `(start_at, text)` の索引を `start_at` だけの索引に戻した（1 行あたり約 40 B、1 年で約 130 MB 減）。
- 発話の行をまとめて「検索用の行」を作る案（隣り合う短いセグメントを保存時に結合）も試算したが、2 文字以下の全件走査は文字数に比例するため 1.5〜2 倍しか速くならず、本文の重複で DB が 2 割ほど増えるので採らなかった。

実機（中位機種）での値は未計測（#61）。スマホの CPU・ストレージは上の環境より遅いので、実機入手後に同じクエリで確認する。

## 6. CI での自動確認（実機なし）

| ワークフロー | ステップ | 確認できること | 証拠の場所 |
| --- | --- | --- | --- |
| `android.yml` | Local-only check (static) / (merged manifests, resolved dependencies) | 「データは端末内のみ」: 権限・バックアップ設定・通信 API と URL・依存 SDK・iOS の Info.plist と entitlements が許可リストの範囲内（§6.4、#76） | ジョブのログ、ジョブサマリーの「Local-only check」（通信箇所の一覧） |
| `android.yml` | Unit tests | 共通ロジック（JVM・Android ホスト）。SQLite を使う core-db / Ingestor / パイプラインのテストは JVM で実行 | テスト結果 |
| `android.yml` | Search benchmark | 1 年分（320 万セグメント）での検索応答時間と DB 容量（§5） | ジョブサマリーの表と `SEARCH_BENCHMARK` 行 |
| `android.yml` | Unit tests（composeApp の `AppUiTest`） | 画面の描画（タイムライン・検索・状態・オンボーディング。権限拒否時の「設定を開く」#32 を含む） | 成果物 `ui-screenshots`（`composeApp/build/screenshots/*.png`） |
| `android.yml` | Assemble release (R8) + debug input leak check | スマホ・Wear OS の release ビルド（R8 で縮小・難読化）が通る。Wear OS の debug 専用の音声ファイル入力（#57）が release に入っていない（`verifyReleaseDebugInput`）（#78） | ジョブサマリーの APK 一覧と `debug-input/release.txt` の内容 |
| `emulator.yml`（phone / wear） | Install, launch and check for crashes | release ビルド（R8、CI で作る使い捨ての鍵で署名）→ debug ビルドの順に入れて起動し、15 秒後もクラッシュしていない（`launch-smoke.sh`。R8 の keep 漏れによる起動時のクラッシュを検出、#78） | ジョブサマリーの `LAUNCH_CHECK` 行、成果物 `logcat-*`（`logcat-release.txt` / `logcat.txt`） |
| `emulator.yml`（phone） | 同上（続けて実行） | 計装テスト: 実際の音声（AAC）を MediaPlayer で指定位置から再生できる・検索結果を 1 タップでその発話から再生できる（`PlaybackTest`）、SQLite のテストを Android 上で実行（core-db / core-pipeline） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK` 行、成果物 `logcat-phone`（テストレポート） |
| `emulator.yml`（phone） | 同上（計装テスト） | 実モデルでの文字起こし: `AndroidSherpaTranscriber`（MediaCodec + sherpa-onnx AAR）が日本語の参照音声チャンクを文字起こしして DB に保存・検索できる（`SherpaTranscriberTest`、#22） | `ASR_CHECK` / `ASR_SEGMENT` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | モデルなしでも受信・再生でき文字起こしは待機（チャンクは処理待ちのまま・失敗扱いにしない）、モデルを置くと自動で処理が進む（`ModelWaitTest`、#22） | `MODEL_CHECK` 行、スクリーンショット `status-model-missing.png` / `status-model-ready.png` |
| `emulator.yml`（phone） | 同上（計装テスト） | 定期ジョブ（6 時間ごと・充電中のみ）は充電されるまで動かず、充電条件を満たすと溜まったチャンクを文字起こしする（WorkManager の `TestDriver`、`ChargingWorkTest`、#20） | `CHARGING_CHECK` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | 録音の途絶アラートが本番の `GapAlertMonitor` から通知され、通知シェードに表示される（`GapAlertNotificationTest`、#43） | `GAP_ALERT_CHECK` 行、スクリーンショット `gap-alert-notification.png` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 通知権限をシステムのダイアログで拒否 → 理由と「設定を開く」が表示され、押すとアプリの設定画面が開く（`PermissionDeniedTest`、#32） | `PERMISSION_CHECK` 行、スクリーンショット `permission-system-dialog.png` / `onboarding-permission-denied.png` / `app-settings-opened.png` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 文字起こしの途中で `am force-stop` → 再起動で全チャンクが 1 回ずつ文字起こしされる（欠落・重複 0、中断したチャンクは 2 回目で完了）（`ResumeAfterKillTest`、#20） | `RESUME_CHECK` 行、成果物 `logcat-phone` の `scenario-logs/` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 診断ログ: チャンクの受信（`ChunkReceiver.store`）→ 文字起こし → `am force-stop` → 再起動で、ログに `chunk_received`・`transcription_started` / `_finished`（RTF つき）・`app_start`・強制終了の `previous_exit`（OS が報告した終了理由）が残り、状態タブの「診断ログを書き出す」で CSV が書き出されて共有シートが開く（書き出しに文字起こし本文を含まない）（`DiagnosticsScenarioTest`、#77） | `DIAGNOSTICS_CHECK` 行と書き出した CSV の抜粋（`DIAGNOSTICS_EXPORT`）、スクリーンショット `diagnostics-export.png`、成果物 `logcat-phone` の `scenario-logs/diagnostics-export.csv` |
| `emulator.yml`（wear） | 同上（`wear-surfaces.sh`） | Tile とコンプリケーションの表示（停止中 → Tile のタップでアプリが開き録音開始 → 録音中）（#41） | `WEAR_SURFACE_CHECK` 行、スクリーンショット `wear-tile-*.png` / `wear-complication-*.png` |
| `ios.yml` | Shared tests (iOS simulator) | 共通ロジックを iOS シミュレータで実行（SQLite を使う core-db / Ingestor のテストを含む）。composeApp の `IosPlaybackTest`: `IosAppServices.play` 直接・共有 UI で前日のタイムラインの発話タップ／検索結果の 1 タップのそれぞれで、そのチャンクの AAC が AVAudioPlayer に指定位置（±0.2 秒）・正しい長さで用意される（#19, #25。テスト実行ファイルはアプリではないので再生開始 `started=` は記録のみ）。`IosTranscriberBridgeTest`: Swift の文字起こし結果が絶対時刻つきで DB・FTS に入る（#21） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK` 行 |
| `ios.yml` | Shared tests (iOS simulator) / Swift unit tests | iPhone の DB・音声・受信ディレクトリ（`LocalDataDirectoryTest`）と Watch の送信待ちディレクトリ（`LocalDataProtectionTests`、`ChunkOutboxTests`）がバックアップ対象外で、保護クラス `CompleteUntilFirstUserAuthentication` を設定できる（シミュレータは保護クラスを報告しないことがあるため、値は報告される場合だけ比較。#76） | ジョブサマリーのテスト一覧、ログ |
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
- **診断ログと前回の終了理由（#77）**: 同じく 3 段階。① `DiagnosticsScenarioTest#receive` が参照音声 1 文のチャンクを時計と同じフレームにして `ChunkReceiver.store` に渡し（`chunk_received` が記録され、受信時と同じく文字起こしが要求される）、本番のワーカーが文字起こしを終えるまで待つ、② スクリプトが `am start` でアプリを起動し、3 秒後に `adb shell am force-stop app.watchaudio`（前後の端末時刻を記録）、③ `DiagnosticsScenarioTest#verify` が次のプロセスで、ログに ① のチャンクの各イベント・この起動の `app_start`・強制終了の時刻に当たる `previous_exit` があることを確認し、状態タブの「診断ログを書き出す」を押して共有シート（`ChooserActivity`）が開くこと・書き出した CSV にログの行があり文字起こし本文がないことを確かめる。書き出した CSV はスクリプトが `run-as` で `scenario-logs/diagnostics-export.csv` に取り出す。

手元で同じことをする場合（KVM のある環境、`tools/harness/download-models.sh` 済み）:

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
.github/scripts/phone-scenarios.sh            # モデルの push、権限拒否、強制終了からの再開、診断ログ
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireModels=true
adb pull /data/local/tmp/watchaudio-screenshots
```

### 6.3 どの変更でどのワークフローが動くか（#92）

PR の CI 待ちを減らすため、変更したファイルに関係するワークフローだけを動かす。`workflow_dispatch` からはいつでも手動で全部動かせる。

| 変更したファイル | `android.yml` | `emulator.yml` | `ios.yml` | `deliver.yml`（main のみ） |
| --- | --- | --- | --- | --- |
| ドキュメントだけ（`docs/**`、`**/*.md`、Issue / PR テンプレート、`tools/roadmap-view/**`、`research/**`、`spikes/**`） | 動かない | 動かない | 動かない | 動かない |
| iOS 専用（`iosApp/**`、`src/iosMain`・`src/iosTest`、iOS 用スクリプト） | 動く | 動かない | 動く | 動く |
| Android 専用（`androidApp/**`、`wearApp/**`、`src/androidMain`・`androidHostTest`・`androidDeviceTest`、Android 用スクリプト） | 動く | 動く | 動かない | 動く |
| JVM 専用（`src/jvmMain`・`src/jvmTest`） | 動く | 動かない | 動かない | 動く |
| 共通コード（`src/commonMain` など）・Gradle の設定・`build-logic/**` | 動く | 動く | 動く | 動く |

- `android.yml` と `deliver.yml` は除外リスト（`paths-ignore`）。ドキュメント以外の変更では必ず動く。
- `emulator.yml` と `ios.yml` は対象リスト（`paths`）に除外（`!`）を足している。除外は後ろに書いたものが優先されるので、新しい除外は対象リストより後ろに書く。
- 新しいスクリプトを `.github/scripts/` に足すときは、片方のプラットフォーム専用なら、もう片方のワークフローの除外に追加する。
- フォルダ構成を変えるとき（#49）は、上の表のパスもあわせて直す。

### 6.4 Local-only チェック（`tools/privacy-check`、#76）

CLAUDE.md の「Local only」（録音・文字起こし・ユーザーデータを端末の外に出さない。通信はモデルの取得だけ）を CI で守るための静的チェック。Python の標準ライブラリだけで動き、`android.yml` で 2 回実行する。

1. **最初のステップ（ビルド不要、数秒）**: `test_check.py`（違反を 1 つずつ入れたフィクスチャで失敗することを確かめるテスト）と、リポジトリのファイルに対するチェック。
2. **Assemble の後**: リリースのマージ後マニフェスト（`processReleaseMainManifest`）と、`./gradlew :<app>:dependencies --configuration releaseRuntimeClasspath` の出力（推移的な依存を含む）も検査し、通信箇所の一覧をジョブサマリーに出す。

| 検査 | 対象 | 失敗する例 |
| --- | --- | --- |
| 権限 | `androidApp` / `wearApp` の `src/*/AndroidManifest.xml`、マージ後マニフェスト | Wear OS に `INTERNET`、スマホに位置情報・広告 ID、依存が勝手に足した権限 |
| バックアップ | `<application>` と `res/xml` のルール | `allowBackup="true"`、`dataExtractionRules` なし、除外していないドメイン、`<include>`、`usesCleartextTraffic="true"` |
| 通信・保護 | `androidApp/src`・`wearApp/src`・`composeApp/src`・`shared`・`iosApp` の `.kt` / `.java` / `.swift`（コメントと、出荷されないテストコード = `test` や `Test` を含むディレクトリは除外） | 許可されていないファイルでの `HttpURLConnection`・`URLSession`・ソケット・WebView・`SFSpeech…RecognitionRequest`、`DataClient` / `PutDataRequest`、`.noFileProtection`、許可されていない URL、使われなくなった許可リストの項目 |
| 依存（宣言） | `gradle/libs.versions.toml` と `*.gradle.kts`（`spikes/` を除く） | firebase・crashlytics・sentry・amplitude などの解析・クラッシュ送信 SDK、okhttp・retrofit・ktor などの HTTP クライアント |
| 依存（解決結果） | リリースの実行時クラスパス | 許可リスト（AndroidX、Kotlin、Compose、play-services-wearable、sherpa-onnx など）にないモジュール |
| iOS | `iosApp/project.yml`、コミットされた `*.entitlements` | 許可リストにない Info.plist のキー（`NSAppTransportSecurity`、位置情報など）、`UIBackgroundModes`、entitlements |

手元での実行:

```bash
python3 -m unittest discover -s tools/privacy-check -v
python3 tools/privacy-check/check.py
# ビルド後の検査も（assembleDebug などの後）
./gradlew --max-workers=2 :androidApp:processReleaseMainManifest :wearApp:processReleaseMainManifest
./gradlew --max-workers=2 -q :wearApp:dependencies --configuration releaseRuntimeClasspath > /tmp/deps-wearApp.txt
python3 tools/privacy-check/check.py --resolved-deps wearApp=/tmp/deps-wearApp.txt \
  --merged-manifest wearApp=wearApp/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
```

**許可リストを広げるとき**（新しい権限・依存・通信先が本当に必要な場合）:

1. `tools/privacy-check/policy.py` の該当する表に、理由を添えて追加する（権限は `ANDROID_APPS`、ライブラリが足す権限は `library_permissions`、通信するファイルは `ALLOWED_API_USES`、URL は `ALLOWED_URL_PREFIXES`、依存は `ALLOWED_RESOLVED_DEPENDENCIES`、iOS は `IOS_TARGETS` / `IOS_ENTITLEMENTS`）。拒否パターン（`GUARDED_APIS`・`DENIED_DEPENDENCIES`）は緩めず、許可リスト側に足す。
2. ユーザーデータが端末の外に出ないことを PR の本文で説明する。データを外に出す変更（AI プロバイダなど P2）は、CLAUDE.md の方針の変更として Issue で合意してから。
3. [02 §7](02-architecture.md#7-セキュリティプライバシー) の保存場所・通信箇所の一覧を更新し、`test_check.py` の期待値（通信箇所の集合など）も直す。

## 7. CI の設定と成果物の扱い（公開リポジトリ）

リポジトリは公開なので、CI のログと成果物（artifact）は GitHub にログインした人なら誰でも見られる・ダウンロードできる（#79）。

- **ランナーは固定**: Linux は `ubuntu-24.04`（`ubuntu-latest` の Ubuntu 26 への切り替えで壊れないように）、macOS は `macos-26`。新しいイメージへは、全ジョブが通ることを PR で確かめてから移る。
- **権限は最小**: 各 workflow は `permissions: contents: read`。書き込みが必要なジョブを足すときは、そのジョブにだけ付ける。`pull_request_target` は使わない（fork の PR に secret が渡るため）。
- **外部アクションは SHA で固定**し、`.github/dependabot.yml` が週 1 回まとめて更新 PR を出す。
- **秘密情報**: GitHub Actions の Secrets にだけ置き、ログに出さない（加工して出力すると伏せ字を回避してしまうので注意）。現時点で secret を使うのは `android.yml` の release 署名だけ（§7.1）。

成果物の中身（どれも実データ・個人データ・秘密情報を含まない）:

| 成果物 | workflow | 中身 | 保持 |
| --- | --- | --- | --- |
| `debug-apks` / `watch-audio-android-<sha>` | android.yml / deliver.yml | debug 署名の APK（スマホ・Wear OS）。API キーは入っていない。モデルは同梱せず初回にダウンロード | 7 / 30 日 |
| `release-apks` | android.yml | release 鍵で署名した R8 の APK（スマホ・Wear OS）。**署名用の secret があるときだけ**作る（ないときは署名なしでビルドだけ確認し、アップロードしない）。鍵そのものは入っていない | 7 日 |
| `ui-screenshots` | android.yml | JVM の UI テストが描いた画面（テスト用の合成データ） | 30 日 |
| `logcat-phone` / `logcat-wear` | emulator.yml | エミュレータの logcat・計装テストのレポート（合成音声・テスト用 DB のみ。文字起こし本文はテスト音声の正解文） | 7 日 |
| `screenshots-phone` / `screenshots-wear` | emulator.yml | エミュレータのスクリーンショット | 30 日 |
| `xcodebuild-logs` / `simulator-smoke` | ios.yml | ビルドログ、シミュレータのスクリーンショットとアプリログ | 7 日 |

成果物を Issue の証跡として参照する場合、保持期間を過ぎると消える点に注意する（コメントには数値・ログ行を本文として残す）。

### 7.1 Android の release ビルドと署名（#78）

- **設定の場所**: `build-logic` の `AndroidReleaseConventionPlugin`（プラグイン ID `watchaudio.android.release`）を `androidApp` と `wearApp` の両方に適用している。release は R8 によるコードの縮小・難読化とリソースの縮小（`isMinifyEnabled` / `isShrinkResources`）を行い、keep ルールは各モジュールの `proguard-rules.pro`。スマホと Watch は applicationId が同じ `app.watchaudio` で、Data Layer でつながるには**同じ鍵で署名する必要がある**ため、署名の設定は 1 か所にまとめている。
- **鍵の渡し方**: 鍵はリポジトリに置かず、Gradle は次の環境変数から読む。4 つとも未設定なら署名なし（`*-release-unsigned.apk`）、一部だけ設定されていれば値を出さずにビルドを失敗させる（secret の設定漏れで黙って署名なしになるのを防ぐ）。

| Gradle が読む環境変数 | 中身 | CI で渡す GitHub Actions secret |
| --- | --- | --- |
| `WATCHAUDIO_RELEASE_STORE_FILE` | キーストアのパス | `ANDROID_RELEASE_KEYSTORE_BASE64`（キーストアを base64 にしたもの。CI がランナーの一時ディレクトリに復元し、ジョブの最後に消す） |
| `WATCHAUDIO_RELEASE_STORE_PASSWORD` | キーストアのパスワード | `ANDROID_RELEASE_KEYSTORE_PASSWORD` |
| `WATCHAUDIO_RELEASE_KEY_ALIAS` | 鍵のエイリアス | `ANDROID_RELEASE_KEY_ALIAS` |
| `WATCHAUDIO_RELEASE_KEY_PASSWORD` | 鍵のパスワード | `ANDROID_RELEASE_KEY_PASSWORD` |

- **CI の動き**: `android.yml` は毎回 `:androidApp:assembleRelease :wearApp:assembleRelease` を実行する（Wear OS の `verifyReleaseDebugInput` もここで走る）。secret があれば release 鍵で署名して成果物 `release-apks` に上げ、なければ（現在の状態。fork からの PR にも secret は渡らない）署名なしでビルドだけ確認する。`emulator.yml` は release 鍵を使わず、ジョブ内で `keytool` で作った使い捨ての鍵で署名して起動確認する（鍵とパスワードはそのジョブ限りで、配布物には使わない）。
- **鍵の作り方**（一度だけ。リポジトリの外で作業し、キーストアをコミットしない）:

```bash
keytool -genkeypair -keystore watch-audio-release.jks -alias watch-audio \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=watch-audio"
# パスワードは対話で入力する（コマンドラインやシェル履歴に残さない）
base64 -w0 watch-audio-release.jks > keystore.b64   # macOS: base64 -i watch-audio-release.jks -o keystore.b64
```

- **secret の登録**: GitHub のリポジトリの Settings → Secrets and variables → Actions → New repository secret で上の 4 つを登録する（`gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 < keystore.b64` でも可。パスワードは `gh secret set <名前>` を実行して対話で入力）。登録後に `keystore.b64` は削除する。
- **保管**: キーストアとパスワードはパスワードマネージャーなど、リポジトリ・Issue・CI ログ以外の場所に保管する。**鍵をなくすと同じアプリとして更新できない**（Play で配信する場合は Play App Signing を使い、ここでの鍵はアップロード鍵として扱う。#35）。
- **更新・漏えい時**: 新しい鍵を作って 4 つの secret を上書きする。既に入れた端末では署名が変わるため、スマホ・Watch とも一度アンインストールしてから入れ直す（Play App Signing を使っていればアップロード鍵のリセットを申請する）。
- **手元での release ビルド**: 署名なしなら `./gradlew --max-workers=2 :androidApp:assembleRelease :wearApp:assembleRelease`。署名する場合は上の 4 つの環境変数を設定してから実行する。

## 8. 実機検証の手順

- M0-3 / M0-4 電池計測: [docs/spikes/m0-3-m0-4-battery-protocol.md](spikes/m0-3-m0-4-battery-protocol.md)
- Wear OS 録音アプリ（常時録音・転送）の手動テスト: [docs/impl/wearos.md §10](impl/wearos.md#10-実機での手動テスト-チェックリスト)

## 9. 検証データの扱い

- 録音検証では、**参加者全員に録音・文字起こしの目的を説明し同意を得る**。
- 実際の会話音声はリポジトリにコミットしない（個人情報）。評価用の正解テキストと集計結果のみ共有する。
