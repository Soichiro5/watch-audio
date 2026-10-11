# 02. 全体構成（アーキテクチャ）

> 現在のゴール（[03-roadmap.md](03-roadmap.md)）は「装着中は常時録音 → スマホでオンデバイス文字起こし → 端末内に蓄積」。この文書は**実装済みの構成**を基本に書き、AI 要約・チャット・意味検索・体調・場所など後回し（P2）の部分は「将来（P2）」と明記して区別する。

## 1. 設計方針

1. **ローカルのみ / サーバーレス** — 音声・文字起こし・索引はすべて端末内。現時点のネットワーク利用は**文字起こしモデルのダウンロードだけ**（Android: sherpa-onnx の GitHub Releases から `HttpURLConnection` で取得、iOS: Speech のモデルは OS が `AssetInventory` で導入）。Watch → スマホの転送も端末間の直接接続のみ（Wear OS は `Node.isNearby` のノードへ `ChannelClient` / `MessageClient`。`DataClient` のクラウド同期経路は使わない）。将来（P2）の AI 機能で外部の LLM に送る場合も、送るのはテキストだけで、ユーザーが明示的に設定したときに限る。
2. **Watch は薄く、スマホが頭脳** — Watch は「録音・Mark・チャンク化・転送」に専念し、重い処理（ASR）はスマホで後追い実行する。
3. **ロジック共有、プラットフォーム固有部はアダプタ** — 転送プロトコル・DB・取り込み・文字起こしパイプライン・スマホ UI は Kotlin Multiplatform で 1 本化。ASR・ストレージ情報・再生・権限など OS 依存部はインターフェース（`Transcriber`, `StorageEnvironment`, `AppServices`）か `expect/actual`（`core-platform`）で差し替える。`if (platform)` の分岐は書かない。
4. **劣化動作（Graceful degradation）** — モデルが未準備、Watch が未接続などでも、受信済みのデータの保存・閲覧・検索は動く（文字起こしはモデルが揃ってから追いつく）。

## 2. 技術スタック

| レイヤ | 採用 | 理由・実装 |
| --- | --- | --- |
| スマホ共通ロジック | **Kotlin Multiplatform (KMP)** | パイプラインは iOS の `BGProcessingTask` や Android の WorkManager から **UI なしで** 起動される。KMP は iOS ではネイティブフレームワーク（`ComposeApp`）として Swift から直接呼べるため、Flutter/RN のような「バックグラウンドでエンジンを立てる」問題がない。さらに **Wear OS アプリ（Kotlin）とチャンク形式・転送プロトコルのコードを共有**できる |
| スマホ UI | **Compose Multiplatform** | Android/iOS で UI を 1 本化（`composeApp`）。画面は ViewModel（`androidx.lifecycle` の KMP 版）+ `AppServices` インターフェース経由でプラットフォームの機能を使う |
| watchOS アプリ | **SwiftUI + AVFoundation**（ネイティブ必須） | watchOS はクロスプラットフォーム FW 非対応。プロトコルは `iosApp/Shared/ChunkProtocol.swift` に Swift で再実装し、`shared/core-protocol/testdata/*.json` のテストベクタで互換性を確認 |
| Wear OS アプリ | **Kotlin + Compose for Wear OS** | 前景サービス・Ongoing Activity・Data Layer（`ChannelClient` / `MessageClient`）はネイティブ API |
| DB | **SQLite（`androidx.sqlite` の `BundledSQLiteDriver`）+ FTS5 trigram** | 全プラットフォームで同じ SQLite（FTS5 入り）を同梱する。SQL は `LocalStore` に手書きし、スキーマは `core-db` の `Schema.kt`（`MIGRATIONS`、`PRAGMA user_version` で管理、**追記のみ**）。trigram トークナイザで日本語の部分一致検索ができる。ORM やコード生成（SQLDelight・Room など）は使わない |
| ファイル・時刻 | kotlinx-io（`Path` / `SystemFileSystem`）、kotlinx-datetime、kotlinx-serialization（manifest / ACK の JSON） | 共通コードからファイル操作・タイムゾーン計算・JSON を扱うため |
| ASR | iOS: `SpeechAnalyzer`/`SpeechTranscriber`（iOS 26+）<br>Android: sherpa-onnx + ReazonSpeech Zipformer（int8）+ Silero VAD | iOS はモデルが OS 管理でアプリが軽い。Android は日本語対応の高速 OSS。M0-5 の精度比較で Zipformer を採用（モデルは初回にダウンロード、[実装メモ](impl/android-phone.md)） |
| DI | **手書きの composition root**（DI ライブラリは使わない） | Android スマホ: `AppGraph`（`androidApp`）、iOS: `IosAppGraph`（`composeApp/iosMain`）、Wear OS: `AppGraph`（`wearApp/WearApp.kt`）。グラフが小さく、Worker・Service・BGTask から UI なしで同じオブジェクトに届けばよいため |
| 非同期 | Kotlin Coroutines / Flow | KMP 定番 |
| バックグラウンド処理 | Android: WorkManager、iOS: `BGProcessingTask` | 文字起こし・保持期間の適用・途絶チェック |
| ネットワーク | 専用の HTTP クライアントは使わない | 通信はモデルのダウンロードだけ（Android は `HttpURLConnection`、iOS は OS 管理）。AI 機能（P2）の HTTP クライアントは P2 で決める。CI の `tools/privacy-check` が通信 API・依存を検査（§7.3） |
| CI | GitHub Actions（`android.yml` / `emulator.yml`: ubuntu、`ios.yml`: macOS、`deliver.yml`: `main` で APK を保存） | |
| 将来（P2） | 埋め込み（multilingual-e5-small, ONNX）によるベクトル検索、BYOK の LLM（要約・チャット）と API キー保管（Keychain / Keystore）、HealthKit / Health Connect | M0-6 で埋め込みモデルを比較済み（[spikes](spikes/m0-6-embedding-desktop.md)）。採用ライブラリは着手時に決める |

