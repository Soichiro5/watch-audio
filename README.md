# watch-audio

スマートウォッチを装着している間ずっと録音し、スマホ上で文字起こしして、データを端末内にためていくアプリ。
**データはローカルのみ**（クラウドには上げない）。
**iOS + watchOS / Android + Wear OS** のクロスプラットフォーム対応。

ベンチマーク: [Auday — All-Day Watch Recorder](https://auday.ai/)（iOS 専用・中英のみ）

## 開発の基本原則

1. **データはローカルのみ** — 録音・文字起こし・あらゆるユーザーデータを外部に送らない。ネットワーク利用はモデルのダウンロードだけ。Watch→スマホ転送も端末間の直結のみ。
2. **コアから作る** — 「装着中は常時録音 → 文字起こし → ローカルに蓄積」を最優先（P0）。AI 要約・チャットなどは後回し（P2）。
3. **実機がなくても検証できるようにする** — ロジックは単体テスト、全体は Linux 上の E2E ハーネスとエミュレータ/シミュレータの CI で確認する。実機でしか確かめられないことは `needs-device` として明示する。
4. **共通化はロジック、UI 以外の OS 依存はアダプタで** — 共通ロジックは Kotlin Multiplatform、Watch アプリはネイティブ。OS 差分はインターフェースの裏に閉じ込める。
5. **小さく積んで、常に緑** — 小さな単位でコミット・push し、CI が赤なら新しい作業より先に直す。
6. **ドキュメントと Issue を同時に更新する** — 変更のたびに関係するドキュメントと Issue（進捗・ラベル・クローズ）を更新する。

## 進め方

- **ブランチ**: `main` が開発ブランチ。小さな単位でコミットして push する。並行作業は作業用ブランチ（worktree）で行い、`main` にマージする。
- **Issue**: 優先度ラベル `P0`（今やる）/ `P1`（次）/ `P2`（後回し）、状態ラベル `in-progress` / `needs-device`、Issue 間の依存は GitHub の「Blocked by」で管理。現在のゴールはマイルストーン「v0.1 コア」。
- **ダッシュボード**: Issue を優先度・状態・依存関係で一覧できるページを `tools/roadmap-view/build.py` で生成している（[ロードマップ](https://claude.ai/artifact/W2TAExeoMUzXQKTo6h4QPE)、オーナーのみ閲覧可）。
- **CI**: GitHub Actions（`android.yml` / `ios.yml` / `emulator.yml`）。
- **AI エージェント向けの作業ルール**: [CLAUDE.md](CLAUDE.md)

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [docs/01-research.md](docs/01-research.md) | ベンチマーク調査・作るべきもの・実現性評価 |
| [docs/02-architecture.md](docs/02-architecture.md) | 技術スタック・全体構成・データモデル・主要フロー |
| [docs/03-roadmap.md](docs/03-roadmap.md) | 実装手順（マイルストーンと Issue 一覧） |
| [docs/04-dev-environment.md](docs/04-dev-environment.md) | 開発・検証環境、ビルド手順、実機なしでの通し検証 |
| [docs/spikes/](docs/spikes/) | 技術検証の結果（ASR・埋め込み・電池計測手順） |
| [docs/impl/android-phone.md](docs/impl/android-phone.md) | Android スマホ側の実装（受信・ACK・オンデバイス文字起こし・モデル配信） |
| [docs/impl/phone-ui.md](docs/impl/phone-ui.md) | スマホ UI（タイムライン・検索・状態画面）の実装メモ |

## 技術スタック

- 共通ロジック / スマホ UI: Kotlin Multiplatform + Compose Multiplatform
- watchOS: SwiftUI / Wear OS: Compose for Wear OS
- オンデバイス ASR: SpeechAnalyzer (iOS) / sherpa-onnx + Zipformer ReazonSpeech (Android)
- ローカル DB: bundled SQLite + FTS5（日本語部分一致検索）
- AI 要約・チャット: 後回し（将来はクラウド LLM も可）

## リポジトリ構成

```
shared/        Kotlin Multiplatform の共通ロジック（core-model, core-protocol, core-db, ...）
composeApp/    スマホ UI（Compose Multiplatform、Android/iOS 共通）
androidApp/    Android スマホアプリ
wearApp/       Wear OS アプリ
iosApp/        iOS アプリ + watchOS アプリ（XcodeGen: project.yml）
build-logic/   Gradle の共通設定（convention plugin）
tools/harness/ 実機なしの通し検証（Watch シミュレータ + sherpa-onnx、Linux/CI）
tools/roadmap-view/ Issue ダッシュボードの生成
```

ビルド手順は [docs/04-dev-environment.md](docs/04-dev-environment.md) を参照。
