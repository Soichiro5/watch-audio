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
./gradlew :apps:android:installDebug   # スマホ
./gradlew :apps:wear:installDebug      # Wear OS（adb でウォッチを接続）

# iOS / watchOS（Mac）
cd apps/ios
echo 'TEAM_ID = XXXXXXXXXX' > Configuration/Local.xcconfig   # 自分の Team ID
echo 'BUNDLE_ID_PREFIX = com.example.watchaudio' >> Configuration/Local.xcconfig  # 一意な Bundle ID
xcodegen generate
open WatchAudio.xcodeproj   # iosApp スキームを実機に Run（Watch アプリも一緒に入る）
```

Linux でも Kotlin の iOS 向けコード（`iosMain`）の**コンパイルまで**は確認できる（klib クロスコンパイル。リンクとフレームワーク生成は Mac のみ）:

```bash
./gradlew :shared:ui:compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

iOS / watchOS 側の構成は [docs/platforms/ios-watchos.md](../platforms/ios-watchos.md) を参照。

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

正解テキスト付きの日本語会話（オフライン TTS）を Watch 録音を模して加工した検証用音声セットと、それをハーネスに流して条件ごとの CER・検出漏れ・処理時間を出す `evaluate` コマンドがある（#56）。生成手順・条件・ライセンス・結果は [docs/dev/test-audio.md](test-audio.md)。

意味検索（#24）も同じハーネスで確かめる（埋め込みモデル ruri-v3-30m は `download-models.sh` が取得する）。`:tools:harness:test` の `EmbedderParityTest`（Kotlin の埋め込みが Python と一致）と `SemanticSearchEvalTest`（言い換えのクエリの上位 5 件のヒット率、キーワードのみ と ハイブリッド。表は `tools/harness/build/reports/semantic-eval.md`）に加えて:

```bash
./gradlew :tools:harness:run --args="semantic-eval /tmp/wa-eval --min-tokens 24"                       # 結合の条件を変えて評価
./gradlew :tools:harness:run --args="semantic-bench /tmp/wa-bench 100000,500000,1100000"             # 総当たり検索の時間（ダミーのベクトル）
```