**代替案**: Flutter（UI 開発速度は高いが、iOS バックグラウンド処理と Wear OS 連携でネイティブ実装比率が高くなる）。チームが Dart に強い場合のみ再検討。

**最小 OS**: iOS 26 / watchOS 26（SpeechAnalyzer 利用のため）、Android 10 (API 29) / Wear OS 4（API 33）以上。

## 3. システム全体図

```mermaid
flowchart LR
  subgraph Watch["⌚ Watch (watchOS / Wear OS)"]
    REC[Recorder<br>16kHz mono AAC 24kbps] --> SEG[Chunker<br>約 5 分ごと + 無音チャンク判定]
    MARK[Mark ボタン] --> MAN
    SEG --> MAN[Chunk + Manifest]
    MAN --> Q[Outbox<br>ACK まで保持・再送]
  end

  Q -- "WatchConnectivity / Data Layer（直接接続のみ）" --> ING
  ING -- ACK --> Q

  subgraph Phone["📱 Phone (iOS / Android) — KMP shared"]
    ING[Ingestor<br>サイズ・SHA-256 検証] --> AUD[(Audio Files<br>audio/yyyy/MM/dd)]
    ING --> DB[(SQLite<br>FTS5 trigram)]
    ING --> JOB[Job Scheduler<br>WorkManager / BGProcessingTask]
    JOB --> PIPE[TranscriptionPipeline<br>境界の連結・リトライ] --> ASR[Transcriber<br>sherpa-onnx + VAD / SpeechAnalyzer] --> DB
    JOB --> RET[StorageManager<br>音声の保持期間] --> AUD
    UI[Compose MP UI<br>日別タイムライン / 検索 / 状態 / オンボーディング] --> DB
    UI --> AUD
  end

  MODEL[(モデル配布元<br>GitHub Releases / OS)] -. "モデルのダウンロードのみ" .-> ASR
```

将来（P2）は、文字起こしの後に「埋め込み → ベクトル検索」「1 時間ごとの要約・1 日の振り返り・記憶の抽出」「チャット（RAG）」を追加する（§6.4・§6.5）。

## 4. リポジトリ構成（モノレポ）

`settings.gradle.kts` に含まれる Gradle モジュールと、その他のディレクトリ。構成の整理（`core-search` / `core-ai` の扱いなど）は #49 で行う。

```
watch-audio/
├── build-logic/               # Gradle の convention plugin（KMP ライブラリ共通設定、SQLite テストを JVM/Android/iOS で動かす設定）
├── gradle/libs.versions.toml  # 依存バージョンのカタログ
├── shared/                    # KMP: 共通ロジック
│   ├── core-model/            #   共通の値オブジェクト（TimeRange）
│   ├── core-protocol/         #   ChunkManifest / ChunkAck / ChunkFrame（Wear OS・スマホ共通。testdata/ は Swift 側と共有）
│   ├── core-platform/         #   expect/actual: SHA-256、IoDispatcher など
│   ├── core-db/               #   LocalStore（手書き SQL）、Schema.kt のマイグレーション、FTS5 検索
│   ├── core-pipeline/         #   Ingestor、TranscriptionPipeline、Transcriber、発話区間の切り出し、境界の連結、StorageManager
│   ├── core-search/           #   中身のない仮モジュール（将来のハイブリッド検索用。現在の検索は core-db の FTS5）
│   └── core-ai/               #   中身のない仮モジュール（将来の AI 機能用、P2）
├── composeApp/                # スマホ UI（Compose MP、Android/iOS 共通）、AppServices、ViewModel。iosMain に IosAppGraph と Swift とのブリッジ
├── androidApp/                # Android スマホアプリ（AppGraph、Data Layer 受信、sherpa-onnx 文字起こし、モデル配信、WorkManager）
├── wearApp/                   # Wear OS アプリ（録音の前景サービス、Outbox、ChannelClient 送信、Tile・コンプリケーション）
├── iosApp/                    # XcodeGen プロジェクト（project.yml）
│   ├── iosApp/                #   iOS ホスト（Compose 埋め込み、WatchConnectivity 受信、SpeechAnalyzer）
│   ├── WatchApp/              #   watchOS アプリ（SwiftUI、録音、WatchConnectivity 送信）
│   ├── Shared/                #   ChunkProtocol.swift など、Watch・iPhone・テストで共有する Swift コード
│   └── ProtocolTests/         #   Swift のプロトコル互換テスト（Kotlin と同じテストベクタ）
├── tools/
│   ├── harness/               #   実機なしの通し検証（Watch シミュレータ → 取り込み → sherpa-onnx → SQLite、Gradle モジュール）
│   ├── test-audio/            #   検証用の日本語会話音声セットの生成（Python）
│   └── roadmap-view/          #   Issue ダッシュボードの生成（Python）
├── spikes/                    # 技術検証のスクリプトと結果（ASR・埋め込みのベンチマーク）
└── docs/                      # 調査・設計・ロードマップ・開発環境・impl/（プラットフォーム別の実装メモ）・spikes/
```

