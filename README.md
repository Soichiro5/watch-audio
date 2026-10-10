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

## 技術スタック（予定）

- 共通ロジック / スマホ UI: Kotlin Multiplatform + Compose Multiplatform
- watchOS: SwiftUI / Wear OS: Compose for Wear OS
- オンデバイス ASR: SpeechAnalyzer (iOS) / sherpa-onnx (Android)
- AI: BYOK（OpenAI 互換 / Anthropic / Gemini）
