# watch-audio

スマートウォッチを装着している間ずっと録音し、スマホ上で文字起こしして、データを端末内にためていくアプリ。
**データはローカルのみ**（クラウドには上げない）。
**iOS + watchOS / Android + Wear OS** のクロスプラットフォーム対応。

## 開発の基本原則

1. **データはローカルのみ** — 録音・文字起こし・あらゆるユーザーデータを外部に送らない。ネットワーク利用はモデルのダウンロードと、本人がエージェント連携を ON にしている間の、ペアリングした自分の PC への LAN 内の接続だけ。Watch→スマホ転送も端末間の直結のみ。
2. **コアから作る** — 「装着中は常時録音 → 文字起こし → ローカルに蓄積」を最優先（P0）。AI 要約・チャットなどは後回し（P2）。
3. **実機がなくても検証できるようにする** — ロジックは単体テスト、全体は Linux 上の E2E ハーネスとエミュレータ/シミュレータの CI で確認する。実機でしか確かめられないことは `needs-device` として明示する。
4. **共通化はロジック、UI 以外の OS 依存はアダプタで** — 共通ロジックは Kotlin Multiplatform、Watch アプリはネイティブ。OS 差分はインターフェースの裏に閉じ込める。
5. **小さく積んで、常に緑** — 小さな単位でコミット・push し、CI が赤なら新しい作業より先に直す。
6. **ドキュメントと Issue を同時に更新する** — 変更のたびに関係するドキュメントと Issue（進捗・ラベル・クローズ）を更新する。
7. **Issue は完了条件で閉じる** — すべての Issue に「完了条件」（判定できる条件と確認方法）を書く。条件を変えたら本文を更新し、理由をコメントに残す。クローズは、各条件を満たした**証拠**（テスト・CI の結果、コミット/PR、計測値、スクリーンショット）を条件ごとにコメントしてから行う。実装しただけではクローズしない。
8. **開発環境で確かめられることを優先する** — 完了条件はできるだけ開発環境（CI・テスト・実機なし E2E・エミュレータ/シミュレータ）で確認できる形にする。実機でしか確かめられない条件は、プラットフォームごとの「実機検証」Issue（`needs-device`）に切り出し、実装の Issue は開発環境の証拠でクローズする。電池計測のように根本的に実機が必要なものだけ、その Issue 自体を `needs-device` で残す。
9. **秘密情報をリポジトリに置かない** — リポジトリと Issue は公開。API キー・署名鍵・認証情報・個人データをコード・Issue・PR・CI ログに書かない。CI は GitHub Actions のシークレット、ローカルは Git 管理外のファイル（`local.properties` など）を使う。アプリに埋め込んだ鍵は取り出せるため、AI プロバイダの API キーはアプリに入れない（BYOK のキーは端末の Keystore / Keychain にだけ保存）。

## 進め方