## 5. データモデル

### 5.1 実装済みのテーブル

スキーマの正は `shared/core-db/src/commonMain/kotlin/app/watchaudio/core/db/Schema.kt`（マイグレーションは追記のみ）。DB ファイルは `watch-audio.db`、音声ファイルは音声ルート（`audio/`）の下の `yyyy/MM/dd/<chunkId>.<拡張子>`（UTC の日付）に置き、DB にはルートからの相対パスを保存する。録音セッションは独立したテーブルを持たず、`audio_chunk.session_id` でまとめる。

```mermaid
erDiagram
  AUDIO_CHUNK ||--o{ TRANSCRIPT_SEGMENT : transcribed_into
  AUDIO_CHUNK ||--o{ MARK : has
  TRANSCRIPT_SEGMENT ||--|| SEGMENT_FTS : indexed_by
```

| テーブル | 主なカラム |
| --- | --- |
| `audio_chunk` | id（UUID、Watch 採番）, session_id, device_id, source（watchos/wearos…）, started_at, ended_at, time_zone, audio_path（相対。無音チャンクは NULL）, codec, size_bytes, sha256, end_reason（rotation/mark/user_stop/charging/off_body/…）, state（received/transcribing/transcribed/failed/no_audio）, attempts, last_error, received_at, manifest_json, audio_purged_at（保持期間で音声を削除した時刻。文字起こしは残る） |
| `mark` | chunk_id, at（PK は両方） |
| `transcript_segment` | id, chunk_id, start_at, end_at, text, lang, engine |
| `segment_fts` | FTS5(trigram) の外部コンテンツ仮想テーブル（`transcript_segment.text`、トリガで同期） |
| `app_setting` | key, value（音声の保持期間・通知設定など。`CREATE TABLE IF NOT EXISTS` で冪等に作成） |

時刻はすべてエポックミリ秒。

### 5.2 将来（P2）のテーブル（構想、未実装）

AI 要約・チャット・意味検索・体調/場所に着手するときに、マイグレーションを追記して作る。名前とカラムは構想段階。

```mermaid
erDiagram
  TRANSCRIPT_SEGMENT ||--o| SEGMENT_EMBEDDING : has
  HOUR_REVIEW ||--o{ HIGHLIGHT : produces
  HIGHLIGHT }o--o{ TRANSCRIPT_SEGMENT : cites
  DAY ||--o{ HOUR_REVIEW : has
  DAY ||--o| DAILY_REFLECTION : has
  MEMORY_FACT }o--o{ TRANSCRIPT_SEGMENT : sourced_from
  CHAT_THREAD ||--o{ CHAT_MESSAGE : has
  CHAT_MESSAGE }o--o{ TRANSCRIPT_SEGMENT : cites
  CONTEXT_LABEL }o--|| DAY : on
```

| テーブル | 主なカラム |
| --- | --- |
| `segment_embedding` | segment_id, model_id, vector(int8 BLOB), scale |
| `hour_review` | day, hour, density_score, summary, state, provider, model |
| `highlight` | id, hour_review_id, title, body, cited_segment_ids |
| `daily_reflection` | day, body |
| `memory_fact` | id, kind(person/preference/plan/…), text, active, source_segment_ids |
| `chat_thread` / `chat_message` | role, content, cited_segment_ids |
| `context_label` | kind(body/place/activity), start_at, end_at, label, confidence |

## 6. 主要フロー

### 6.1 録音（Watch）

詳細は [impl/wearos.md](impl/wearos.md)・[impl/ios-watchos.md](impl/ios-watchos.md)。

