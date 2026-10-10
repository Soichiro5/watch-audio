# watch-audio

スマートウォッチを、ローカルファーストの AI ライフジャーナルにするアプリ。
Watch で一日中録音し、スマホ上で文字起こし・検索・AI による振り返りを行う。
**iOS + watchOS / Android + Wear OS** のクロスプラットフォーム対応。

ベンチマーク: [Auday — All-Day Watch Recorder](https://auday.ai/)（iOS 専用・中英のみ）

## ドキュメント

| ドキュメント | 内容 |
| --- | --- |
| [docs/01-research.md](docs/01-research.md) | ベンチマーク調査・作るべきもの・実現性評価 |
| [docs/02-architecture.md](docs/02-architecture.md) | 技術スタック・全体構成・データモデル・主要フロー |
| [docs/03-roadmap.md](docs/03-roadmap.md) | 実装手順（マイルストーンと Issue 一覧） |
| [docs/04-dev-environment.md](docs/04-dev-environment.md) | 開発・検証環境、ビルド手順 |
| [docs/impl/android-phone.md](docs/impl/android-phone.md) | Android スマホ側の実装（受信・ACK・オンデバイス文字起こし・モデル配信） |

## 技術スタック（予定）

- 共通ロジック / スマホ UI: Kotlin Multiplatform + Compose Multiplatform
- watchOS: SwiftUI / Wear OS: Compose for Wear OS
- オンデバイス ASR: SpeechAnalyzer (iOS) / sherpa-onnx (Android)
- AI: BYOK（OpenAI 互換 / Anthropic / Gemini）

## リポジトリ構成

```
shared/        Kotlin Multiplatform の共通ロジック（core-model, core-protocol, core-db, ...）
composeApp/    スマホ UI（Compose Multiplatform、Android/iOS 共通）
androidApp/    Android スマホアプリ
wearApp/       Wear OS アプリ
iosApp/        iOS アプリ + watchOS アプリ（XcodeGen: project.yml）
build-logic/   Gradle の共通設定（convention plugin）
```

ビルド手順は [docs/04-dev-environment.md](docs/04-dev-environment.md) を参照。