結果は [research/m0-6 §9](../research/m0-6-embedding-desktop.md#9-追記-実装後の-jvm-での確認242026-10-12)。

## 5. 検索の性能ベンチマーク（#23, #72）

1 年分相当の日本語の文字起こしを決定的に生成し、`StoreTransaction.search` の応答時間を測る。通常の `jvmTest` には含めない（1 年分の投入に数分かかるため）。

```bash
./gradlew --max-workers=2 :shared:db:jvmBenchmark                       # 1 年分（320 万セグメント、約 650 MB）、各クエリ 40 回
./gradlew --max-workers=2 :shared:db:jvmBenchmark -Pbenchmark.segments=50000 -Pbenchmark.rounds=5   # 手早く試す（約 13 MB）
./gradlew --max-workers=2 :shared:db:jvmBenchmark -Pbenchmark.profile=sentences -Pbenchmark.segments=500000   # #23 の前提（長めのセグメント）
```

1 年分の DB は約 650 MB になる（#94 の前は約 1 GB）。ディスクの空きが少ない環境では件数を減らして試し、1 年分は CI で確認する。`-Pbenchmark.keepDb=<パス>` を付けると、終了後に DB をそのパスへ写す（表・索引ごとの容量を `dbstat` などで調べる用。Python の `sqlite3` で `SELECT name, SUM(pgsize) FROM dbstat GROUP BY name` が使える）。

### 生成するデータ（`TranscriptGenerator`）

| プロファイル | 内容 | 1 日（装着 16 時間）| 1 年 |
| --- | --- | --- | --- |
| `utterances`（既定、#72） | #68（PR #70）以降のパイプラインと同じ「話者交代の間で切った発話」単位。1 行あたり平均 11.8 文字・長さの中央値 1.75 秒。会話のあるチャンク（42%）に 70〜120 発話を詰め、残りのチャンクは 0〜12 発話 | 約 8,600 セグメント | 約 314 万 → **320 万**で測る |
| `sentences`（#23） | 5 分チャンク × 約 10 発話、1 行 1〜3 文（約 30 文字） | 約 1,400 セグメント | 約 50 万 |

`utterances` の件数の根拠（[02 §9.3](../architecture/02-architecture.md#93-データ量の見積もり)）: 装着 16 時間のうち発話は約 31%（約 5 時間、[M0-5 §5](../research/m0-5-asr-desktop.md#5-1-日分の処理時間の見積もりスマホ)）。検証用音声セット（[test-audio.md](test-audio.md)）の会話は発話 24 分で 505 発話・688 文・約 8,000 文字、PR #70 の評価では保存されたセグメントの長さの中央値が clean 1.7 秒・wrist 1.9 秒で、ほぼ 1 文 = 1 セグメントになる。発話 1 時間あたり約 1,720 セグメント × 5 時間 = 1 日約 8,600。雑音のある条件（office・cafe・day など）では話者交代で切れず中央値 5〜10 秒なので、行数は少なくなる。静かな条件の値を上限として使う。

チャンク ID は UUID、エンジン ID は実際と同じ長さにして、1 行あたりの容量もアプリと同じになるようにしている。

### 対象クエリと出力

- 対象クエリ: 3 文字以上（FTS5 trigram）は頻出語・フレーズ・まれな語・ヒットなし、2 文字以下は頻出語・1 文字・まれな語・ヒットなし。いずれも上限 50 件・スニペット生成込み。
- 出力: 標準出力に `SEARCH_BENCHMARK profile=… segments=… days=… chars_per_segment=… median_segment_s=… db_mb=… bytes_per_segment=… fts_p50_ms=… fts_p95_ms=… like_p50_ms=… like_p95_ms=… all_p95_ms=… max_ms=… target_ms=300 …` の 1 行（`key=value` 区切り）と、クエリ別の表（Markdown、`shared/db/build/reports/search-benchmark/summary.md`）。`like_*` は 2 文字以下の経路（下記の通り LIKE と語彙の組み合わせ）。
- 判定: どれか 1 つのクエリの p95 が 300 ms を超えるとテストが失敗する。
- CI: `android.yml` の「Search benchmark (3.2M segments, target 300 ms)」ステップで毎回 1 年分（`-Pbenchmark.segments=3200000`）を実行し、表をジョブサマリーに出す。

### 計測結果

クラウドの Linux（4 CPU、JVM 21）と GitHub Actions の ubuntu-latest（4 CPU、JVM 21。PR #84 の [android.yml の実行](https://github.com/Soichiro5/watch-audio/actions/runs/38114188178)）。#23 は 2026-10-10、#72・#94 は 2026-10-11。手元の値は他のビルドと同じマシンで測ったもので、CI より遅めに出る。DB の容量は WAL を含む。

| データ | 対策 | FTS（3 文字以上）p50 / p95 | 2 文字以下 p50 / p95 | 最悪のクエリ p95 | DB |
| --- | --- | --- | --- | --- | --- |
| `sentences` 50 万 | #23 の改善前 | 139 / 260 ms | 0.8 / 212 ms | 327 ms（2 文字・ヒットなし） | - |
| `sentences` 50 万 | #23 の改善後 | 30 / 48 ms | 0.8 / 128 ms | 148 ms（2 文字・ヒットなし） | 281 MB |
| `utterances` 30 万 | #72 の改善前 | 7.2 / 13.5 ms | 1.0 / 49 ms | 62 ms（2 文字・まれ / ヒットなし） | 111 MB（371 B/行） |
| `utterances` 30 万 | #72 の改善後 | 7.6 / 13.9 ms | 2.0 / 8.9 ms | 22 ms（「ミーティング」） | 101 MB（335 B/行） |
| `utterances` 320 万（1 年 384 日分、CI） | #72 の改善後 | 56.6 / 98.2 ms | 0.9 / 7.1 ms | 101 ms（「ミーティング」） | 1,018 MB（318 B/行） |
| `utterances` 320 万（1 年分、手元） | #94 の前（main 5e2fa2e） | 73.4 / 130.7 ms | 1.7 / 14.8 ms | 173 ms（「ミーティング」） | 1,018 MB（318 B/行） |
| **`utterances` 320 万（1 年分、手元）** | **#94 の後（整数の参照、マイグレーション 6）** | **63.1 / 103.5 ms** | **1.5 / 11.1 ms** | **136 ms（「ミーティング」）** | **644 MB（201 B/行）** |

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

#94（マイグレーション 6）:

- 容量の削減。セグメントと印はチャンクを UUID の文字列ではなく `audio_chunk.id`（整数）で、エンジンは `engine` 表の整数で参照する。検索の手順は変えず、返す 50 行だけをチャンク・エンジンの表と主キーで結合して UUID とエンジン名に戻す（`Rows.kt` の `SEGMENTS`）。

実機（中位機種）での値は未計測（#61）。スマホの CPU・ストレージは上の環境より遅いので、実機入手後に同じクエリで確認する。

## 6. CI での自動確認（実機なし）

| ワークフロー | ステップ | 確認できること | 証拠の場所 |
| --- | --- | --- | --- |
| `android.yml` | Local-only check (static) / (merged manifests, resolved dependencies) | 「データは端末内のみ」: 権限・バックアップ設定・通信 API と URL・依存 SDK・iOS の Info.plist と entitlements が許可リストの範囲内（§6.4、#76） | ジョブのログ、ジョブサマリーの「Local-only check」（通信箇所の一覧） |
| `android.yml` | Unit tests | 共通ロジック（JVM・Android ホスト）。SQLite を使う shared/db / Ingestor / パイプラインのテストは JVM で実行 | テスト結果 |
| `android.yml` | Search benchmark | 1 年分（320 万セグメント）での検索応答時間と DB 容量（§5） | ジョブサマリーの表と `SEARCH_BENCHMARK` 行 |
| `android.yml` | Unit tests（shared/ui の `AppUiTest`） | 画面の描画（タイムライン・検索・状態・オンボーディング。権限拒否時の「設定を開く」#32 を含む） | 成果物 `ui-screenshots`（`shared/ui/build/screenshots/*.png`） |
| `android.yml`・`deliver.yml`・`emulator.yml`（phone・`release-test-apks`） | sherpa-onnx JNI without TTS（`.github/actions/sherpa-onnx-jni`） | 音声合成と話者分離を外した JNI ライブラリを `libs.versions.toml` の版の上流タグからビルドでき（キャッシュが外れたとき）、eSpeak NG の痕跡がなく、AAR と同じ `libonnxruntime.so` にリンクしている。以降のビルドの APK がそれを入れている（`verify<Variant>SherpaOnnxJni`）（#173。§7.3） | ジョブサマリーの「sherpa-onnx JNI without TTS」（キャッシュのヒット・ABI ごとのサイズと SHA-256）、ジョブのログ |
| `android.yml`・`deliver.yml`・`emulator.yml` | sherpa-onnx JNI without TTS（APK の検査） | できた APK（`android.yml` は release と debug、`deliver.yml` は配る phone の APK、`emulator.yml` はエミュレータで確かめる APK）の `libsherpa-onnx-jni.so`（全 ABI）に eSpeak NG（GPL-3.0）と音声合成・話者分離の痕跡がない（`check-no-tts.sh`）。あれば失敗し、`deliver.yml` は何も配らない | ジョブのログ（ABI ごとの `OK` 行とサイズ・SHA-256、`build/reports/sherpa-onnx-jni/<variant>.txt`） |
| `android.yml` | Assemble release (R8) + debug input leak check | スマホ・Wear OS の release ビルド（R8 で縮小・難読化）が通る。Wear OS の debug 専用の音声ファイル入力（#57）が release に入っていない（`verifyReleaseDebugInput`）（#78） | ジョブサマリーの APK 一覧と `debug-input/release.txt` の内容 |
| `emulator.yml`（phone / wear） | Install, launch and check for crashes | release ビルド（R8、CI で作る使い捨ての鍵で署名）→ debug ビルドの順に入れて起動し、15 秒後もクラッシュしていない（`launch-smoke.sh`。R8 の keep 漏れによる起動時のクラッシュを検出、#78） | ジョブサマリーの `LAUNCH_CHECK` 行、成果物 `logcat-*`（`logcat-release.txt` / `logcat.txt`） |
| `emulator.yml`（phone） | 同上（`release-asr.sh`） | release ビルド（R8）が既知の日本語チャンクを本番の経路（取り込み → WorkManager の文字起こし → sherpa-onnx）で文字起こしして DB に保存し、検索できる（`ReleaseAsrTest`、#78。§7.1） | `RELEASE_ASR_CHECK` 行（`debuggable=false` と文字起こし結果）、成果物 `logcat-phone` の `release-asr/` |
| `emulator.yml`（phone） | 同上（続けて実行） | 計装テスト: 実際の音声（AAC）を MediaPlayer で指定位置から再生できる・検索結果を 1 タップでその発話から再生できる（`PlaybackTest`）、SQLite のテストを Android 上で実行（shared/db / shared/pipeline） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK` 行、成果物 `logcat-phone`（テストレポート） |
| `emulator.yml`（phone） | 同上（計装テスト） | 実モデルでの文字起こし: `AndroidSherpaTranscriber`（MediaCodec + sherpa-onnx AAR）が日本語の参照音声チャンクを文字起こしして DB に保存・検索できる（`SherpaTranscriberTest`、#22） | `ASR_CHECK` / `ASR_SEGMENT` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | モデルなしでも受信・再生でき文字起こしは待機（チャンクは処理待ちのまま・失敗扱いにしない）、モデルを置くと自動で処理が進む（`ModelWaitTest`、#22） | `MODEL_CHECK` 行、スクリーンショット `status-model-missing.png` / `status-model-ready.png` |
| `emulator.yml`（phone） | 同上（計装テスト） | 定期ジョブ（6 時間ごと・充電中のみ）は充電されるまで動かず、充電条件を満たすと溜まったチャンクを文字起こしする（WorkManager の `TestDriver`、`ChargingWorkTest`、#20） | `CHARGING_CHECK` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | 電池と発熱の方針（#75）: 電池駆動で残量 30% なら何も処理せず充電待ちのジョブを予約、残量 80% なら 1 時間の上限まで処理して 1 時間後の再実行を予約、充電中に発熱すると処理中のチャンクを終えて止まり、冷えてからの再実行で残りを処理する（全チャンク 1 回ずつ）（WorkManager の `TestDriver`、端末の状態は差し替え、`BatteryPolicyWorkTest`） | `POLICY_CHECK` 行 |
| `emulator.yml`（phone） | 同上（計装テスト） | 録音の途絶アラートが本番の `GapAlertMonitor` から通知され、通知シェードに表示される（`GapAlertNotificationTest`、#43） | `GAP_ALERT_CHECK` 行、スクリーンショット `gap-alert-notification.png` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 通知権限をシステムのダイアログで拒否 → 理由と「設定を開く」が表示され、押すとアプリの設定画面が開く（`PermissionDeniedTest`、#32） | `PERMISSION_CHECK` 行、スクリーンショット `permission-system-dialog.png` / `onboarding-permission-denied.png` / `app-settings-opened.png` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 文字起こしの途中で `am force-stop` → 再起動で全チャンクが 1 回ずつ文字起こしされる（欠落・重複 0、中断したチャンクは 2 回目以降で完了）（`ResumeAfterKillTest`、#20） | `RESUME_CHECK` 行、成果物 `logcat-phone` の `scenario-logs/` |
| `emulator.yml`（phone） | 同上（`phone-scenarios.sh`） | 診断ログ: チャンクの受信（`ChunkReceiver.store`）→ 文字起こし → `am force-stop` → 再起動で、ログに `chunk_received`・`transcription_started` / `_finished`（RTF つき）・`app_start`・強制終了の `previous_exit`（OS が報告した終了理由）が残り、状態タブの「診断ログを書き出す」で CSV が書き出されて共有シートが開く（書き出しに文字起こし本文を含まない）（`DiagnosticsScenarioTest`、#77） | `DIAGNOSTICS_CHECK` 行と書き出した CSV の抜粋（`DIAGNOSTICS_EXPORT`）、スクリーンショット `diagnostics-export.png`、成果物 `logcat-phone` の `scenario-logs/diagnostics-export.csv` |
| `emulator.yml`（wear） | 同上（`wear-surfaces.sh`） | Tile とコンプリケーションの表示（停止中 → Tile のタップでアプリが開き録音開始 → 録音中）（#41） | `WEAR_SURFACE_CHECK` 行、スクリーンショット `wear-tile-*.png` / `wear-complication-*.png` |
| `agent-bridge.yml`（cli、Linux・macOS・Windows） | `npm run lint` / `npm test`（`tools/agent-bridge`） | エージェント連携の橋渡し CLI（#183）の型検査と単体テスト: Kotlin と共有するやり取りの形式（`shared/agent/testdata`）、自己署名の証明書、ペアリング（使い捨ての値・確認コード）、TLS での拒否（ペアリングしていない鍵・別の鍵の PC・解除後）、stdio の MCP（道具の一覧・スマホ未接続時の応答） | テスト結果 |
| `agent-bridge.yml`（e2e） | End to end: bridge CLI + fake phone | ペアリング（両側の確認コードが一致）→ 相互 TLS 1.3 → stdio の MCP クライアントからの道具の呼び出しが偽スマホ（`tools/agent-fake-phone`。共通部分 `shared/agent` の道具のロジックとデモの DB）の記録を返す。範囲外（30 日より前）・音声の未許可は返らない。ペアリングしていない相手・鍵の違う PC は握手で、期限切れのペアリング値は最初のメッセージで、PC・スマホで取り消したペアリングは以後の接続で拒否。CLI が外向きの接続を一度も開かない（`e2e/no-egress.ts`） | テスト結果（`node --test` の出力） |
| `ios.yml` | Shared tests (iOS simulator) | 共通ロジックを iOS シミュレータで実行（SQLite を使う shared/db / Ingestor のテストを含む）。shared/ui の `IosPlaybackTest`: `IosAppServices.play` 直接・共有 UI で前日のタイムラインの発話タップ／検索結果の 1 タップのそれぞれで、そのチャンクの AAC が AVAudioPlayer に指定位置（±0.2 秒）・正しい長さで用意される（#19, #25。テスト実行ファイルはアプリではないので再生開始 `started=` は記録のみ）。`IosTranscriberBridgeTest`: Swift の文字起こし結果が絶対時刻つきで DB・FTS に入る（#21） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK` 行 |
| `ios.yml` | Shared tests (iOS simulator) / Swift unit tests | iPhone の DB・音声・受信ディレクトリ（`LocalDataDirectoryTest`）と Watch の送信待ちディレクトリ（`LocalDataProtectionTests`、`ChunkOutboxTests`）がバックアップ対象外で、保護クラス `CompleteUntilFirstUserAuthentication` を設定できる（シミュレータは保護クラスを報告しないことがあるため、値は報告される場合だけ比較。#76） | ジョブサマリーのテスト一覧、ログ |
| `ios.yml` | Launch iOS app / Launch watchOS app | ビルドした iOS・watchOS アプリをシミュレータに入れて起動し、15 秒後もプロセスが生きていてクラッシュレポートがない | ジョブサマリー、成果物 `simulator-smoke`（スクリーンショット・ログ） |
| `ios.yml` | Swift unit tests (iOS Simulator) | `SwiftUnitTests` スキーム（ホストなし）: Swift のワイヤ形式が Kotlin のテストベクタと一致（`ProtocolTests`）、Watch の停止理由・状態判定と終了理由のマニフェストへの記録（`RecorderPolicyTests`、#15）、送信キューの ACK・再送・再起動後の再送・強制終了からの復旧（`ChunkOutboxTests`、#17） | ジョブサマリーのテスト一覧（`.github/scripts/xcresult-summary.py`） |
| `ios.yml` | Japanese test speech / Speech transcription on the iOS simulator | macOS の `say` で作った日本語音声（Watch と同じ AAC 16 kHz モノラル）を SpeechTranscriber（ja_JP）で文字起こしし、時刻と文字の再現率を確認（`SpeechTests`、iOS アプリがホスト、#21）。シミュレータで使えなければ理由を残してスキップ（実機確認は #60） | ジョブサマリーのテスト一覧と「Speech check logs」、成果物 `swift-evidence` |
| `ios.yml` | Playback in the iOS app (simulator) | `PlaybackTests`（iOS アプリがホストの XCTest）: アプリが起動時に作った本物の `IosAppServices` で AAC 16 kHz の音声を 8 秒から再生し、再生位置がそこから始まり 2 秒後に 1 秒以上進む（#19） | ジョブサマリーのテスト一覧と `PLAYBACK_CHECK check=hosted_player` 行、成果物 `swift-evidence` |
| `ios.yml` | Agent access on the iOS simulator (bridge CLI on the host) | エージェント連携（#185）: このホストで本物の橋渡し CLI（`tools/agent-bridge/e2e/emulator-host.ts`）を動かし、`AgentAccessTests`（iOS アプリがホストの XCTest、`.github/scripts/ios-agent-access.sh`）がアプリの `IosAgentAccess`（Keychain の鍵・Network.framework の相互 TLS 1.3）で 127.0.0.1 の CLI とペアリング（確認コードが PC と一致）→ ON の間だけ PC のエージェントの `search_transcripts` に答える → OFF・アプリが画面から外れる・取り消しで答えなくなる（CLI が 6 回の切り替わりを確かめる）。mDNS は見えたかを記録するだけ | ジョブサマリーの `AGENT_HOST` / `AGENT_CHECK` 行、成果物 `swift-evidence`（`agent-access-logs/`、`build/swift-evidence/agent-access-*.txt`） |
| `ios.yml`（`release` ジョブ） | Build iOS / watchOS app, Release, device | Release 構成の iOS アプリ（watchOS アプリを同梱。Kotlin フレームワークは iosArm64 の release）と watchOS アプリが実機向け（`generic/platform=iOS` / `watchOS`）に署名なし（`CODE_SIGNING_ALLOWED=NO`）でビルドできる。Release のバイナリに debug 専用の音声ファイル入力（#57）が入っていない（#78） | ジョブサマリーの `RELEASE_BUILD_CHECK` 行（アーキテクチャ・サイズ） |
| `android.yml` | Version check | アプリのバージョンが 1 か所（`gradle.properties`）にあり、iOS / watchOS の `apps/ios/project.yml` の `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` が同じ値（`tools/check-version`、§7.2、#138） | ジョブのログ（食い違いを `ファイル:行: 内容` で列挙） |
| `links.yml` | Markdown link check | リポジトリ内のすべての Markdown の相対リンクとアンカー（見出し）が存在する。CLAUDE.md のコードとして書いたパス・`cd` 先・Gradle のプロジェクトパス（`:module:task`）が実在する（`tools/check-links`、#49） | ジョブのログ（壊れたリンクを `ファイル:行: 内容` で列挙） |

スクリーンショットは成果物 `screenshots-phone` / `screenshots-wear`（30 日保存）、`*_CHECK` 行はジョブサマリーにまとめて出す。

SQLite（bundled ドライバ）を開くテストは `src/sqliteTest` に置く（`watchaudio.kmp.sqlite-tests` プラグイン）。`jvmTest`・`iosTest`・`androidDeviceTest` から共有され、JVM・iOS シミュレータ・Android 端末/エミュレータで実行される。Android のホストテスト（`testAndroidHostTest`）では実行しない: Android 版の `sqlite-bundled` は Android の ABI 向けネイティブライブラリしか含まず、ホストの JVM では読み込めないため。

- shared/db（`LocalStoreTest`・`StorageQueriesTest`・`IntegerRefsMigrationTest`）と shared/pipeline（`IngestorTest`・`TranscriptionPipelineTest`・`StorageManagerTest`・`ChunkIntakeTest`・`TranscriptionRunTest`・`GapAlertEvaluatorTest`）: JVM・iOS シミュレータ・Android 端末/エミュレータ。
- shared/ui の `src/sqliteTest`（今はテストなし）: JVM・iOS シミュレータ（`watchaudio.kmp.sqlite-tests-jvm-ios`）。Compose Multiplatform のリソースプラグインが KMP ライブラリの Android デバイステスト用バリアントを構成できないため、Android 端末では実行しない。途絶アラートの `GapAlertEvaluatorTest` は #134 で shared/pipeline に移り、Android 端末でも実行される。
- shared/ui の `ViewModelTest`・`AppUiTest` は JVM のみ（`Dispatchers.Main` に Swing、描画にデスクトップの Skia を使うため）。iOS では `src/iosTest` の `IosPlaybackTest` が実際の `IosAppServices` で同じ流れ（タイムライン・検索 → 再生の準備）を確かめる。Kotlin/Native のテスト実行ファイルはアプリのプロセスではなく `AVAudioPlayer.play()` が false を返すため、再生が進むことはアプリをホストにした XCTest（`apps/ios/Tests/PlaybackTests`）で確かめる。Kotlin/Native のテストはメインスレッドで動き、`Dispatchers.Main` はメインのランループが回るときだけ進むので、待ち合わせ中にランループを回している（`IosPlaybackFixture.pumpMainRunLoop`）。

テストの準備コード（一時ディレクトリ `TempDir`、manifest を作る `testManifest` / `testAudio`）は `shared/testing` にまとめ、各モジュールの `commonTest` から使う（#94）。本体のコードからは参照しない。shared/db のテストのチャンク登録は `StoreFixtures.kt` の `registerChunk`。

手元での実行:

```bash
# iOS 向けテストのコンパイル確認（Linux、リンクなし）
./gradlew --max-workers=2 :shared:db:compileTestKotlinIosSimulatorArm64 :shared:pipeline:compileTestKotlinIosSimulatorArm64 \
  :shared:ui:compileTestKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
# Android エミュレータ / 実機を接続して（KVM のある環境）
./gradlew :apps:android:connectedDebugAndroidTest :shared:db:connectedAndroidDeviceTest :shared:pipeline:connectedAndroidDeviceTest
adb logcat -d | grep -E "[A-Z_]+_CHECK"
# Mac: ビルド済みアプリをシミュレータで起動確認
.github/scripts/simulator-smoke.sh iOS iPhone build/DerivedData/Build/Products/Debug-iphonesimulator/WatchAudio.app app.watchaudio
# Mac: Swift のテスト（cd apps/ios && xcodegen generate の後）
.github/scripts/xcode-test.sh SwiftUnitTests "Swift unit tests"
.github/scripts/make-ja-speech.sh build/ja-speech && UNINSTALL_APP=app.watchaudio .github/scripts/xcode-test.sh SpeechTests "SpeechTranscriber"
UNINSTALL_APP=app.watchaudio .github/scripts/xcode-test.sh PlaybackTests "Playback in the iOS app"
```

`ios.yml` の `build` ジョブはシミュレータを使い回す（#92）。起動は端末ごとに 1 回、同時に起動しているのは 1 台だけにする（3 コアのランナーでは、2 台を同時に起動したままにすると各ステップが 2〜3 倍遅くなった）。順番は Apple Watch（コンプリケーション → 起動確認。終わったらシャットダウン）→ iPhone（Swift unit tests → 起動確認 → Speech → Playback）。初回起動はデータ移行とその後の落ち着くまでに数分かかるため、`xcode-test.sh` は iPhone の起動をバックグラウンドで始めてからテストをビルドし（`build-for-testing`）、起動完了を待ってから実行する（`test-without-building`）。初回起動のデータ移行が長引くと、xcodebuild が起動中の端末を見つけられずにビルドを諦める（`Unable to find a device matching the provided destination specifier`）ことがあるため、そのときは起動完了を待ってからビルドを 1 回だけやり直す（ログに警告が出る）。iPhone で最初に動くホストなしの SwiftUnitTests のビルドが初回起動と重なり、続く起動確認は落ち着いた端末で行われる。各スクリプト（`simulator-smoke.sh`・`watch-complication-smoke.sh`・`xcode-test.sh`）は `simulator-udid.sh` が選ぶ同じ端末（最新ランタイムの iPhone / Apple Watch）を使い、終わってもシャットダウンしない（ジョブの最後の「Shut down simulators」で止める）。アプリがホストのテスト（Speech・Playback）は、前のステップで入れたアプリが残っているとテストバンドルを読み込めないため、端末を消去（`ERASE_SIMULATOR=1`。再起動でデータ移行がやり直しになる）する代わりにアプリをアンインストールしてから（`UNINSTALL_APP`）実行する。アンインストールでアプリのコンテナ・データ・権限の状態は消去と同じく初期化される。ホストのテストのビルドでは、「Build iOS app」が同じソースから作った Kotlin フレームワークを使い、Gradle の再実行を省く（`OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES`）。各ステップのログの `Timing:` 行に起動待ち・インストール・ビルド・実行の所要時間が出る。

`emulator.yml` の `smoke` ジョブは、エミュレータが起動している間に Gradle のコンパイルや SDK のダウンロードを走らせない（#92）。ランナーの不要なファイル（.NET・GHC・Boost・NDK・ツールキャッシュ）は同じファイルシステムの `/ci-trash` に移してからバックグラウンドで消す（同期して消すと約 40 秒かかっていた）。エミュレータ本体とシステムイメージはジョブの準備と APK のビルドの間にバックグラウンドの `sdkmanager` で取得し（「Download the emulator and its system image (background)」→「Wait for the emulator download」）、emulator-runner 自身のインストールはほぼ何もしない（約 38 秒 → 約 6 秒）。APK はエミュレータの起動前にすべてそろえる: 配布と同じ release・debug・計装テストの APK は phone ジョブで、release での文字起こし確認用の APK（2 回目の Gradle ビルド。ほぼ R8 で、アプリのコードを変えた PR では 1〜2 分）は並行して動く `release-test-apks` ジョブが別のランナーで作る（§7.1）。エミュレータは `-no-snapshot`（毎回コールドブートで、終了時のスナップショット保存も省く）。CPU は既定の 2 コアのまま（4 コアにしても 2 回の比較で各段階の差は誤差程度だったため）。AVD のスナップショットはキャッシュしない（数 GB のキャッシュが他のキャッシュを追い出すため）。各段階の所要時間はジョブのログのタイムスタンプで追える（ビルド・SDK・起動・`LAUNCH_CHECK`・各 `SCENARIO`・計装テスト・`RELEASE_ASR_CHECK`）。

Swift のテストを Linux で実行する手段はない（Xcode が必要）。変更したら CI（macOS、10 倍課金）で確認する前に、Kotlin 側は上のクロスコンパイル、Swift 側は差分の読み直しで型・並行性のエラーを潰しておく。

### 6.1 エミュレータでの音声認識モデル

phone ジョブは `android.yml` と同じキャッシュ（`tools/harness/models`、キーは `download-models.sh` のハッシュ）からモデルを復元し、なければ `download-models.sh` で取得する（int8 の約 160 MB と参照音声のみ展開）。`push-models.sh`（`release-asr.sh` と `phone-scenarios.sh` から呼ぶ。2 回目は省略）が `adb push` で `/data/local/tmp/watchaudio-test/models/` に置き、計装テスト（`SpeechFixtures.ensureModels`）がそれをアプリの `files/models/`（ダウンロード先と同じ場所）にコピーしてから使う。`/data/local/tmp` を経由するのは、テストエンジンが計装テストの前にアプリをアンインストール・再インストールしてアプリの領域を消すため。コピーはシェル権限の `cat` をパイプで読む（アプリから `/data/local/tmp` を直接読めるかは SELinux の設定に依存するため）。

- モデルがない環境（手元の `connectedDebugAndroidTest` など）では、モデルを使うテストはスキップされる。CI は `-Pandroid.testInstrumentationRunnerArguments.requireModels=true` を渡すので、モデルがなければ失敗する。
- テスト中はアプリの自動モデルダウンロード（`model-download`）を取り消す（テスト中にファイルが置き換わらないように）。
- エミュレータは x86_64 で CPU 推論のみ。`ASR_CHECK` の `rtf`（処理時間 ÷ 音声長）は実機の目安にはならない。

### 6.2 1 回の計装テストでは確かめられないもの（`phone-scenarios.sh`）

- **権限の拒否（#32）**: 権限を取り消すとアプリのプロセスが終了するため、計装テストの中ではリセットできない。インストール直後（権限が一度も付与されていない状態）に `am instrument -e scenario permission-denied` で最初に実行する。
- **強制終了からの再開（#20）**: 計装テストは強制終了されるプロセスの中で動くため、3 段階に分ける。① `ResumeAfterKillTest#seed` が参照音声のチャンクを 8 件保存（文字起こしは要求しない）、② スクリプトがアプリを起動（起動時に未処理チャンクの処理を要求）し、アプリの診断ログ（`files/diagnostics/diagnostics.csv`。debug ビルドなので `run-as` で読む）に ① のセッションのチャンクの最初の `transcription_finished` が出たら `adb shell am force-stop app.watchaudio`。強制終了の時点で `transcription_started` だけあって `_finished` の無いチャンクを中断したチャンクとする（強制終了後に JobScheduler が起こしたプロセスの記録は、次の `app_start` 以降を除いて数えない）。logcat の文言は見ないので、ログの文言を変えても CI は壊れない（#134）、③ `ResumeAfterKillTest#verify` が再起動後に全件の完了を待ち、各チャンクの発話が重複なく揃っていること・強制終了時に処理中だったチャンクの試行回数が 2 以上であることを確認する（強制終了の直後に JobScheduler がアプリを起こして処理を再開し、③ の計装テストの開始でもう一度中断されることがあるため。2026-10-11、main の run 38138582342 で発生）。
- **診断ログと前回の終了理由（#77）**: 同じく 3 段階。① `DiagnosticsScenarioTest#receive` が参照音声 1 文のチャンクを時計と同じフレームにして `ChunkReceiver.store` に渡し（`chunk_received` が記録され、受信時と同じく文字起こしが要求される）、本番のワーカーが文字起こしを終えるまで待つ、② スクリプトが `am start` でアプリを起動し、3 秒後に `adb shell am force-stop app.watchaudio`（前後の端末時刻を記録）、③ `DiagnosticsScenarioTest#verify` が次のプロセスで、ログに ① のチャンクの各イベント・この起動の `app_start`・強制終了の時刻に当たる `previous_exit` があることを確認し、状態タブの「診断ログを書き出す」を押して共有シート（`ChooserActivity`）が開くこと・書き出した CSV にログの行があり文字起こし本文がないことを確かめる。書き出した CSV はスクリプトが `run-as` で `scenario-logs/diagnostics-export.csv` に取り出す。

手元で同じことをする場合（KVM のある環境、`tools/harness/download-models.sh` 済み）:

```bash
./gradlew :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
.github/scripts/phone-scenarios.sh            # モデルの push、権限拒否、強制終了からの再開、診断ログ
./gradlew :apps:android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireModels=true
adb pull /data/local/tmp/watchaudio-screenshots
```

### 6.3 どの変更でどのワークフローが動くか（#92）

PR の CI 待ちを減らすため、変更したファイルに関係するワークフローだけを動かす。`workflow_dispatch` からはいつでも手動で全部動かせる。

| 変更したファイル | `android.yml` | `emulator.yml` | `ios.yml` | `deliver.yml`（main のみ） | `links.yml` |
| --- | --- | --- | --- | --- | --- |
| ドキュメントだけ（`docs/**`、`**/*.md`、Issue / PR テンプレート、`tools/roadmap-view/**`、`tools/check-links/**`、`.github/workflows/links.yml`、`research/**`） | 動かない | 動かない | 動かない | 動かない | 動く |
| iOS 専用（`apps/ios/**`、`src/iosMain`・`src/iosTest`、iOS 用スクリプト） | 動く | 動かない | 動く | 動く | 動く |
| Android 専用（`apps/android/**`、`apps/wear/**`、`src/androidMain`・`androidHostTest`・`androidDeviceTest`、Android 用スクリプト） | 動く | 動く | 動かない | 動く | 動く |
| JVM 専用（`src/jvmMain`・`src/jvmTest`） | 動く | 動かない | 動かない | 動く | 動く |
| 共通コード（`src/commonMain` など）・Gradle の設定・`build-logic/**` | 動く | 動く | 動く | 動く | 動く |

- `agent-bridge.yml`（エージェント連携の橋渡し CLI と偽スマホ、#183）は対象リスト（`paths`）: `tools/agent-bridge/**`・`tools/agent-fake-phone/**`・`shared/**`・Gradle の設定・`build-logic/**`・ワークフロー自身が変わったときだけ動く。
- `.github/actions/sherpa-onnx-jni/**` と `tools/sherpa-onnx-android/**`（音声合成なしの sherpa-onnx、§7.3）は Android 専用の扱い（`emulator.yml` の対象リストに入れてある）。`deliver.yml` は main への push のほか、ほかのブランチで手動実行すると成果物を残さない試運転になる。
- `android.yml` と `deliver.yml` は除外リスト（`paths-ignore`）。ドキュメント以外の変更では必ず動く。
- `links.yml`（Markdown のリンク検査、数秒）は絞り込みなしで毎回動く。ドキュメントだけの変更でもリンク切れを見つけるためと、コードの移動で docs からのリンクが切れることがあるため。手元では `python3 tools/check-links/check_links.py`（テストは `python3 -m unittest discover -s tools/check-links -v`）。
- `emulator.yml` と `ios.yml` は対象リスト（`paths`）に除外（`!`）を足している。除外は後ろに書いたものが優先されるので、新しい除外は対象リストより後ろに書く。
- 新しいスクリプトを `.github/scripts/` に足すときは、片方のプラットフォーム専用なら、もう片方のワークフローの除外に追加する。
- フォルダ構成を変えるとき（#49）は、上の表のパスもあわせて直す。

### 6.4 Local-only チェック（`tools/privacy-check`、#76）

CLAUDE.md の「Local only」（録音・文字起こし・ユーザーデータを端末の外に出さない。通信はモデルの取得と、本人が ON にしたエージェント連携での自分の PC への LAN 内の接続だけ）を CI で守るための静的チェック。Python の標準ライブラリだけで動き、`android.yml` で 2 回実行する。

1. **最初のステップ（ビルド不要、数秒）**: `test_check.py`（違反を 1 つずつ入れたフィクスチャで失敗することを確かめるテスト）と、リポジトリのファイルに対するチェック。
2. **Assemble の後**: リリースのマージ後マニフェスト（`processReleaseMainManifest`）と、`./gradlew :<app>:dependencies --configuration releaseRuntimeClasspath` の出力（推移的な依存を含む）も検査し、通信箇所の一覧をジョブサマリーに出す。

| 検査 | 対象 | 失敗する例 |
| --- | --- | --- |
| 権限 | `apps/android` / `apps/wear` の `src/*/AndroidManifest.xml`、マージ後マニフェスト | Wear OS に `INTERNET`、スマホに位置情報・広告 ID、依存が勝手に足した権限 |
| バックアップ | `<application>` と `res/xml` のルール | `allowBackup="true"`、`dataExtractionRules` なし、除外していないドメイン、`<include>`、`usesCleartextTraffic="true"` |
| 通信・保護 | `apps/android/src`・`apps/wear/src`・`shared`・`apps/ios` の `.kt` / `.java` / `.swift`（コメントと、出荷されないテストコード = `test` や `Test` を含むディレクトリは除外） | 許可されていないファイルでの `HttpURLConnection`・`URLSession`・ソケット・mDNS（`NsdManager`・マルチキャストロック）・Network.framework（`NWConnection`・`NWBrowser`、エージェント連携の `AgentNetwork.swift` だけ可）・`NWListener`（どこでも不可）・WebView・`SFSpeech…RecognitionRequest`、`DataClient` / `PutDataRequest`、`.noFileProtection`、許可されていない URL、使われなくなった許可リストの項目、許可した通信箇所が守るべきこと（`ALLOWED_API_REQUIREMENTS`。エージェント連携の `TlsAgentTransport.kt` は LAN のアドレスだけ・DNS なし・鍵の固定・TLS 1.3 だけ）を守らなくなったこと |
| 依存（宣言） | `gradle/libs.versions.toml` と `*.gradle.kts`（`research/` を除く） | firebase・crashlytics・sentry・amplitude などの解析・クラッシュ送信 SDK、okhttp・retrofit・ktor などの HTTP クライアント |
| 依存（解決結果） | リリースの実行時クラスパス | 許可リスト（AndroidX、Kotlin、Compose、play-services-wearable、sherpa-onnx など）にないモジュール |
| iOS | `apps/ios/project.yml`、コミットされた `*.entitlements`・`*.xcprivacy` | 許可リストにない Info.plist のキー（`NSAppTransportSecurity`、位置情報など）、`UIBackgroundModes`、`NSBonjourServices`（`_watchaudio-mcp._tcp` だけ、#185）、entitlements。アプリ・拡張のターゲットに `PrivacyInfo.xcprivacy`（`buildPhase: resources`）がないこと、マニフェストの追跡・追跡ドメイン・収集データ、許可リストにない理由コード（#159） |
| エージェント連携の橋渡し CLI（PC、#183） | `tools/agent-bridge/src/**/*.ts`（`test/`・`e2e/` は出荷しないので除外）、`package.json`、`package-lock.json` | 許可されていないファイルでの `node:tls`・mDNS、どこでも `fetch(`・`connect(`・`node:http(s)`・`node:net`・`node:dgram`・`node:dns`・`child_process`・HTTP ライブラリ、URL リテラル、許可リストにない実行時の npm パッケージ（推移的なものを含む）、インストールスクリプトを持つパッケージ |

手元での実行:

```bash
python3 -m unittest discover -s tools/privacy-check -v
python3 tools/privacy-check/check.py
# ビルド後の検査も（assembleDebug などの後）
./gradlew --max-workers=2 :apps:android:processReleaseMainManifest :apps:wear:processReleaseMainManifest
./gradlew --max-workers=2 -q :apps:wear:dependencies --configuration releaseRuntimeClasspath > /tmp/deps-wear.txt
python3 tools/privacy-check/check.py --resolved-deps apps/wear=/tmp/deps-wear.txt \
  --merged-manifest apps/wear=apps/wear/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
```

**許可リストを広げるとき**（新しい権限・依存・通信先が本当に必要な場合）:

1. `tools/privacy-check/policy.py` の該当する表に、理由を添えて追加する（権限は `ANDROID_APPS`、ライブラリが足す権限は `library_permissions`、通信するファイルは `ALLOWED_API_USES`（守るべきことがあれば `ALLOWED_API_REQUIREMENTS` にも）、URL は `ALLOWED_URL_PREFIXES`、依存は `ALLOWED_RESOLVED_DEPENDENCIES`、iOS は `IOS_TARGETS` / `IOS_ENTITLEMENTS` / `IOS_REQUIRED_REASONS`、橋渡し CLI は `AGENT_BRIDGE_ALLOWED_API_USES` / `AGENT_BRIDGE_DEPENDENCIES` / `AGENT_BRIDGE_RUNTIME_PACKAGES`）。拒否パターン（`GUARDED_APIS`・`DENIED_DEPENDENCIES`）は緩めず、許可リスト側に足す。
2. ユーザーデータが端末の外に出ないことを PR の本文で説明する。データを外に出す変更（AI プロバイダなど P2）は、CLAUDE.md の方針の変更として Issue で合意してから。
3. [02 §7](../architecture/02-architecture.md#7-セキュリティプライバシー) の保存場所・通信箇所の一覧を更新し、`test_check.py` の期待値（通信箇所の集合など）も直す。

## 7. CI の設定と成果物の扱い（公開リポジトリ）

リポジトリは公開なので、CI のログと成果物（artifact）は GitHub にログインした人なら誰でも見られる・ダウンロードできる（#79）。

- **ランナーは固定**: Linux は `ubuntu-24.04`（`ubuntu-latest` の Ubuntu 26 への切り替えで壊れないように）、macOS は `macos-26`。新しいイメージへは、全ジョブが通ることを PR で確かめてから移る。
- **権限は最小**: 各 workflow は `permissions: contents: read`。書き込みが必要なジョブを足すときは、そのジョブにだけ付ける。`emulator.yml` の `smoke` ジョブだけは、同じ実行の成果物 `release-test-apks` を取得するため `actions: read`（読み取りのみ）を足している（#92）。`pull_request_target` は使わない（fork の PR に secret が渡るため）。
- **外部アクションは SHA で固定**し、`.github/dependabot.yml` が週 1 回まとめて更新 PR を出す。
- **秘密情報**: GitHub Actions の Secrets にだけ置き、ログに出さない（加工して出力すると伏せ字を回避してしまうので注意）。現時点で secret を使うのは `android.yml` の release 署名だけ（§7.1）。

成果物の中身（どれも実データ・個人データ・秘密情報を含まない）:

| 成果物 | workflow | 中身 | 保持 |
| --- | --- | --- | --- |
| `debug-apks` / `watch-audio-android-<sha>` | android.yml / deliver.yml | debug 署名の APK（スマホ・Wear OS）。API キーは入っていない。モデルは同梱せず初回にダウンロード。スマホの APK の sherpa-onnx は音声合成なし（§7.3）。`watch-audio-android-<sha>` は main の実行だけが作る | 7 / 30 日 |
| `release-apks` | android.yml | release 鍵で署名した R8 の APK（スマホ・Wear OS）。**署名用の secret があるときだけ**作る（ないときは署名なしでビルドだけ確認し、アップロードしない）。鍵そのものは入っていない | 7 日 |
| `ui-screenshots` | android.yml | JVM の UI テストが描いた画面（テスト用の合成データ） | 30 日 |
| `logcat-phone` / `logcat-wear` | emulator.yml | エミュレータの logcat・計装テストのレポート（合成音声・テスト用 DB のみ。文字起こし本文はテスト音声の正解文） | 7 日 |
| `screenshots-phone` / `screenshots-wear` | emulator.yml | エミュレータのスクリーンショット | 30 日 |
| `release-test-apks` | emulator.yml | release での文字起こし確認用の APK（R8 + テスト用 keep ルールのアプリと、そのテスト APK）。ジョブ内で作った使い捨ての鍵で署名した確認専用のもので、配布には使わない。同じ実行の phone ジョブが受け取る（§7.1） | 1 日 |
| `xcodebuild-logs` / `xcodebuild-release-logs` / `simulator-smoke` | ios.yml | ビルドログ（`xcodebuild-release-logs` は Release ビルドが失敗したときだけ）、シミュレータのスクリーンショットとアプリログ | 7 日 |

成果物を Issue の証跡として参照する場合、保持期間を過ぎると消える点に注意する（コメントには数値・ログ行を本文として残す）。

### 7.1 Android の release ビルドと署名（#78）

- **設定の場所**: `build-logic` の `AndroidAppConventionPlugin`（プラグイン ID `watchaudio.android.app`、§7.2）の一部（`AndroidRelease.kt`）で、`apps/android` と `apps/wear` の両方に適用している。release は R8 によるコードの縮小・難読化とリソースの縮小（`isMinifyEnabled` / `isShrinkResources`）を行い、keep ルールは各モジュールの `proguard-rules.pro`。スマホと Watch は applicationId が同じ `app.watchaudio` で、Data Layer でつながるには**同じ鍵で署名する必要がある**ため、署名の設定は 1 か所にまとめている。
- **鍵の渡し方**: 鍵はリポジトリに置かず、Gradle は次の環境変数から読む。4 つとも未設定なら署名なし（`*-release-unsigned.apk`）、一部だけ設定されていれば値を出さずにビルドを失敗させる（secret の設定漏れで黙って署名なしになるのを防ぐ）。

| Gradle が読む環境変数 | 中身 | CI で渡す GitHub Actions secret |
| --- | --- | --- |
| `WATCHAUDIO_RELEASE_STORE_FILE` | キーストアのパス | `ANDROID_RELEASE_KEYSTORE_BASE64`（キーストアを base64 にしたもの。CI がランナーの一時ディレクトリに復元し、ジョブの最後に消す） |
| `WATCHAUDIO_RELEASE_STORE_PASSWORD` | キーストアのパスワード | `ANDROID_RELEASE_KEYSTORE_PASSWORD` |
| `WATCHAUDIO_RELEASE_KEY_ALIAS` | 鍵のエイリアス | `ANDROID_RELEASE_KEY_ALIAS` |
| `WATCHAUDIO_RELEASE_KEY_PASSWORD` | 鍵のパスワード | `ANDROID_RELEASE_KEY_PASSWORD` |

- **CI の動き**: `android.yml` は毎回 `:apps:android:assembleRelease :apps:wear:assembleRelease` を実行する（Wear OS の `verifyReleaseDebugInput` もここで走る）。secret があれば release 鍵で署名して成果物 `release-apks` に上げ、なければ（現在の状態。fork からの PR にも secret は渡らない）署名なしでビルドだけ確認する。`emulator.yml` は release 鍵を使わず、ジョブ内で `keytool` で作った使い捨ての鍵で署名して起動確認する（鍵とパスワードはそのジョブ限りで、配布物には使わない）。
- **release での文字起こしの確認**（`emulator.yml` の phone、`release-asr.sh`）: 計装テスト `ReleaseAsrTest` を R8 の release ビルドに対して実行する。既知の日本語チャンク（ReazonSpeech の参照音声 1・3・5）を `AppGraph.ingestor`（Watch から受信したときと同じ経路）で保存し、アプリ自身の WorkManager の文字起こし（`TranscriptionWorker` → sherpa-onnx の JNI）で DB に保存され、FTS で検索できることを確かめる。アプリが debuggable でないこと（release であること）もテストで確認する。証拠は `RELEASE_ASR_CHECK` 行。
  - 計装テストの APK は、テスト対象のアプリの R8 の mapping に合わせて R8 で処理され、アプリの中のクラスを呼ぶ。R8 はテストだけが使うメソッドを消してしまうため、`-Pwatchaudio.testBuildType=release` を付けたビルドでだけ、テストが呼ぶものを残す keep ルール `apps/android/proguard-release-test.pro`（難読化は許可）をアプリに足す。このファイルは `tools/release-test-keep/generate.sh` が R8 の `tracereferences` で生成する（`ReleaseAsrTest` や使っているテスト用ヘルパーを変えたら再生成する）。テスト APK 自体の R8 ルールは `apps/android/proguard-test-rules.pro`。
  - 配布する APK にはこの keep ルールは入らない。起動確認（`launch-smoke.sh`）は配布するものと同じ release APK（プロパティなしのビルド）で行う。プロパティ付きで作り直した APK での文字起こしの確認は、phone ジョブの最後（シナリオと debug の計装テストの後）に行い、それまでの確認の端末の状態を変えない。プロパティ付きのビルド（1 回のビルドでテストできるビルドタイプは 1 つなので 2 回目のビルドになる。ほぼ R8）は、phone ジョブと並行して動く `release-test-apks` ジョブが別のランナーで行い、成果物 `release-test-apks` に上げる。`release-asr.sh` は環境変数 `RELEASE_TEST_ARTIFACT` があれば、順番が来た時点で同じ実行のその成果物を `gh run download` で受け取る（まだなければ 10 秒ごとに待ち、ジョブが失敗していれば `RELEASE_ASR_CHECK FAILED` で終わる。待った時間は `release-asr/apks.txt`）。環境変数がなければ（手元）ローカルのビルド出力を使う（#92）。debug 専用のフックは追加していない（テストはアプリの公開されたクラスを呼ぶだけ）。
  - 手元では（KVM のある環境、`tools/harness/download-models.sh` 済み）: 4 つの環境変数で署名してから `./gradlew :apps:android:assembleRelease :apps:android:assembleReleaseAndroidTest -Pwatchaudio.testBuildType=release` → `.github/scripts/release-asr.sh`。
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
- **手元での release ビルド**: 署名なしなら `./gradlew --max-workers=2 :apps:android:assembleRelease :apps:wear:assembleRelease`。署名する場合は上の 4 つの環境変数を設定してから実行する。
- **iOS / watchOS の Release 構成**: `ios.yml` の `release` ジョブ（シミュレータでの確認をする `build` ジョブと並列）が、iOS アプリ（watchOS アプリを同梱）と watchOS アプリを Release 構成・実機向けに署名なし（`CODE_SIGNING_ALLOWED=NO`）でビルドし、`RELEASE_BUILD_CHECK` 行（バンドル ID・アーキテクチャ・サイズ・debug 専用の音声ファイル入力がないこと）をジョブサマリーに出す。証明書・プロビジョニングプロファイルを secret に置いた署名付きのアーカイブ（TestFlight）は #35 で扱う。

### 7.2 アプリのバージョンと Android アプリの共通設定（#138）

- **バージョンは `gradle.properties` の 1 か所**: `watchaudio.versionName`（表示用のバージョン。例 `0.1.0`）と `watchaudio.versionCode`（ビルド番号。1 以上の整数で、上げるたびに増やす）。スマホ・Wear OS の `versionName` / `versionCode` は `build-logic` がここから読み、各アプリの `build.gradle.kts` には書かない。
- **iOS / watchOS**: XcodeGen は `gradle.properties` を読めないので、`apps/ios/project.yml` の `settings.base` に同じ値を書く（`MARKETING_VERSION` = versionName、`CURRENT_PROJECT_VERSION` = versionCode。Info.plist の `CFBundleShortVersionString` / `CFBundleVersion` はこの設定から作られる）。
- **CI の確認**: `android.yml` の最初のステップ「Version check」（`tools/check-version/check_version.py`、Python 標準ライブラリのみ・ビルド不要・数秒）が、次のどれかで失敗する。`android.yml` はドキュメント以外のすべての変更で動くので、`gradle.properties` や `project.yml` だけの変更でも走る。
  - `project.yml` の `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`（ターゲットごとの上書きも含めてすべて）が `gradle.properties` と違う、またはない
  - `gradle.properties` の値がない・形式が違う（versionName は Apple の制約に合わせて「整数を `.` で 1〜3 個つないだもの」、versionCode は 1〜2100000000）
  - アプリの `build.gradle.kts` が `versionName` / `versionCode` を自分で設定している、`project.yml` が `CFBundleShortVersionString` / `CFBundleVersion` を `$(MARKETING_VERSION)` / `$(CURRENT_PROJECT_VERSION)` 以外の値にしている
- **バージョンの上げ方**（例: v0.2）: `gradle.properties` の 2 行と `project.yml` の 2 行を同じ PR で直す。手元では `python3 tools/check-version/check_version.py`（テストは `python3 -m unittest discover -s tools/check-version -v`）。
- **Android アプリの共通設定**（`build-logic` の `AndroidAppConventionPlugin`、プラグイン ID `watchaudio.android.app`。`apps/android` と `apps/wear` が適用）: Android アプリ・Compose コンパイラのプラグイン、applicationId `app.watchaudio`（Data Layer でつながるため両方で同じ）、compileSdk / targetSdk（`gradle/libs.versions.toml`）、Java 17、Compose の有効化、上のバージョン、release ビルド（R8 と署名、§7.1）。各アプリに残すのは namespace・minSdk（Watch は 33、スマホは 29）・依存・アプリ固有の設定（スマホの ABI や計装テストの設定など）。
- **Wear OS の debug 入力の混入検査**（#57）: `build-logic` の `VerifyDebugInputTask` を `DebugInputGuardPlugin`（プラグイン ID `watchaudio.android.debug-input-guard`、`apps/wear` が適用）がバリアントごとに `verify<Variant>DebugInput` として登録し、`assemble<Variant>` から実行する（内容は [wearos.md](../platforms/wearos.md) の「リリースへの混入防止」）。

### 7.3 sherpa-onnx の JNI ライブラリ（音声合成なし、#173）

**なぜ自前でビルドするか**: JitPack の sherpa-onnx の AAR（v1.13.8）に入っている `libsherpa-onnx-jni.so` には、音声合成（TTS）用の **eSpeak NG（GPL-3.0）**・piper-phonemize と、話者分離用の hclust-cpp が静的にリンクされている（上流の Android ビルドは `SHERPA_ONNX_ENABLE_TTS=ON` が既定）。アプリが使うのはオフラインの ASR（Zipformer・SenseVoice）と Silero VAD だけなので、使わない GPL のコードを配布物に入れないように、**配布する APK では** TTS と話者分離だけを外した JNI ライブラリに差し替える。上流には TTS なしの Android 向けの公開ビルドが見つからなかった。

- **フォークではない**: 上流（k2-fsa/sherpa-onnx）の **タグをそのまま**、上流のビルドスクリプト（`build-android-<abi>.sh`）でビルドする。パッチは当てず、変えるのは CMake のオプションだけ（`SHERPA_ONNX_ENABLE_TTS=OFF`・`SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF`・`SHERPA_ONNX_ENABLE_C_API=OFF`）。手で保守するものはない。
- **公開しない・リポジトリに入れない**: ビルドは CI の中だけで行い、GitHub Actions のキャッシュに置く。GitHub Release・Git へのコミット・ほかの公開先は使わない。キャッシュが消えても（7 日使われないと消える）次の CI が作り直す。
- **差し替えるのは `libsherpa-onnx-jni.so` だけ**（ABI はアプリの `abiFilters`: `arm64-v8a`・`x86_64`）。Kotlin の API（`OfflineRecognizer`・`Vad` など）と `libonnxruntime.so` は AAR のものを使う。
- **動作は変わらない**: オプションで変わるのは TTS・話者分離のソースがビルドに入るかどうかだけで、ASR・VAD とその JNI のソースはこれらのオプションを参照しない（ビルドのログに、オプションを参照するソースの一覧を出す。v1.13.8 では C API の `c-api.cc` と Python の `sherpa-onnx.cc` だけで、どちらも JNI ライブラリには入らない）。JNI の関数は Kotlin から呼ばれたときに名前で解決されるので、使わないクラス（`OfflineTts` など）の関数がなくても読み込みは失敗しない。`emulator.yml` の release ビルドの文字起こし（`ReleaseAsrTest`）と計装テストを、差し替えた APK で動かして確かめ続ける。
- **サイズ**: `libsherpa-onnx-jni.so` は arm64-v8a が 4,771,760 → 3,436,448 バイト、x86_64 が 5,201,192 → 3,717,024 バイト。APK では `.so` を圧縮せずに格納するので、両 ABI 入りの APK は約 2.8 MB 小さくなる（v1.13.8）。同じ入力からは、どのジョブでビルドしてもバイト単位で同じファイルになる（NDK を同じ場所に置くため。ランナーにある NDK を使うと、同じ版でも SHA-256 が変わった）。

**どの APK に入るか**:

| ビルド | `libsherpa-onnx-jni.so` |
| --- | --- |
| CI の `deliver.yml`（main から配る APK）・`android.yml`（release / debug の APK）・`emulator.yml`（エミュレータで確かめる APK） | **音声合成なし**（差し替え）。入っていなければジョブが失敗する |
| 手元の開発ビルド（`./gradlew :apps:android:assembleDebug` など、下の指定なし） | AAR のまま（eSpeak NG を含む）。配布しないので問題ない。ビルドは何も警告しない |

**仕組み**:

| 場所 | 役割 |
| --- | --- |
| `tools/sherpa-onnx-android/aar-info.sh` | 版（`gradle/libs.versions.toml` の `sherpa-onnx` = ビルドする上流のタグ）・ABI（`apps/android` の `abiFilters`）・NDK（AAR の `libsherpa-onnx-jni.so` の `.note.android.ident`。上流が AAR をビルドした NDK）を読み取り、AAR の `libonnxruntime.so` を取り出す。AAR は Gradle のキャッシュにあればそれを、なければ JitPack から取る。手で固定する値はない |
| `tools/sherpa-onnx-android/build-jni.sh` | 上流のタグを取得し（`git clone --branch <tag>`）、その NDK（毎回 Google から取得して同じ場所に展開する。NDK の場所が変わると出力のバイトが変わるため。`SHERPA_ONNX_ANDROID_NDK` で指定も可）と上のオプションで ABI ごとにビルドする。リンクした `libonnxruntime.so` が AAR のものとバイト単位で同じこと、`check-no-tts.sh` を通ることを確かめ、`<dir>/<abi>/libsherpa-onnx-jni.so` と、何から作ったか（版・コミット・NDK・ABI・リンクした `libonnxruntime.so` の SHA-256）を書いた `<dir>/sherpa-onnx-jni.properties` を出力する（1 ABI 約 3.5 分） |
| `tools/sherpa-onnx-android/check-no-tts.sh` | `.so` または APK の中の `libsherpa-onnx-jni.so` に、eSpeak NG のデータ名（`espeak-ng-data`・`phontab`・`phonindex`）か TTS・話者分離の JNI 関数名があれば失敗する（AAR のものでは失敗することを確認済み） |
| `.github/actions/sherpa-onnx-jni`（複合アクション） | 上の 3 つをまとめる。キャッシュのキーは（sherpa-onnx の版、NDK、ABI、`build-jni.sh` のハッシュ）。ヒットすれば数秒、外れたときだけビルドして保存する。毎回、版・NDK が AAR と同じこと、リンクした `libonnxruntime.so` が AAR のものであること、eSpeak NG / TTS の痕跡がないことを確かめ、環境変数 `WATCHAUDIO_SHERPA_ONNX_JNI_DIR` をそのジョブの後のステップに渡す。結果（ヒットか、SHA-256）はジョブサマリーに出る |
| `build-logic` の `SherpaOnnxJniPlugin`（プラグイン ID `watchaudio.android.sherpa-onnx-jni`、`apps/android` が適用） | `-Pwatchaudio.sherpaOnnxJniDir=<dir>` か環境変数 `WATCHAUDIO_SHERPA_ONNX_JNI_DIR` があるときだけ動く。`prepare<Variant>SherpaOnnxJni` がその `.so` を jniLibs に加え（アプリ自身の jniLibs は依存の同じパスのファイルより優先されるので、AAR のものは入らない）、`verify<Variant>SherpaOnnxJni` が `assemble<Variant>` で APK を検査する: 入っている `.so` が指定したものとバイト単位で同じ、痕跡がない、隣の `libonnxruntime.so` がリンクしたもの（AAR のもの）と同じ。報告は `build/reports/sherpa-onnx-jni/<variant>.txt`。ディレクトリの版が `libs.versions.toml` の版と違えばビルドを失敗させる |

- **CI での使い方**: `deliver.yml`・`android.yml`・`emulator.yml`（phone ジョブと `release-test-apks` ジョブ）が setup-gradle の後にこのアクションを使い、その後の Gradle のビルドがすべて差し替えた `.so` を入れる。さらに、できた APK を `check-no-tts.sh` で検査する（`deliver.yml` は配る phone の APK、`android.yml` は release と debug、`emulator.yml` は release・debug・`ReleaseAsrTest` 用の release）。`deliver.yml` は main 以外のブランチで手動実行すると、同じビルドと検査だけを行い成果物を残さない（変更を main に入れる前に、配布の経路を試せる）。
- **キャッシュが外れたとき**: 版を上げた直後などは、使う各ジョブがそれぞれビルドする（約 7 分。ジョブの制限時間はその分を見込んである）。保存はどれか 1 つが成功すればよい。ブランチで作ったキャッシュはそのブランチ（と PR）でだけ使われ、main で作ったものは全ブランチから使われる。
- **手元で配布と同じ APK を作るとき**: Linux で `tools/sherpa-onnx-android/aar-info.sh <work>` の後に `tools/sherpa-onnx-android/build-jni.sh <work> <dir>`（git・cmake・make・wget・unzip・curl が要る）を実行し、`./gradlew :apps:android:assembleRelease -Pwatchaudio.sherpaOnnxJniDir=<dir>` でビルドする。

**sherpa-onnx の版を上げるとき**: `gradle/libs.versions.toml` の `sherpa-onnx = "<tag>"` の 1 行を変えるだけ。ビルドするタグ・NDK・ABI はそこと AAR から決まり、キャッシュのキーが変わるので CI が新しいタグからビルドし直す。PR では次を確かめる。

1. `android.yml`・`emulator.yml` が通る（どちらもこのアクションを使うので、新しいタグのビルド・痕跡の検査・`libonnxruntime.so` の照合・APK の検査が自動で走る）。`ReleaseAsrTest` の文字起こしの結果が変わらない。
2. 上流のビルドスクリプトのオプション名や既定値が変わってビルドが失敗したら、`build-jni.sh` の環境変数を直す（それでも上流のソースは変えない）。
3. iOS は sherpa-onnx を使わない。ハーネス（`tools/harness`）は JVM 版の sherpa-onnx をそのまま使う（配布しない開発用ツール）。

## 8. 実機検証の手順

- M0-3 / M0-4 電池計測: [docs/research/m0-3-m0-4-battery-protocol.md](../research/m0-3-m0-4-battery-protocol.md)
- Wear OS 録音アプリ（常時録音・転送）の手動テスト: [docs/platforms/wearos.md §10](../platforms/wearos.md#10-実機での手動テスト-チェックリスト)

## 9. 検証データの扱い

- 録音検証では、**参加者全員に録音・文字起こしの目的を説明し同意を得る**。
- 実際の会話音声はリポジトリにコミットしない（個人情報）。評価用の正解テキストと集計結果のみ共有する。