- **開始**: ユーザーが Watch アプリでタップ（前景）。watchOS は `AVAudioSession(.record)` + background audio、Wear OS は `foregroundServiceType=microphone` の前景サービス + Ongoing Activity。一度 ON にすると、充電・非装着（Wear OS のみ検出）・電池残量低下・容量不足・割り込みで一時停止し、条件が解けたら自動で再開する（watchOS の充電後の再開はアプリが前面に戻ったとき）。
- **エンコード**: 16kHz / mono / AAC-LC 24kbps（.m4a、1 時間 ≈ 10.8MB）。
- **チャンク化**: 約 5 分ごと（停止・一時停止でも閉じる。watchOS は Mark でも閉じる）にファイルをローテーション。各チャンクに manifest（`ChunkManifest`: チャンク UUID、セッション ID、デバイス ID、開始時刻、長さ、タイムゾーン、Mark のオフセット、音声ファイルの形式・サイズ・SHA-256、終了理由）を付ける。
- **無音スキップ**: 1 秒ごとのレベル（Wear OS: ピーク、watchOS: 平均・ピーク）がチャンク全体で一度もしきい値を超えなかった場合だけ「確実に無音」として音声を捨て、`audio` なしの manifest で時間範囲だけを送る（タイムライン上は無音区間として残る）。
- **割り込み・停止**: 終了理由（`interruption` / `charging` / `off_body` / `low_battery` / `terminated` など）を manifest に記録し、欠損の理由としてタイムラインに出す。予期しない停止は Watch 上で通知/ハプティクスを出す。

### 6.2 転送（Watch → Phone）

- **iOS**: `WCSession.transferFile(_:metadata:)`（無音チャンクは `transferUserInfo`）。Phone 側で保存完了後、`transferUserInfo` で ACK → Watch がファイル削除。
- **Android**: `ChannelClient` でファイルストリーム送信（`ChunkFrame`: 4 byte 長 + manifest JSON + 音声）→ Phone 側で保存・ハッシュ検証後に `MessageClient` で ACK。直接接続（`isNearby`）のノードとだけやり取りし、Data Layer のクラウド経路は使わない。フレームが壊れている・転送が途切れた場合は ACK を返さず、Watch の再送に任せる。
- **冪等性**: チャンク UUID で重複受信を無視。ACK 未着のチャンクは Watch 側で再送キューに残す。
- **プロトコル定義**は `shared/core-protocol` に置き、Wear OS とスマホ側で同一コードを使う（watchOS 側は同仕様を Swift で実装し、テストベクタで互換性を保証）。

### 6.3 処理パイプライン（Phone）

```
received → transcribing → transcribed
                       ↘ failed（リトライ上限まで再試行）
no_audio（無音チャンク。文字起こししない）
```

- ジョブはべき等・再開可能（各チャンクの `state` で進捗管理）。将来（P2）は文字起こしの後に埋め込み・要約などの段を追加する。
- iOS: 前景時は即時処理、バックグラウンドは `BGProcessingTask`（充電中、`requiresExternalPower = true`）。
- Android: WorkManager。チャンク受信ごとに unique な 1 回限りジョブ（実行中なら後続を 1 つだけ追加）、保険として充電中に 6 時間ごとの定期ジョブ。プロセス起動時に中断チャンクを再キュー（詳細は [docs/impl/android-phone.md](impl/android-phone.md)）。
- 音声の保持期間ポリシー（#33）: 文字起こし完了済みのチャンクのみ削除対象。文字起こしの後に同じバックグラウンド処理（Android `TranscriptionWorker`、iOS `BGProcessingTask` / 前面実行）で `StorageManager.applyRetention()` を実行する。詳細は §9.3。
- 文字起こしエンジンは共通インターフェース `Transcriber`（`core-pipeline`）の裏に置く。入力は「音声パーツ（ファイル + 範囲 `[fromMs, toMs)`）のリスト」で、エンジンはそれを連結した 1 本の録音として認識し、連結先頭からの相対時刻で発話を返す（各パーツはちょうど `toMs - fromMs` の長さにする。ファイルが短ければ無音で埋める）。実装: Android = sherpa-onnx（各ファイルを `MediaCodec` でデコードして PCM を連結）、iOS = SpeechAnalyzer（Swift 側で一時 CAF ファイルに連結）、JVM ハーネス = sherpa-onnx（ffmpeg でデコード）。

#### 発話区間の切り出し（Android / JVM ハーネス、#68）

sherpa-onnx の経路では、Silero VAD（しきい値 0.3、最小無音 0.5 秒、最小発話 0.25 秒、最大発話 20 秒）で見つけた発話区間を `padSpeechSpans`（`core-pipeline/SpeechSegmentation.kt`、設定は `AsrSettings`）で認識器への入力に切り分ける。Android とハーネスは同じコードを使う。iOS の SpeechAnalyzer は音声ファイルを丸ごと渡し、区切りは OS に任せる（この処理は通らない）。