- **`main` は正式ブランチ**: CI と CD（ビルド成果物の作成）は `main` で動く。`main` には直接コミット・push しない。
- **必ず別ブランチで開発して PR を出す**: Issue ごとに作業ブランチ（`feat/<Issue番号>-<内容>` など）を切り、**Issue と紐づけた PR**（本文に `Closes #番号`、一部対応なら `Refs #番号`）を作る。`Closes` は PR だけで完了条件をすべて満たす場合に限る。PR でも CI が動き、緑になってから `main` にマージする（現時点では Claude が CI を確認してマージしてよい）。マージで Issue は自動でクローズされる。
- **Issue**: 優先度ラベル `P0`（今やる）/ `P1`（次）/ `P2`（後回し）、状態ラベル `in-progress` / `needs-device`、Issue 間の依存は GitHub の「Blocked by」で管理。現在のゴールはマイルストーン「v0.1 コア」。
- **ダッシュボード**: リリース（Milestone）→ ユースケース → 作業 Issue の順に進み具合を一覧できるページを `tools/roadmap-view/build.py` で生成している（[ロードマップ](https://claude.ai/artifact/W2TAExeoMUzXQKTo6h4QPE)、オーナーのみ閲覧可）。
- **CI/CD**: GitHub Actions。PR と `main` で `android.yml`（テスト・実機なし E2E・ビルド）/ `ios.yml`（iOS・watchOS、関連変更時のみ）/ `emulator.yml`（起動スモークテスト）、`main` へのマージで `deliver.yml`（スマホ・Wear OS の APK を成果物として保存。将来は TestFlight / Play 内部テストへ自動配信）。
- **AI エージェント向けの作業ルール**: [CLAUDE.md](CLAUDE.md)

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [docs/product/01-research.md](docs/product/01-research.md) | 市場環境・作るべきもの・実現性評価 |
| [docs/architecture/02-architecture.md](docs/architecture/02-architecture.md) | 技術スタック・全体構成・データモデル・主要フロー |
| [docs/product/03-roadmap.md](docs/product/03-roadmap.md) | 実装手順（マイルストーンと Issue 一覧） |
| [docs/dev/04-dev-environment.md](docs/dev/04-dev-environment.md) | 開発・検証環境、ビルド手順、実機なしでの通し検証 |
| [docs/research/](docs/research/) | 技術検証の結果（ASR・埋め込み・電池計測手順） |
| [docs/platforms/android-phone.md](docs/platforms/android-phone.md) | Android スマホ側の実装（受信・ACK・オンデバイス文字起こし・モデル配信） |
| [docs/platforms/phone-ui.md](docs/platforms/phone-ui.md) | スマホ UI（タイムライン・検索・状態画面）の実装メモ |
| [docs/legal/](docs/legal/privacy-policy.ja.md) | プライバシーポリシー（日本語・英語）とストア申告の下書き（#36、レビュー前） |

## 技術スタック

- 共通ロジック / スマホ UI: Kotlin Multiplatform + Compose Multiplatform
- watchOS: SwiftUI / Wear OS: Compose for Wear OS
- オンデバイス ASR: SpeechAnalyzer (iOS) / sherpa-onnx + Zipformer ReazonSpeech (Android)
- ローカル DB: bundled SQLite + FTS5（日本語部分一致検索）
- 意味検索: ruri-v3-30m（int8 ONNX）を端末内の ONNX Runtime で実行（Android は sherpa-onnx の ONNX Runtime、iOS は Swift パッケージ）。モデルは検索タブを初めて開いたときに Wi-Fi でダウンロード
- AI 要約・チャット: 後回し（将来はクラウド LLM も可）

## ライセンス

アプリが使っているライブラリとモデルの名前・作者・ライセンスは、アプリの「状態」タブの「ライセンス」で確認できる（#167）。主なもの:

- Apache-2.0: sherpa-onnx、ReazonSpeech k2 v2（日本語モデル）、Ruri v3 30m（意味検索のモデル）、AndroidX、Kotlin・kotlinx、Compose Multiplatform、Skiko
- MIT: Silero VAD、ONNX Runtime ほか
- FunASR Model Open Source License Agreement v1.1: SenseVoice Small（英語モデル。「英語も文字起こしする」をオンにしたときだけダウンロード）
- パブリックドメイン: SQLite
- そのほか（BSD・Unicode・Google Play services の SDK ライセンスなど）と、確認中の点は [docs/legal/store-data-declarations.md §8](docs/legal/store-data-declarations.md#8-使っているライブラリとモデルのライセンス167)

## リポジトリ構成

```
apps/android/        Android スマホアプリ
apps/wear/           Wear OS アプリ
apps/ios/            iOS アプリ + watchOS アプリ（XcodeGen: project.yml）
shared/              Kotlin Multiplatform の共通ロジック（protocol, platform, db, pipeline, agent）
shared/ui/           スマホ UI（Compose Multiplatform、Android/iOS 共通）
build-logic/         Gradle の共通設定（convention plugin）
tools/harness/       実機なしの通し検証（Watch シミュレータ + sherpa-onnx、Linux/CI）
tools/agent-bridge/  エージェント連携の橋渡し CLI（watch-audio-mcp。PC の AI エージェント用の MCP サーバー）
tools/agent-fake-phone/ 橋渡し CLI の E2E 用の偽スマホ（JVM）
tools/test-audio/    検証用音声セットの生成（オフライン TTS の日本語会話 + Watch 録音を模した加工）
tools/privacy-check/ 「データは端末内のみ」の CI チェック（権限・バックアップ・通信・依存の許可リスト）
tools/check-links/   Markdown のリンク切れ検査（CI: links.yml）
tools/check-version/ アプリのバージョンの一致検査（gradle.properties と iOS の project.yml、CI: android.yml）
tools/roadmap-view/  Issue ダッシュボードの生成
research/            技術検証のベンチマーク（ASR・埋め込み、Python）
docs/                product（調査・ロードマップ）/ architecture / platforms / dev（開発環境）/ research（検証結果）
```

ビルド手順は [docs/dev/04-dev-environment.md](docs/dev/04-dev-environment.md) を参照。
