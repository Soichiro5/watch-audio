# watch-audio

スマートウォッチを装着している間ずっと録音し、スマホ上で文字起こしして、データを端末内にためていくアプリ。
**データはローカルのみ**（クラウドには上げない）。
**iOS + watchOS / Android + Wear OS** のクロスプラットフォーム対応。

ベンチマーク: [Auday — All-Day Watch Recorder](https://auday.ai/)（iOS 専用・中英のみ）

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [docs/01-research.md](docs/01-research.md) | ベンチマーク調査・作るべきもの・実現性評価 |
| [docs/02-architecture.md](docs/02-architecture.md) | 技術スタック・全体構成・データモデル・主要フロー |
| [docs/03-roadmap.md](docs/03-roadmap.md) | 実装手順（マイルストーンと Issue 一覧） |
| [docs/04-dev-environment.md](docs/04-dev-environment.md) | 開発・検証環境、ビルド手順、実機なしでの通し検証 |
| [docs/spikes/](docs/spikes/) | 技術検証の結果（ASR・埋め込み・電池計測手順） |
| [docs/impl/android-phone.md](docs/impl/android-phone.md) | Android スマホ側の実装（受信・ACK・オンデバイス文字起こし・モデル配信） |

## 技術スタック（予定）

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
```

ビルド手順は [docs/04-dev-environment.md](docs/04-dev-environment.md) を参照。