1. **余白**: 各区間の前後に 0.3 秒の実音声を付ける（語頭が欠けないように）。
2. **結合と切り分け**: 余白が重なる隣の区間は、余白込みで 20 秒（`MAX_SEGMENT_SECONDS`）以内に収まる場合だけ 1 つにまとめる。収まらない場合はまとめず、重なった余白の中で最も静かな点で切り分ける（同じ音声を 2 回認識しない）。
3. **間での分割**: 各区間の中に「間」があれば、その中央で切る。間とは、10 ms ごとのエネルギーが区間内の大きい音（95 パーセンタイル）より 30 dB 以上小さい状態が 0.22 秒（`MIN_PAUSE_SECONDS`）以上続く所。会話の話者交代（平均 0.35 秒）を捉えるため。残響のある音声では、Silero の確率は無音になっても 1 秒以上しきい値を下回らないので、VAD の最小無音を短くしても話者交代では切れない。
4. **上限**: それでも 20 秒を超える区間（間のない長い発話、雑音で間が埋まった会話）は、最も静かな点（前後 50 ms のエネルギーが最小の所）で 20 秒以下に分ける。どの片も 5 秒以上にする。

結果として、認識器への入力は必ず 20 秒以下になり、入力どうしは重ならない。

**理由**: ReazonSpeech の Zipformer は、1 つの入力に複数の話者交代が入ると文をまとめて落とす。以前は VAD が最大 20 秒で切った区間どうしが接していたため、余白を付けると重なって結合し直され、入力が最大 109 秒になっていた。上限を 20 秒にするだけでは足りず（会話の CER 53.8% → 44.3%、区間長の中央値 10 秒）、話者交代ごとに切って初めて正解区間で切った場合に近づいた（15.0%、中央値 2 秒前後）。測定結果は [test-audio.md §4](test-audio.md#4-結果)。

**残る制約**: 雑音のある条件（オフィス・カフェ・屋外）では間が雑音で埋まり、エネルギーでは話者交代を見つけられない。区間が長いままになり、正解区間で切った場合との差が大きい。

#### チャンク境界をまたぐ発話（#45）

Watch は発話に関係なく約 5 分ごとにチャンクを区切るので、境界をまたぐ発話を別々に認識すると前後で単語が切れて誤認識する（ハーネスの例: 「持ち主とはぐれた / そして傘が風で舞い」「バルクイ / 十八歳の時だった」）。そこで `TranscriptionPipeline` は、チャンクを**隣のチャンクの境界付近の音声と連結して**認識する（`core-pipeline/BoundaryJoin.kt`）。

```
  前のチャンク                    このチャンク                   次のチャンク
  … ▕最後の発話▏|…………………………………………………………………|▕最初の発話▏ …
     └ tail ┘ +              チャンク全体              + └ head ┘
```

1. **連結する条件**: 隣が同じセッション・同じデバイスで、前側の終了理由が `ROTATION` / `MARK`（ギャップなし）、前の終了と後の開始の差が 1 秒以内、両方に音声があり、隣が**文字起こし済み**で、境界から 1.5 秒以内に発話（保存済みセグメント）があること。
2. **範囲**: 前のチャンクは「境界に達している発話の開始 − 0.5 秒（ただしその前の発話の終わりより前には戻らない）」から終わりまで（tail）。次のチャンクは開始から「先頭の発話の終わり + 0.5 秒（次の発話の開始まで）」まで（head）。どちらも最大 30 秒で、それより長いと連結しない。
3. **帰属と置き換え**: 認識結果は**開始時刻が入っているチャンク**のセグメントとして絶対時刻で保存する（境界をまたぐセグメントは前のチャンクに属し、`end_at` がそのチャンクの終了を越える）。隣のチャンクの、再認識した範囲内で始まるセグメントは新しい結果で置き換える（`StoreTransaction.replaceSegments`）。このチャンク自身の結果と合わせて 1 トランザクションで保存するので、重複も欠落も起きない。
4. **到着順**: 境界の 2 つのチャンクのうち**後から文字起こしされる方**が連結を行う。通常は古い順に処理するので後のチャンクが前の tail を使い、後のチャンクが先に届いて処理済みなら、前のチャンクの処理時に後の head を使って直す。欠けていたチャンクが後から届けば両側を連結する。同じチャンクを再処理しても結果は同じ（冪等）。
5. **失敗時**: 連結した認識が失敗したら（隣の音声が読めない等）、そのチャンク単体で認識し直す。

**コスト**: 追加で認識するのは発話のある境界ごとに「その発話 + 余白」（最大 30 秒）だけ。ハーネスの例（36 秒の録音、境界 2 か所）では順番どおり 39.8 秒、逆順到着 43.4 秒分を認識した。1 日 16 時間（境界 192 か所）で境界の半分に 5 秒程度の発話があると仮定すると追加は約 9 分の音声で、処理時間の増加は 1 日数分以内に収まる見込み（Android は隣のファイルを丸ごとデコードしてから切り出すので、そのデコード時間も加わる）。

**残る制約**:

- 境界付近の発話が 30 秒より長い、または隣が別セッション・別デバイス・ギャップ（停止や割り込み）の場合は連結しない。隣が `FAILED` のまま、または音声が削除済みの場合も単体で認識する。
- 境界をまたぐ発話をタップして再生すると、属するチャンクの音声の終わりで止まる（次のチャンクへ続けて再生しない）。
- 連結の判定は保存済みセグメント（VAD / SpeechTranscriber の結果）に依存する。境界直前の発話を VAD が取りこぼした場合（0.25 秒未満など）は連結されない。
- 隣のチャンクの境界付近のセグメントは後から書き換わる（タイムライン・検索結果がそのぶん変わる）。

### 6.4 検索

実装済み: `transcript_segment` の FTS5（trigram）によるキーワードの部分一致検索。trigram は 3 文字未満の語を引けないので、1〜2 文字の検索は `idx_segment_start_text` を新しい順に走査する `LIKE` で補う（#23、性能は §8）。

#### 将来（P2）: 意味検索とチャット（RAG）

1. クエリを埋め込み → ベクトル上位 K 件 + FTS5 上位 K 件を RRF（Reciprocal Rank Fusion）で統合。
2. 日付/時間帯フィルタ（「先週の火曜」など）は LLM の関数呼び出しで抽出（AI 未設定時は UI フィルタ）。
3. 前後の文脈セグメント + 関連記憶 + 1 時間ごとの要約をコンテキストにして LLM へ。
4. 回答中の引用 `[seg:123]` を UI で原文・音声再生へのリンクに変換。

### 6.5 将来（P2）: AI プロバイダ（BYOK）

未実装の構想。HTTP クライアントや SDK は着手時に決める。

```kotlin
interface LlmProvider {
    suspend fun complete(request: LlmRequest): LlmResponse          // 構造化出力(JSON)対応
    fun stream(request: LlmRequest): Flow<LlmDelta>
    suspend fun listModels(): List<ModelInfo>
}
// 実装: OpenAiCompatibleProvider（OpenAI / ローカル LLM / 任意エンドポイント）, AnthropicProvider, GeminiProvider
// Phase 2: OnDeviceProvider（Apple Foundation Models / Gemini Nano）
```

- 送信前に `PrivacyFilter` を必ず通す（音声・生の心拍値・正確な座標は型レベルで送れない設計にする）。
- 自動レビューは「1 日あたりの上限回数/トークン」を設定可能にし、想定外の課金を防ぐ。

## 7. セキュリティ・プライバシー

| 項目 | 方針 |
| --- | --- |
| 保存 | アプリサンドボックス内のみ（§7.1）。iOS・watchOS は `Application Support/WatchAudio`（ファイル保護クラス `CompleteUntilFirstUserAuthentication` を明示。ロック中の録音・送信・BGTask からも読める）、Android は内部ストレージ（`files/`） |
| バックアップ | OS バックアップの対象外（データを端末外に出さないため。§7.1）。Android / Wear OS は `allowBackup=false` とバックアップ除外ルール、iOS・watchOS は保存先に `NSURLIsExcludedFromBackupKey`。E2E 暗号化バックアップは後続フェーズ（#40） |
| API キー | 現在は扱わない。将来（P2）の BYOK では Keychain（`ThisDeviceOnly`）/ Android Keystore にだけ保存する |
| 外部送信 | なし（ネットワーク利用は文字起こしモデルのダウンロードのみ。§7.2、CI で検査 §7.3）。将来（P2）に AI プロバイダ（テキストのみ、ユーザーが設定した場合）、OS ジオコーダを検討（追加時は §7.3 の許可リストを更新）。解析 SDK・広告 SDK・クラッシュ送信 SDK は入れない |
| 同意 | 初回オンボーディングで「録音対象者への告知」の注意喚起。Watch に録音中インジケータを常時表示 |
| 削除 | 「この日のデータを削除」（その日に始まったチャンクの音声・文字起こし・マーク）と「すべてのデータを削除」（全録音・文字起こし・音声ファイル。DB は `VACUUM` で縮める）。どちらもアプリ内の確認ステップ必須（全削除は「削除」と入力）。システムダイアログは使わない。AI 設定・API キーは実装時に全削除の対象に加える |

### 7.1 保存場所と保護（#76）

| 端末 | データ | 場所 | 保存時の保護 | バックアップ |
| --- | --- | --- | --- | --- |
| Android スマホ | DB（文字起こし・チャンク）、音声、ASR モデル | `files/watch-audio.db`、`files/audio/`、`files/models/` | 内部ストレージ（ファイルベース暗号化の資格情報暗号化領域。初回ロック解除後に読める） | `allowBackup="false"`。`data_extraction_rules`（Android 12+）と `backup_rules`（11 以下）で全ドメインをクラウドバックアップから除外。機種変更時の端末間転送（ケーブル・ローカル Wi-Fi）は端末間で完結するため許可 |
| Android スマホ | 受信中のチャンク | `cache/incoming/` | 同上 | 同上 |
| Wear OS | 録音中・送信待ちのチャンク | `files/recording/`、`files/outbox/` | 同上 | `allowBackup="false"`。`data_extraction_rules` でクラウドバックアップと端末間転送の両方から全ドメインを除外 |
| Wear OS | 診断ログ（イベントと電池残量の CSV。音声・文字起こしは含まない） | アプリ専用の外部ストレージ `Android/data/app.watchaudio/files/diagnostics/` | 他アプリからは読めない（Android 11+）。adb では取り出せる（電池計測用） | 同上（`external` ドメインも除外） |
| iPhone | DB、音声、受信直後のファイル | `Application Support/WatchAudio/`（`watch-audio.db`、`audio/`、`inbox/`） | `NSFileProtectionCompleteUntilFirstUserAuthentication` をディレクトリに明示（`LocalDataDirectory`）。中に作るファイルはこのクラスを引き継ぐ | `NSURLIsExcludedFromBackupKey`（ディレクトリに設定すると中身も対象外） |
| iPhone | 文字起こし用の一時ファイル | `tmp/` | 既定（同上） | 一時ディレクトリはバックアップされない。処理後に削除 |
| Apple Watch | 録音中・送信待ちのチャンク、診断ログ | `Application Support/WatchAudio/`（`recording/`、`outbox/`、`diagnostics.csv`） | `FileProtectionType.completeUntilFirstUserAuthentication` をディレクトリに明示（`LocalDataProtection`） | `isExcludedFromBackup`（同上） |

- 保護クラスを `Complete`（ロック中は読めない）にしないのは、画面オフ・ロック中の録音継続、送信待ちの再送、充電中の BGTask での文字起こしがロック中に動くため。再起動後に一度もロック解除されていない間だけ読めない。
- iOS・watchOS の属性は `LocalDataDirectoryTest`（Kotlin、iOS シミュレータ）と `LocalDataProtectionTests`（Swift、`ProtocolTests`）が ios.yml で確認する。バックアップ除外は値まで確認する。保護クラスは設定 API が成功することを確認し、値はファイルシステムが報告する場合だけ比較する（シミュレータには Data Protection がないため。実際の暗号化は実機でのみ有効）。

### 7.2 通信箇所の一覧

| 端末 | 通信 | 宛先 | 実装 |
| --- | --- | --- | --- |
| Android スマホ | ASR モデル（sherpa-onnx）のダウンロード | `https://github.com/k2-fsa/sherpa-onnx/releases/download/`（GitHub Releases） | `androidApp/.../asr/ModelManager.kt`（`HttpURLConnection`）。INTERNET 権限はこのためだけ |
| Wear OS → スマホ | チャンクと ACK | 直接つながっているペアのスマホ（`Node.isNearby`） | `ChannelClient` / `MessageClient`（Wearable Data Layer）。クラウド経由で同期されうる `DataClient` は使わない。Wear OS アプリに INTERNET 権限はない |
| Apple Watch → iPhone | チャンクと ACK | ペアの iPhone | WatchConnectivity（`transferFile` / `transferUserInfo`）。OS が管理する端末間の転送 |
| iPhone | 音声認識モデル | Apple（OS が管理） | `SpeechAnalyzer` の `AssetInventory`。アプリは URL を持たない。文字起こし自体は端末上 |

アプリのコードに通信 API・URL があるのは `ModelManager.kt` だけ。CI の `tools/privacy-check` がこの一覧（`policy.py` の `ALLOWED_API_USES` / `ALLOWED_URL_PREFIXES`）とソースを照合し、ジョブサマリーに通信箇所を出す（§7.3）。

### 7.3 CI での検査（`tools/privacy-check`、#76）

`android.yml` の最初と Assemble の後に実行し、許可リスト（`tools/privacy-check/policy.py`）にないものがあると失敗する。

- **権限**: 各アプリのマニフェスト（全ソースセット）と、リリースのマージ後マニフェスト（ライブラリが足す権限）。Wear OS に INTERNET はない。
- **バックアップ**: `allowBackup="false"`、`dataExtractionRules` があり、全ドメインを除外している（`<include>` は不可）。`usesCleartextTraffic` と独自の `networkSecurityConfig` も不可。
- **通信・保護**: ソース（Kotlin / Java / Swift、コメントを除く）での HTTP クライアント・ソケット・WebView・`URLSession`・Network.framework・サーバー型の `SFSpeech…RecognitionRequest`・`DataClient`・保護なしのファイル（`.noFileProtection` など）の利用、URL リテラル。
- **依存**: Gradle ファイルに解析・広告・クラッシュ送信・HTTP クライアントの SDK がない。リリースの実行時クラスパス（推移的な依存を含む）がすべて許可リストの範囲内。
- **iOS**: `project.yml` の Info.plist のキー・`UIBackgroundModes`、entitlements（現状なし）。

使い方と許可リストの広げ方は [04 §6.3](04-dev-environment.md#63-local-only-チェックtoolsprivacy-check76)。

## 8. 非機能目標（MVP）

| 指標 | 目標 |
| --- | --- |
| Watch 連続録音 | Apple Watch Series 10 以降で 10 時間以上、Pixel Watch 3 / Galaxy Watch 7 以降で 8 時間以上（M0 で実測し確定） |
| 転送遅延 | 接続中は録音から 20 分以内に Phone 着 |
| 文字起こし | 充電中なら 1 日分（8h 録音）を 1 時間以内に処理 |
| 検索応答 | 1 年分のデータで 300ms 以内（50 万セグメントの JVM ベンチマークで最悪 p95 約 150ms: [04 §5](04-dev-environment.md#5-検索の性能ベンチマーク23)。実機は未計測） |
| ストレージ | 8h/日で 1 日 ≈ 120MB 以下（音声）|

## 9. 常時録音（装着中）の設計

現在のゴール（[03-roadmap.md](03-roadmap.md)）は「**装着中は常に録音**し、充電中・就寝中（外している間）は止まってよい」。プロダクトのテーマ（[01 §2](01-research.md#2-作るべきものプロダクト定義)）の「24 時間録音」のうち、Watch を外している時間の欠損は許容し、代わりに「止まったことに気づける」「欠損の理由が分かる」ことを重視する。

### 9.1 録音ソース: Watch のみ（スマホ録音へのハンドオフは採用しない）

- **録音するのは Watch だけ**。Watch が充電中・非装着の間は録音しない（終了理由 `charging` / `off_body` を記録し、タイムラインには理由付きの欠損として出す）。条件が解けたら Watch 側で自動再開する（§6.1）。
- 以前は、Watch の充電中・非装着時にスマホのマイクで録音を引き継ぐ「ハンドオフ」を計画していた（#42）。**採用しない**（#42 は not planned でクローズ）。理由: 「充電中・就寝中は止まってよい」とゴールを変えたため不要になった。あわせて、スマホ側に常駐の録音（iOS の background audio、Android の `microphone` 前景サービス）と、2 台が同時に録った区間の重複解消が要らなくなり、電池・プライバシー・実装の負担を避けられる。
- 録音ソースの種類は manifest の `source`（`watchos` / `wearos` / `ios` / `android`）として残してあるが、現在スマホは録音しない。

### 9.2 連続性の監視

- スマホ側で「最後にチャンクが届いた時刻」を監視し、10 分を超えて途絶えたらローカル通知を出す（#43）。最新チャンクが停止理由付き（ユーザー停止・充電・非装着など）で終わっている場合は通知しない。おやすみ時間（既定 23:00–7:00）は通知を終了時刻まで遅らせる。Android は WorkManager の定期チェック、iOS は予約通知（[impl/phone-ui.md](impl/phone-ui.md) §1.5）。
- タイムラインに**欠損区間とその理由**（充電中・非装着・割り込み・電池切れ・OS による終了・ユーザー停止）を表示する。
- 1 日ごとの「録音カバー率」（録れていた時間 ÷ 24h）を計測し、改善の指標にする。

### 9.3 データ量の見積もり

| 条件 | 1 日 | 1 年 |
| --- | --- | --- |
| 24h × AAC 32kbps（無音も保存） | 約 345MB | 約 126GB |
| 24h、無音区間を捨てる（音声 8h 相当） | 約 115MB | 約 42GB |
| 同上 + Opus 16kbps | 約 58MB | 約 21GB |
| 文字起こし + 索引（ベクトル int8 256 次元を含む） | 約 2〜4MB | 約 1〜1.5GB |

→ 文字起こしは無期限に保存しても問題ない。音声は「無音を捨てる + 低ビットレート」と、保持期間・バックアップ先へのオフロードで管理する。

**ストレージ管理（#33、実装済み）**

- 音声の保持期間: 7 日 / 30 日 / 90 日 / **無期限（既定。テーマ「すべて残す」）**。設定は `app_setting.audio_retention`。
- 削除対象は**文字起こし完了済み**（`TRANSCRIBED`）かつ終了時刻が「今 − 保持日数」以前のチャンクの音声ファイルだけ。未処理・処理中・失敗のチャンクの音声は消さない。文字起こしは常に残り、UI ではそのチャンクの発話を再生ボタンなしで表示する。
- 削除は「ファイル削除 → `audio_purged_at` を記録」の順（中断しても次回で整合する）。DB の `audio_path` は残す（タイムライン上は録音できていた時間として表示）。
- 容量の内訳（音声 / 文字起こし・索引 = DB ファイル / モデル / 空き容量）を状態画面に表示し、空き容量が 1 GB 未満なら警告する。モデルサイズと空き容量はプラットフォーム実装（`StorageEnvironment`）から取得する（iOS の Speech モデルは OS 管理のため不明扱い）。
- 未対応: バックアップ（#40）済みの音声だけを消す「オフロード」、コーデック比較（AAC 32kbps vs Opus）による上表の更新。

### 9.4 OS の制約（要実機検証）

| 制約 | watchOS | Wear OS |
| --- | --- | --- |
| 録音開始 | アプリ前景からのみ | アプリ前景からのみ（Android 14+）。再起動後に自動開始できない |
| 割り込み後の自動再開 | 不確実（M0-3 で検証） | AudioFocus / 同時録音の扱いを M0-4 で検証 |
| 電池（24h） | 約 4%/時（既存の Watch 録音アプリの公開値からの推定）。充電中は録音しない（§9.1） | 機種差大（M0-4 で検証） |
