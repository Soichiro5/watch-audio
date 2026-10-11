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
| `android.yml` | Unit tests | 共通ロジック（JVM・Android ホスト）。SQLite を使う core-db / Ingestor / パイプラインのテストは JVM で実行 | テスト結果 |
| `android.yml` | Search benchmark | 1 年分（320 万セグメント）での検索応答時間と DB 容量（§5） | ジョブサマリーの表と `SEARCH_BENCHMARK` 行 |
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
| `ios.yml` | Shared tests (iOS simulator) | 共通ロジックを iOS シミュレータで実行（SQLite を使う core-db / Ingestor のテストを含む） | ジョブサマリーのテスト一覧 |
| `ios.yml` | Launch iOS app / Launch watchOS app | ビルドした iOS・watchOS アプリをシミュレータに入れて起動し、15 秒後もプロセスが生きていてクラッシュレポートがない | ジョブサマリー、成果物 `simulator-smoke`（スクリーンショット・ログ） |

スクリーンショットは成果物 `screenshots-phone` / `screenshots-wear`（30 日保存）、`*_CHECK` 行はジョブサマリーにまとめて出す。

SQLite（bundled ドライバ）を開くテストは `src/sqliteTest` に置く（`watchaudio.kmp.sqlite-tests` プラグイン）。`jvmTest`・`iosTest`・`androidDeviceTest` から共有され、JVM・iOS シミュレータ・Android 端末/エミュレータで実行される。Android のホストテスト（`testAndroidHostTest`）では実行しない: Android 版の `sqlite-bundled` は Android の ABI 向けネイティブライブラリしか含まず、ホストの JVM では読み込めないため。

- core-db（`LocalStoreTest`・`StorageQueriesTest`）と core-pipeline（`IngestorTest`・`TranscriptionPipelineTest`・`StorageManagerTest`）: JVM・iOS シミュレータ・Android 端末/エミュレータ。
- composeApp（`GapAlertEvaluatorTest`）: JVM・iOS シミュレータ（`watchaudio.kmp.sqlite-tests-jvm-ios`）。Compose Multiplatform のリソースプラグインが KMP ライブラリの Android デバイステスト用バリアントを構成できないため、Android 端末では実行しない。
- composeApp の `ViewModelTest`・`AppUiTest` は JVM のみ（`Dispatchers.Main` に Swing、描画にデスクトップの Skia を使うため）。

手元での実行:

```bash
# iOS 向けテストのコンパイル確認（Linux、リンクなし）
./gradlew --max-workers=2 :shared:core-db:compileTestKotlinIosSimulatorArm64 :shared:core-pipeline:compileTestKotlinIosSimulatorArm64 \
  -Pkotlin.native.enableKlibsCrossCompilation=true
# Android エミュレータ / 実機を接続して（KVM のある環境）
./gradlew :androidApp:connectedDebugAndroidTest :shared:core-db:connectedAndroidDeviceTest :shared:core-pipeline:connectedAndroidDeviceTest
adb logcat -d | grep -E "[A-Z_]+_CHECK"
# Mac: ビルド済みアプリをシミュレータで起動確認
.github/scripts/simulator-smoke.sh iOS iPhone build/DerivedData/Build/Products/Debug-iphonesimulator/WatchAudio.app app.watchaudio
```

未対応: iOS の再生（`IosAppServices.play`）を確かめるシミュレータテスト。テスト用の AAC の生成と XCTest のホストアプリ設定が必要で、macOS の CI 時間（10 倍課金）もかかるため、現状はシミュレータでの起動確認までとする。

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
