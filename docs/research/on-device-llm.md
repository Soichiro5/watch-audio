# 端末内の LLM で要約する（#39）

> 状態: **提案**（2026-10-13）。オーナーが 2026-10-13 に UC9 の AI 機能の着手を決めたのを受けて、端末の中だけで動く LLM（iPhone は Apple の Foundation Models、Android は Gemini Nano など）で要約を作る方式を調べた。§8 の表のうち「オーナー」の行を決めてもらい、§9 の作業 Issue に分けて進める。2026-10-17: §8 の 3・4 を (A) ML Kit GenAI（Gemini Nano）に決定（#205、§2.4）。
> ユースケース: [UC9 #118](https://github.com/Soichiro5/watch-audio/issues/118)、関連: [#26](https://github.com/Soichiro5/watch-audio/issues/26)（LLM の共通部分と BYOK）・[#27](https://github.com/Soichiro5/watch-audio/issues/27)（1 時間ごとの要約）・[#28](https://github.com/Soichiro5/watch-audio/issues/28)（ホーム画面）・[#29](https://github.com/Soichiro5/watch-audio/issues/29)（1 日の振り返り）・[#30](https://github.com/Soichiro5/watch-audio/issues/30)（記憶）・[#31](https://github.com/Soichiro5/watch-audio/issues/31)（チャット）

## 1. 目的と UC9 での位置づけ

ためた会話を、**データを端末の外に一切出さずに**要約で振り返れるようにする。API キーも通信も要らないので、「データはスマホにしかない」という説明（[02 §7](../architecture/02-architecture.md#7-セキュリティプライバシー)）を変えずに AI 機能を足せる。

- UC9 の要約・振り返り・記憶・チャット（#27〜#31）は、もともと BYOK（本人のキーでクラウドの LLM、#26）を前提にしていた。この文書は、その**最初の形を端末内の LLM にする**ための設計。
- #26 の `LlmProvider` のうち、端末内の LLM に要る最小限（使えるかの確認と、形の決まった出力の生成）だけを先に作る。BYOK は同じインターフェースの別の実装として後で足せる。
- 端末内の LLM は文脈（一度に読める量）が小さい（§2）。そのため最初は**短い入力を 1 つずつ処理できる要約**（#27）から始め、長い文脈が要るチャット（#31）は後にする。

## 2. 調べた結果（一次情報、2026-10-13 に閲覧）

### 2.1 Apple Foundation Models（iPhone）

| 項目 | 結果 | 出典 |
| --- | --- | --- |
| 対象の端末・OS | iOS 26 以降の Apple Intelligence 対応機種: iPhone 15 Pro / Pro Max、iPhone 16 の全機種・16e、iPhone 17 の全機種・17e・Air、iPhone 18 Pro / Pro Max・Duo（iPad は M1 以降と iPad mini（A17 Pro））。本アプリの最小 OS（iOS 26）と同じ | [Apple Intelligence](https://www.apple.com/apple-intelligence/)、[SystemLanguageModel](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel) |
| 日本語 | 対応（Apple Intelligence は Siri と端末の言語が日本語でも使える）。アプリでは `supportedLanguages` / `supportsLocale(_:)` で確かめる | [Apple Intelligence](https://www.apple.com/apple-intelligence/) の注 1、[SystemLanguageModel](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel) |
| 文脈の大きさ | **iOS 26 で 4,096 トークン**（指示・入力・出力・出力の型の説明をすべて含む）。日本語は**およそ 1 文字 1 トークン**。iOS 26.4 から `contextSize` と `tokenCount(for:)` で実行時に分かる。iOS 27 の新しいモデルの説明のコード例では `contextSize` が 8,192。長い入力は「小さく分けて別のセッションで要約し、結果をまとめる」ことが推奨されている。超えると `exceededContextWindowSize` | [TN3193](https://developer.apple.com/documentation/technotes/tn3193-managing-the-on-device-foundation-model-s-context-window)（2026-03-31 改訂）、[WWDC26「What's new in the Foundation Models framework」](https://developer.apple.com/videos/play/wwdc2026/241/) |
| モデルの版 | OS の更新で変わる（26.0〜26.3、26.4、27.0 の 3 つ）。同じプロンプトでも結果が変わるので、版ごとに評価し直す | [SystemLanguageModel](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel) |
| 形の決まった出力 | `@Generable`（Swift の型から出力の形を決める）。実行時に形を組み立てる `DynamicGenerationSchema` もある（Kotlin で決めた形を Swift で組み立てるときに使う）。型の説明もトークンを使う | [TN3193](https://developer.apple.com/documentation/technotes/tn3193-managing-the-on-device-foundation-model-s-context-window) |
| 使えるかの確認 | `SystemLanguageModel.default.availability`。使えない理由は `deviceNotEligible`（非対応の端末）・`appleIntelligenceNotEnabled`（Apple Intelligence がオフ）・`modelNotReady`（ダウンロード中など） | [UnavailableReason](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel/availability-swift.enum/unavailablereason) |
| バックグラウンド | **`BGProcessingTask` の中でも動く**。ただし OS が忙しいと `rateLimited` になりうるので、後で再試行する（WWDC26 のグループラボ）。「レート制限は**電池駆動かつバックグラウンド**のときにかかる」「電源につないでいれば制限はかからない想定」（Apple のエンジニア、2025-07 のフォーラム）。バックグラウンドではストリーミングせず `respond` で一度に受け取るよう推奨。**ロック中に動くかは文書に書かれていない**（実機で確かめる） | [WWDC26 グループラボ](https://developer.apple.com/videos/play/wwdc2026/8121/)、[フォーラム 793787](https://developer.apple.com/forums/thread/793787)・[789788](https://developer.apple.com/forums/thread/789788) |
| データが端末から出るか | `SystemLanguageModel` は「端末内のモデル」（文書の冒頭の一文）。**ただし iOS 27 から同じフレームワークに Private Cloud Compute（Apple のサーバー）のモデル `PrivateCloudComputeLanguageModel` と、他社のモデル（Anthropic・Google の Swift パッケージ）をつなぐ `LanguageModel` プロトコルが加わった**。PCC は専用の entitlement が要る。本アプリは `SystemLanguageModel` だけを使い、PCC・他社のモデルは使わない（§6.2 で CI が検査） | [SystemLanguageModel](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel)、[WWDC26 241](https://developer.apple.com/videos/play/wwdc2026/241/) |
| シミュレータ・CI | シミュレータは**ホストの Mac の Apple Intelligence** を使う（macOS 26 以降・Apple Intelligence がオン）。**仮想マシンの macOS では Apple Intelligence が使えない**（Apple のエンジニアの回答）。GitHub の macOS ランナーは仮想マシンなので、**CI では実際の生成はできない**見込み（SpeechTranscriber と同じ扱いで、理由を記録して飛ばす） | [フォーラム 790860](https://developer.apple.com/forums/thread/790860)・[815397](https://developer.apple.com/forums/thread/815397) |
| 内容による拒否 | 既定の `Guardrails.default` は危ない内容を入力・出力の両方で止める（`guardrailViolation`）。**本人の文字の変換（要約など）向けに、危ない内容を含む入力も扱える `permissiveContentTransformations`** がある。iOS 26.4・27 で誤検出を減らしたとされる。日常会話の誤検出の率は実機で測る | [Guardrails](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel/guardrails)、[WWDC26 241](https://developer.apple.com/videos/play/wwdc2026/241/) |
| 利用条件 | 「Foundation Models の利用要件」（違法・暴力・医療や法律の無監督の判断などの禁止）。要約は問題にならない | [Acceptable use requirements](https://developer.apple.com/apple-intelligence/acceptable-use-requirements-for-the-foundation-models-framework/) |
| 速さの目安 | 2024 年のモデル（iPhone 15 Pro）で、入力 1 トークンあたり約 0.6 ms、生成 約 30 トークン/秒（今のモデルの値ではない。実機で測る） | [Apple の 2024-06 の解説](https://machinelearning.apple.com/research/introducing-apple-foundation-models) |

### 2.2 Android: ML Kit GenAI（Gemini Nano / AICore）

| 項目 | 結果 | 出典 |
| --- | --- | --- |
| API の種類 | 要約（Summarization、ベータ）などの用途別の API と、自由なプロンプトの **Prompt API**（ベータ） | [ML Kit GenAI](https://developers.google.com/ml-kit/genai)（2026-10-08 更新） |
| 対応端末 | 用途別の API: Pixel 9〜11、Galaxy S25 以降・Z Fold / Flip などの上位機種と一部の中位機種。Prompt API はさらに少なく、Gemini Nano の版（nano-v2〜v4）ごとの一覧。**ブートローダーを解除した端末では動かない** | 同上、[Prompt API の始め方](https://developers.google.com/ml-kit/genai/prompt/android/get-started)（2026-09-08 更新） |
| 日本語 | **要約 API は英語・日本語・韓国語**。Prompt API は文書に対応言語の記載がない（実機で確かめるしかない） | [要約 API](https://developers.google.com/ml-kit/genai/summarization/android)（2026-10-08 更新） |
| 入力・出力 | どちらも**入力 4,000 トークン未満**。要約 API の出力は**箇条書き 1〜3 個だけ**で、引用（どの発話か）は出せない。Prompt API は形の決まった出力（アルファ、KSP と追加の依存が要る） | 同上 |
| 状態の確認 | `checkFeatureStatus()` / `checkStatus()` が `UNAVAILABLE` / `DOWNLOADABLE` / `DOWNLOADING` / `AVAILABLE` を返す。`DOWNLOADABLE` なら `downloadFeature` | 同上 |
| モデルのダウンロード | **AICore（OS の機能）が行う**。AICore 自体はインターネットに直接つながず、Private Compute Services（オープンソースの付属アプリ）を通す。アプリのプロセスの通信ではない | [Gemini Nano](https://developer.android.com/ai/gemini-nano)（2026-10-08 更新） |
| バックグラウンド | **「アプリが一番前に出ているときだけ」推論できる。前景サービスからも不可で `BACKGROUND_USE_BLOCKED`**。短時間の連続で `BUSY`、1 日などの上限で `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` | [ML Kit GenAI](https://developers.google.com/ml-kit/genai) |
| **アプリのプロセスからの送信** | **ある**。`genai-prompt:1.0.0-beta4` と `genai-summarization:1.0.0-beta1` の依存（POM）に `com.google.mlkit:common`・`com.google.android.datatransport:transport-backend-cct`（INTERNET 権限を足す）・`firebase-encoders` が入り、`genai-prompt` のクラスが `CCTDestination`（Google へのログの送信先）を直接使っている。ML Kit のデータ開示は、端末情報・アプリ名と版・処理時間・API の設定に加え、**GenAI では利用者・端末などの識別子と設定した言語**を「診断と利用状況の分析」に使うと書く。追加規約も「Google の指標データの処理を利用者に知らせること」を求めている。**#184 で Google のコードスキャナを採らなかったのと同じ理由に当たる**。依存から送信部を外すと `CCTDestination` のクラスが無くなり動かないので、外して使うこともできない | Google Maven の POM・AAR（2026-10-13 に確認）、[ML Kit のデータ開示](https://developers.google.com/ml-kit/android-data-disclosure)、[ML Kit GenAI 追加規約](https://developers.google.com/ml-kit/genai-terms)（2025-05-14） |
| エミュレータ・CI | 公式の手順はない（AICore のある対応端末が要る）。CI では動かせない | [Google AI のフォーラム](https://discuss.ai.google.dev/t/is-there-any-official-emulator-simulator-workflow-for-gemini-nano-aicore-prompt-api-testing-on-android/147148) |
| 利用条件 | 追加規約: 生成 AI の禁止用途ポリシーに従う、安全のための仕組みを迂回しない、医療用途の禁止、「プレビュー」「試験運用」の API は本番で使えない、競合するモデルの開発の禁止 | [ML Kit GenAI 追加規約](https://developers.google.com/ml-kit/genai-terms) |
| 旧来の入口 | Google AI Edge SDK（`com.google.ai.edge.aicore`、試験運用）は、テストプログラムへの登録が要り、Google は Prompt API への移行を勧めている。配布するアプリでは使えない | [試験運用版のアクセス](https://developer.android.google.cn/ai/gemini-nano/experimental?hl=ja) |

### 2.3 自分でモデルを持つ方式（非対応端末・Android の代わり）

| 候補 | 大きさ・速さ | 日本語 | 保守と通信 |
| --- | --- | --- | --- |
| **LiteRT-LM + Gemma 4 E2B**（Google の端末内 LLM の実行ライブラリ。MediaPipe の LLM Inference の後継で、MediaPipe 側は保守のみ） | モデル 約 2.6 GB（`gemma-4-E2B-it.litertlm`）。Galaxy S26 Ultra で入力 557（CPU）/ 3,808（GPU）トークン/秒、生成 47 / 52 トークン/秒、メモリ 0.7〜1.7 GB。iPhone 17 Pro の GPU で生成 57。文脈は最大 32K（LiteRT-LM）。中位機種の値は公開されていない | 140 以上の言語で学習（日本語の要約の品質の公開データは見つからなかった → §7 で測る） | **Maven の公式の成果物**（`com.google.ai.edge.litertlm:litertlm-android:0.18.0`、Apache 2.0。依存は gson・kotlin-reflect・coroutines だけ）。AAR を調べると、Java 側に `java.net` の利用がなく、ネイティブライブラリも socket・connect・getaddrinfo を取り込んでいない（2026-10-13 に確認）。**x86_64 のライブラリと JVM 版（`litertlm-jvm`）もある**ので、エミュレータと Linux の CI・ハーネスで同じ経路を動かせる。モデルは Hugging Face の `litert-community/gemma-4-E2B-it-litert-lm`（Apache 2.0、利用申請なしで取得可）。自分で fork・ビルドするものはない |
| LiteRT-LM + 小さいモデル（Qwen3-0.6B / 1.7B、Gemma 3 1B、gemma-3-270m） | 0.3〜1.5 GB 程度 | 1B 前後では日本語の要約は弱いと見込む（公開データなし） | 同上。CI で経路を確かめる用には使える |
| llama.cpp + GGUF（Qwen など） | Q4 で 1〜3 GB | モデルしだい | Android・iOS 向けの公式のバイナリ配布がなく、JNI のビルドを自分で持つことになる（オーナーの方針「ライブラリを fork・保守しない」に合わない） |
| iOS 27 の `LanguageModel` プロトコル + MLX（Apple が公開した実装） | モデルしだい | モデルしだい | Apple Intelligence 非対応の iPhone（iPhone 15 以前・RAM 6 GB 以下）では大きいモデルが載らない。iOS の非対応端末向けは当面作らない（§8） |

- **Android で Gemini Nano を使える端末は限られ（上位機種中心）、さらに送信の問題で ML Kit を使えない**ので、Android は「自分でモデルを持つ」方式（LiteRT-LM + Gemma 4 E2B）を本命の候補にする。この方式は自分のプロセスの計算なので、**バックグラウンド（充電中の WorkManager）でも動く**。代わりに、約 2.6 GB のダウンロード（Wi-Fi のみ、本人が選んだときだけ）と、メモリ（RAM 8 GB 以上を目安。実機で決める）が要る。

### 2.4 Android の実装で使ったもの（#205、一次情報を 2026-10-17 に再確認）

§8 の 3 を (A) に決めたあと、実装の前に読み直した結果。

| 項目 | 結果 | 出典（2026-10-17 に閲覧） |
| --- | --- | --- |
| 使う API と版 | **Prompt API**（`com.google.mlkit:genai-prompt:1.0.0-beta4`、版を固定）。Google Maven の最新は beta4（`genai-common` も beta4、`genai-summarization` は beta1） | [Prompt API の始め方](https://developers.google.com/ml-kit/genai/prompt/android/get-started)（2026-09-08 更新）、Google Maven の `maven-metadata.xml` |
| 要約 API を使わない理由 | 要約 API は日本語に対応（英語・日本語・韓国語）するが、出力は**箇条書き 1〜3 個だけ**で、どの発話にもとづくか（行番号）を返せない。共通の設計（§4.1・§4.5）は要点ごとに根拠の行を引くので、形を指定できる Prompt API を使う | [要約 API](https://developers.google.com/ml-kit/genai/summarization/android)（2026-10-15 更新） |
| 日本語 | Prompt API の文書に対応言語の記載は**ない**（概要のページも「言語は端末の設定とダウンロード済みのモデルしだい」）。そのまま使い、日本語の質は実機（#61）と評価（#197）で確かめる | [ML Kit GenAI](https://developers.google.com/ml-kit/genai)（2026-10-15 更新）、[Prompt API](https://developers.google.com/ml-kit/genai/prompt/android)（2026-07-15 更新） |
| 形の決まった出力 | 構造化出力は**アルファ**で、KSP で `@Generable` のクラスから作る方式だけ（実行時のスキーマは渡せない）。使わず、共通の `OutputSchema` から作った例と項目の説明をプロンプトに書き（`LlmRequest.toSingleText()`）、共通のパーサで検査する（形が違えば 1 回だけ作り直す） | [構造化出力](https://developers.google.com/ml-kit/genai/prompt/android/structured-output)（2026-07-21 更新、「alpha」） |
| システム指示 | ベータで、Gemini Nano v3 以上が要る。使わず、指示も 1 つの文にまとめる（どの版でも同じ） | [システム指示](https://developers.google.com/ml-kit/genai/prompt/android/system-instructions)（2026-07-15 更新） |
| 文脈とトークン | 「入力は 4,000 トークン未満」。`getTokenLimit()` と `countTokens()` がある（AAR の公開 API で確認）。文脈は `getTokenLimit()`（取れなければ 4,000）、数えるのは `countTokens()` | Prompt API の始め方、AAR（`GenerativeModel`） |
| モデルの版 | `ModelConfig` の既定は「安定版（`STABLE`）・`FULL`」。プレビュー（`ModelReleaseStage.PREVIEW`）は使わない（追加規約が「Preview」の本番利用を禁じる） | [ModelConfig](https://developers.google.com/android/reference/com/google/mlkit/genai/prompt/ModelConfig) |
| エラー | `GenAiException.ErrorCode`: `BUSY`(9)・`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`(27)・`BACKGROUND_USE_BLOCKED`(30)・`REQUEST_TOO_LARGE`(12)・方針の検査で止まった `REQUEST_PROCESSING_ERROR`(4)・`RESPONSE_GENERATION_ERROR`(15)・`RESPONSE_PROCESSING_ERROR`(11)・`AICORE_INCOMPATIBLE`(-101)・`NEEDS_SYSTEM_UPDATE`(604) など | [ErrorCode](https://developers.google.com/android/reference/com/google/mlkit/genai/common/GenAiException.ErrorCode) |
| 利用規約 | 追加規約（2025-05-14 改訂）は「API クライアントを本番で使ってよい（Google はレート制限をかけうる）」としたうえで、「Preview」「Experimental Access」**などの名前の付いたサービス**の本番利用を禁じる。Prompt API はサイト上で「Beta」（規約の本文に「beta」の語はない）、構造化出力は「Alpha」、プレフィックスキャッシュは「Experimental」。**ベータが「などの名前」に当たるかは規約の文面からは決まらない**ので、配布の判断はオーナーに残す。実装は Experimental・Alpha の部分とプレビューのモデルを使わない（`tools/privacy-check` で検査）。ほかに: 利用者に Google の指標データの処理を知らせる（ポリシー第 5 条）、安全の仕組みを迂回しない、医療用途の禁止 | [ML Kit GenAI 追加規約](https://developers.google.com/ml-kit/genai-terms)（ページの更新 2025-10-29） |
| 送るもの | ML Kit のデータ開示（2026-07-15 更新）: 端末の情報・アプリの名前と版・処理時間・API の設定・入出力の大きさ・エラー、GenAI では利用者・端末などの識別子と設定した言語。「診断と利用状況の分析」に使い、HTTPS で送り、第三者には渡さない | [ML Kit のデータ開示](https://developers.google.com/ml-kit/android-data-disclosure) |

実装の中身（状態の対応、エラーの対応、前面だけの制約）は [android-phone.md §4.7](../platforms/android-phone.md#47-1-時間ごとの要約gemini-nano205)。

## 3. 最初に作るもの

**1 時間ごとの要約（#27）を、端末内の LLM で作る。** 1 時間ごとに「要約（2〜3 文）」と「要点（最大 3 件、それぞれタイトル・本文・根拠の発話）」を作り、日別タイムラインの各時間帯に表示する。要点から根拠の発話に飛んで、その場面を再生できる。

最初の起動のしかたは 2 案を比べた。

| 案 | 内容 | 良い点 | 弱い点 |
| --- | --- | --- | --- |
| A. 充電中に自動 | 1 時間が閉じて文字起こしがそろったら、充電中のバックグラウンド処理（iOS の `BGProcessingTask`、Android の WorkManager）で順に作る | 何もしなくても翌朝にはそろっている（#27 の完了条件） | 最初から OS の制約（レート制限・ロック中・メモリ）と電池の影響をすべて相手にする。ML Kit の場合は Android で不可能 |
| **B. 「この日を要約」ボタン（推奨: 最初はこちら）** | 日別タイムラインの上のボタン。押すと、その日のまだ要約していない時間帯を、画面を開いている間に順に作る（進み具合を表示。閉じたら途中で止め、次に押したときに続きから） | 本人が始めるので分かりやすく、バックグラウンドの制約を最初は避けられる。両 OS で同じ形にできる。評価（§7）の結果を見る前に自動で電池を使わない | 押さないとできない |

- 推奨: **B から始め、データの形（時間帯ごとの要約）は A と同じにしておく**。評価と実機の確認のあとで、A（設定「充電中に自動で要約する」）を足す。A を足すときも、作る処理は B と同じ共通のコード。
- 「1 日の振り返り」（#29）は、時間帯ごとの要約をまとめるだけなので、そのあとに同じ仕組みで足せる（入力が小さい）。

## 4. 構成

### 4.1 共通のインターフェース（`shared/pipeline`、commonMain）

#26 の `LlmProvider` のうち、端末内の LLM に要る部分だけを先に作る。BYOK の実装（#26）は後で同じインターフェースに足す。

```kotlin
/** A language model the app may use; for now only on-device ones (#39). */
interface LlmProvider {
    /** Stable id stored with every result, e.g. "apple-fm", "litertlm-gemma4-e2b". */
    val id: String
    suspend fun availability(): LlmAvailability
    /** Context size in tokens (prompt + output + schema), from the platform when it can tell. */
    suspend fun contextTokens(): Int
    /** Token count of [text], or null where the platform cannot count (then 1 char = 1 token). */
    suspend fun countTokens(text: String): Int?
    /** One stateless generation; [schema] asks for JSON of that shape. */
    suspend fun generate(request: LlmRequest): LlmResult
}

sealed interface LlmAvailability {
    data class Available(val modelVersion: String) : LlmAvailability
    data class NeedsDownload(val bytes: Long) : LlmAvailability        // Android: our model file
    data class Downloading(val fraction: Float?) : LlmAvailability      // iOS modelNotReady / our download
    data object TurnedOff : LlmAvailability                             // Apple Intelligence is off
    data object LanguageNotSupported : LlmAvailability                  // supportsLocale(ja) == false
    data class Unsupported(val reason: String) : LlmAvailability        // device not eligible, too little RAM
}

data class LlmRequest(val instructions: String, val prompt: String, val schema: OutputSchema, val maxOutputTokens: Int)

sealed interface LlmResult {
    data class Ok(val json: String) : LlmResult
    data object Refused : LlmResult              // guardrail
    data object ContextExceeded : LlmResult
    data class RateLimited(val retryAfterMs: Long?) : LlmResult
    data class Failed(val type: String) : LlmResult
}
```

- `OutputSchema` は共通のコードで決める出力の形（文字列・整数・配列・最大件数）。iOS は Swift でこれを `DynamicGenerationSchema` に組み立てて guided generation を使い、`GeneratedContent` を JSON にして返す。Android（LiteRT-LM）は形をプロンプトに書いて JSON を求め、共通のパーサで検査し、壊れていれば 1 回だけ作り直す。
- プロンプトに載せられるのは文字起こしの本文と時刻・印（Mark）だけで、音声は型の上で渡せない（#26 の `PrivacyFilter` の考え方。端末内でも同じ型にしておけば、BYOK のときにそのまま使える）。
- テスト用に、決まった応答・拒否・文脈超過・レート制限を返す `FakeLlmProvider`（commonTest）を作る。
- iOS の実装は Swift（`FoundationModels`）で書き、Kotlin のインターフェースとして渡す（文字起こしの `IosSpeechTranscriber` と同じ形、[ios-watchos §6](../platforms/ios-watchos.md#6-kotlin--swift-ブリッジ)）。Android は Kotlin で LiteRT-LM を呼ぶ。

### 4.2 小さい文脈に合わせた分け方

量の見積もり: 装着 16 時間で 1 日 約 8,600 セグメント・1 セグメント 平均約 12 文字（[02 §9.3](../architecture/02-architecture.md#93-データ量の見積もり)）なので、**1 時間あたり平均 約 6,500 文字、会話が続く時間帯は約 20,000 文字**。日本語はおよそ 1 文字 1 トークンなので、4,096 トークンの文脈には 1 時間分が入らない。

1. **行にまとめる**（`PromptLines`）: 1 セグメントは 2 秒・12 文字前後と短く、1 つずつ番号を付けると番号だけでトークンを使う。間が短い（2 秒未満）連続するセグメントを最大 60 文字程度までつないで 1 行にし、行ごとに短い番号を振る（`[12] 会話の文`）。番号はプロンプトの中だけのもので、行が指すセグメントの範囲（最初のセグメントの ID と件数）は共通のコードが持つ。**DB の ID はプロンプトに載せない**（トークンの節約と、LLM が ID を作り出す余地をなくすため）。
2. **予算**: 1 回の入力の上限 = `contextTokens()` − 指示 − 出力の形 − 出力の上限（約 500）。`countTokens` で数え、数えられないときは 1 文字 = 1 トークンで安全側に見積もる。4,096 なら 1 回 約 2,800 トークン（約 75 行・約 2,700 文字）。
3. **分けて要約してまとめる**（TN3193 の推奨と同じ）: 1 時間分の行を予算に収まるまとまりに分け、それぞれ新しいセッションで「要約 1〜2 文 + 要点の候補（最大 3 件、根拠の行番号付き）」を作る。続いて 1 回で、まとまりごとの結果から「1 時間の要約 2〜3 文 + 要点 最大 3 件」を作る（要点は候補の中から選ぶので、根拠は候補の行番号を引き継ぐ）。1 時間 = 平均 3〜4 回 + まとめ 1 回、会話が続く時間帯で 8〜9 回。
4. **印（Mark）の優先**: 本人が Mark を押した時刻の前後 2 分の行に `★` を付け、「★ の行を優先して要点にする」と指示する（#27）。
5. **発話が少ない時間帯は LLM を呼ばない**: 文字数が少ない（例 200 文字未満）時間帯は「発話が少ない」として要約を作らない。
6. **情報量のスコア**（#28 の時間のバーの高さ）: LLM に頼らず、その時間帯の発話の時間・文字数から共通のコードで計算する。要約がまだない時間帯や LLM が使えない端末でもバーを出せる。

時間の目安（推定、実機で測る）: iPhone（2024 年のモデルの値）で 1 回 = 入力 2,800 トークン × 0.6 ms + 出力 300 トークン ÷ 30/秒 ≒ 12 秒、1 時間 約 1 分、16 時間で 約 15 分。Android の LiteRT-LM は上位機種の GPU で 1 時間 30 秒前後、中位機種の CPU ではその数倍。

### 4.3 保存（追記のみのマイグレーション）

`shared/db` の `Schema.kt` の次の番号（今は 10 の次）に追記する。[02 §5.2](../architecture/02-architecture.md#52-将来p2のテーブル構想未実装) の `hour_review` / `highlight` の構想を、次の形で具体化する。

```sql
CREATE TABLE hour_summary (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    hour_start     INTEGER NOT NULL,   -- local hour start, epoch ms
    time_zone      TEXT    NOT NULL,
    state          TEXT    NOT NULL,   -- DONE / LITTLE_SPEECH / REFUSED / FAILED
    summary        TEXT,
    provider       TEXT    NOT NULL,   -- LlmProvider.id
    model_version  TEXT,
    prompt_version INTEGER NOT NULL,
    source_hash    TEXT    NOT NULL,   -- hash of the input lines (segment ids + text)
    stale          INTEGER NOT NULL DEFAULT 0,
    attempts       INTEGER NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_hour_summary_hour ON hour_summary(hour_start);

CREATE TABLE summary_highlight (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    summary_id INTEGER NOT NULL REFERENCES hour_summary(id) ON DELETE CASCADE,
    ord        INTEGER NOT NULL,
    title      TEXT    NOT NULL,
    body       TEXT    NOT NULL
);

CREATE TABLE highlight_citation (
    highlight_id INTEGER NOT NULL REFERENCES summary_highlight(id) ON DELETE CASCADE,
    segment_id   INTEGER NOT NULL REFERENCES transcript_segment(id) ON DELETE CASCADE,
    PRIMARY KEY (highlight_id, segment_id)
);
```

- 1 時間に 1 行。作り直しは同じ行を置き換える（要点と引用は消して入れ直す）。
- **入力が変わったら古いと印を付ける**: チャンク境界の再認識や文字起こしのやり直しでセグメントが入れ替わると、引用が消えたり要約が合わなくなったりする。`transcript_segment` の追加・削除のトリガで、その時間帯の `hour_summary.stale = 1` にする（埋め込みの `segment_passage_ai` / `_ad` と同じ考え方、[02 §6.3](../architecture/02-architecture.md#埋め込み意味検索24)）。画面は古い要約を「更新待ち」と出し、次に作るときに作り直す。
- 「この日のデータを削除」「すべてのデータを削除」は要約も消す（`StorageQueries` に加える、[02 §7](../architecture/02-architecture.md#7-セキュリティプライバシー) の「削除」）。
- 暗号化バックアップには入れない（作り直せる派生データ。埋め込みと同じ）。データの書き出し（#155）には入れる（本人が読める形で持ち出せるように）。→ §8 で確認。

### 4.4 いつ作るか

- **最初（§3 の B）**: 画面の「この日を要約」で、`SummaryRun`（共通のコード）を画面のスコープで動かす。時間帯ごとに 1 つずつ作って保存し、画面を閉じたら止める（作り終えた時間帯は残る）。文字起こしが終わっていない時間帯は「文字起こし待ち」として飛ばす。
- **次（§3 の A、設定でオン）**: 文字起こしの実行（`TranscriptionRun`）の後、埋め込みの段（`EmbeddingStep`）の後に要約の段を置く。条件は**充電中だけ**（電池駆動では作らない。LLM は文字起こしより重い）・発熱していない・文字起こし待ちのチャンクがない・終わった時間帯（現在の時刻を含む時間帯は作らない）。時間帯ごとに、新しいチャンクの到着・OS の停止・方針の変化を確かめて止まる（`EmbeddingStep` と同じ形）。
  - iOS: 既存の `BGProcessingTask`（`requiresExternalPower = true`）の中。`rateLimited` ならその回はやめ、次の実行で続ける。ロック中に動かなければ（実機で確かめる）、前面にいる間だけ作る。
  - Android（LiteRT-LM の場合）: 既存の充電中の WorkManager のジョブの中。ML Kit（Gemini Nano）を選んだ場合は前景以外で動かないので、A は作れない（画面を開いている間だけになる）。
- 失敗は要約の段の中で止め、文字起こし・検索には影響させない。同じ時間帯が続けて失敗したら（3 回）`FAILED` にして、本人が押したときだけ作り直す。

### 4.5 引用の検査

- LLM が返した行番号のうち、そのプロンプトに無い番号・その時間帯の外を指すものは捨てる。根拠が 1 つも残らない要点は捨てる。
- 保存の直前に、入力に使ったセグメントが今もあるか（`source_hash` が今の DB と同じか）を確かめ、違えば保存せず次に作り直す（埋め込みの保存前の確認と同じ）。
- 要点の本文が根拠の行と無関係な場合（作り話）は機械的には見分けにくいので、§7 の評価で率を測る。画面では、要点から必ず根拠の発話（と音声）に飛べるようにして、本人が確かめられるようにする。

### 4.6 画面の状態

日別タイムライン（[phone-ui](../platforms/phone-ui.md)）の時間帯ごとと、「この日を要約」ボタンの周りに出す。

| 状態 | 表示 |
| --- | --- |
| 非対応の端末 | 「この端末では、端末内の AI による要約を使えません」（ボタンは出さない。他の機能はそのまま） |
| Apple Intelligence がオフ・言語が合わない | 「設定で Apple Intelligence をオンにすると使えます」（言語が合わないときはその旨） |
| モデルの準備中 | 「端末内の AI のモデルを準備しています」（iOS の `modelNotReady`、Android の AICore の `DOWNLOADING`）。Android でモデルをダウンロードできる状態（`DOWNLOADABLE`）では「「この日を要約」を押すと、OS がモデルをダウンロードしてから要約します」とボタンを出し、押したら進み具合を出してから要約に進む（#205）。(B) を足すなら「要約のモデル（約 2.6 GB）をダウンロード」（Wi-Fi のみ） |
| 要約待ち | 「N 時間分を要約できます」 |
| 作成中 | 「要約しています（3/9 時間）」。時間帯ごとに出来たものから表示 |
| 発話が少ない | 「会話が少ないため要約していません」 |
| 拒否された | 「この時間帯は要約できませんでした」（guardrail。再試行はしない） |
| 失敗・更新待ち | 「要約に失敗しました（もう一度）」「会話が更新されたので、要約を作り直します」 |

## 5. 守ること（プライバシー）

**基準（2026-10-13 にオーナーが確認）**: 絶対に端末の外に出さないのは、**音声・文字起こし・要約などの個人的な内容**。内容を含まない診断・利用状況のログ（SDK が送る処理時間・設定・識別子など）は、それだけで不可とはしない。採るときは `tools/privacy-check` の許可リストに理由を書き、プライバシーポリシーとストアの申告に書く。

### 5.1 端末から出ないことの確認

- **iOS**: `SystemLanguageModel`（端末内のモデル）だけを使う。`PrivateCloudComputeLanguageModel`・他社のモデルの `LanguageModel` 実装・`LanguageModelFeedbackAttachment`（Apple へのフィードバック用の添付）は使わない。PCC に要る entitlement も付けない。モデルのダウンロードは OS（Apple Intelligence）が行い、アプリは URL を持たない（音声認識のモデルと同じ扱い）。
- **Android（LiteRT-LM）**: 推論は自分のプロセスの計算だけ。通信はモデルのダウンロード（既存の `ModelDownloader`、URL はコミット固定、SHA-256 で検証、Wi-Fi のみ）だけで、既存の「通信はモデルのダウンロードだけ」に収まる。
- **Android（ML Kit。2026-10-17 に採用、#205）**: 推論は AICore の中で端末内に閉じ、文字起こし・要約は送られない。**アプリのプロセスから Google へ利用状況のログ（端末情報・アプリの版・処理時間・API の設定・識別子・言語。内容は含まない）が送られる**（§2.2）。上の基準では内容を含まないので採れる。採用にあたり、privacy-check の許可リスト、プライバシーポリシー、ストアの申告（Google の SDK による「アプリの情報とパフォーマンス」「端末などの ID」の収集）を更新した（#205）。

### 5.2 `tools/privacy-check` の扱い

- **iOS**: 出荷されるソースで `FoundationModels` を `import` してよいのは 1 つのファイル（例 `apps/ios/App/Llm/OnDeviceLanguageModel.swift`）だけにし、`PrivateCloudComputeLanguageModel`・`LanguageModelFeedbackAttachment` を `GUARDED_APIS` に加えて、どのファイルでも不可にする。entitlements の許可リストは変えない（PCC の entitlement が足されると失敗する）。Swift パッケージ（他社のモデルのパッケージ）は今の `IOS_SWIFT_PACKAGES` の許可リストで止まる。
- **Android**: ML Kit を採った（#205）。`com.google.mlkit.genai` は `apps/android/.../llm/MlKitGeminiNano.kt` だけ（`SINGLE_FILE_FRAMEWORKS`。`Generation.getClient` と `ModelReleaseStage.STABLE` を要求）、ほかの ML Kit の API・`ModelReleaseStage.PREVIEW`・プレフィックスキャッシュと構造化出力はどこでも不可、依存は `com.google.mlkit:genai-*`・`common`、`datatransport:transport-*`、`firebase-encoders*`・`firebase-components` を理由つきで許可、マージ後のマニフェストで増える権限は AICore への接続だけ（`library_permissions`）。以下は採用前の案: ML Kit を採るなら、`ALLOWED_RESOLVED_DEPENDENCIES` に `com.google.mlkit:genai-*`・`com.google.mlkit:common`・`com.google.android.datatransport`・`firebase-encoders` を「内容を含まない利用状況のログだけを送る」と理由を書いて加え、マージ後のマニフェストに増える権限（INTERNET など）を `library_permissions` に書く。ML Kit を採らない場合は、これらを `DENIED_DEPENDENCIES` に加える。LiteRT-LM を採るなら `ALLOWED_RESOLVED_DEPENDENCIES` に `com.google.ai.edge.litertlm:litertlm-android`（端末内の LLM、通信なし）と `com.google.code.gson:gson` を加え、モデルの URL（コミット固定）を `ALLOWED_URL_PREFIXES` に加える。マージ後のマニフェストに INTERNET 以外の権限が増えないことは今の検査で確かめられる。

### 5.3 プライバシーポリシーの文言（案）

- 第 4 条（端末の中での処理）に追加: 「要約（その日の会話を 1 時間ごとにまとめる機能）も端末の中で作ります。iPhone では Apple Intelligence の端末内のモデル（Apple の Foundation Models）を使い、Apple のサーバーで処理する仕組み（Private Cloud Compute）は使いません。Android では、ダウンロードした要約のモデル（Google の Gemma）を本アプリの中で動かします。文字起こしや要約を外部に送ることはありません。」
- 第 5 条（通信）に追加（Android で LiteRT-LM を採る場合）: 「要約を使うと選んだときに限り、要約のモデル（約 2.6 GB）を Hugging Face から Wi-Fi 接続時にダウンロードします。」iPhone は「Apple Intelligence のモデルは iOS が管理し、本アプリはダウンロードしません」。
- ストアの申告（[store-data-declarations](../legal/store-data-declarations.md)）: iPhone と、Android で LiteRT-LM を採る場合は「データの収集なし」のまま。Android で ML Kit を採る場合は、Google の SDK が集める「アプリの情報とパフォーマンス（診断）」「端末などの ID」を申告し、ポリシーにも Google の利用状況のログを書く。

### 5.4 CLAUDE.md の文言

§5 の基準と §8 の決定を受けて、lead が CLAUDE.md の「Local only」と「AI summaries/chat are out of scope」の行を直した（この文書と同じ PR）。

## 6. テストのしかた

### 6.1 CI で確かめられること

| 対象 | 方法 |
| --- | --- |
| 行のまとめ方・予算・分け方・まとめの手順・印の優先・発話が少ない時間帯の扱い・情報量のスコア | 共通のコードの単体テスト（`commonTest` / `jvmTest`）。偽のトークン数（1 文字 = 1 トークンなど）で、どの文脈の大きさ（4,096 / 8,192 / 32K）でも予算を超えないこと |
| JSON の検査・引用の検査・壊れた出力の作り直し・拒否・文脈超過・レート制限 | `FakeLlmProvider` で決まった応答を返す単体テスト（存在しない行番号が捨てられる、根拠のない要点が捨てられる、拒否は再試行しない、など） |
| 保存・古い印・削除 | マイグレーションのテスト（既存の DB からの更新）、トリガ（セグメントの入れ替えで `stale`）、日・全削除で要約が消えること（`sqliteTest`、JVM・Android・iOS シミュレータ） |
| 画面の状態 | ViewModel のテスト（§4.6 の各状態、`FakeLlmProvider` の可用性ごと） |
| iOS の Swift の部分 | ビルドと、モデルを使わない部分（`OutputSchema` → `DynamicGenerationSchema` の組み立て、エラーの対応づけ）の XCTest。実際の生成は、GitHub の macOS ランナー（仮想マシン）では Apple Intelligence が使えない見込みなので、`availability` が使えないなら理由をログに残して飛ばす（SpeechTranscriber のテストと同じ扱い） |
| Android の LiteRT-LM の経路 | 小さいモデル（gemma-3-270m や Qwen3-0.6B の `.litertlm`）で、読み込み → 生成 → JSON の検査までを JVM（`litertlm-jvm`、ハーネス）とエミュレータ（x86_64 のライブラリ）で動かす。品質は見ない |
| 送信がないこと | `tools/privacy-check`（§5.2）。Android のエミュレータのテストで、要約の間に外向きの接続がないことも見る（エージェント連携の no-egress のガードと同じ考え） |

### 6.2 実機でしか確かめられないこと（#60・#61 に追加する）

- **#60（iPhone）**: Apple Intelligence 対応の iPhone で、機内モードのまま「この日を要約」で要約ができる。非対応の iPhone では「使えません」と出て他の機能に影響しない。Apple Intelligence をオフにしたとき・モデルの準備中の表示。（自動を入れたら）充電中・ロック中の `BGProcessingTask` で要約が進むか、`rateLimited` の頻度。1 日分にかかる時間・発熱。実際の会話での拒否（guardrail）の率。
- **#61（Android、Gemini Nano、#205）**: 対応端末で機内モードのまま「この日を要約」で要約ができる。日本語の要約の質（Prompt API の日本語は文書に記載がない）。1 時間あたりの時間・電池・発熱。要約中にアプリを裏に回すと止まり、作り終えた時間帯は残る（`BACKGROUND_USE_BLOCKED` で失敗の時間帯が残らない）。モデルが未ダウンロードの対応端末で、押したときだけダウンロードが始まり進み具合が出る。非対応端末で「この端末では使えません」と出てほかの機能に影響しない。`BUSY`・1 日の上限に当たる頻度。
- （(B) を足すとき）モデルのダウンロード（約 2.6 GB、Wi-Fi のみ、途中から再開）、中位機種での 1 時間あたりの時間・メモリ・発熱、RAM が足りない端末の判定、機内モードでの生成。

## 7. 日本語の品質の評価

評価のしくみは #197 で作った（評価セット・採点・ハーネスのコマンド・Mac で Apple のモデルに答えさせるテスト）。実際のモデルでの数字は、モデルが動く環境で §7.4 の手順を実行して Issue に貼る。

### 7.1 評価セット

- 素材: 検証用音声セットの会話台本 16 本（[`tools/test-audio/corpus/`](../../tools/test-audio/corpus/)、合成の会話で個人の情報は含まない。約 9,100 字）と、ハーネスの `evaluate` が出す認識結果（作業ディレクトリの `<録音>/segments.tsv`。clean・wrist・cafe の条件）。約 1 時間の `day` 録音も、同じく会話ごとに分けて入力にできる（[test-audio.md](../dev/test-audio.md)）。
- 正解: [`tools/harness/testdata/summary-eval-facts.json`](../../tools/harness/testdata/summary-eval-facts.json)。会話ごとに次を持つ（16 会話・事実 84 個・作り話の候補 32 個）。
  - `facts`: 要約に入っているべき事実を 4〜6 個。`id`、`text`（事実の文）、`lines`（根拠の台本の行。`#` の見出しと空行を除いた 1 から数えた行番号で、`semantic-eval-queries.json` と同じ数え方）、`keys`（自動判定のキーワード）。
  - `mustNot`: 要約が言ってはいけないこと（ありそうな作り話: 違う日付・件数・場所・支払い方法など）を 2 個。`id`、`text`、`keys`。
  - `keys` は「グループのリスト、グループは言い換えのリスト」。**すべてのグループで、どれか 1 つの言い換えが文中にある**と一致とする（例: 週次定例のサーバー費用 `[["サーバー"], ["12万"]]`）。
- 台本に忠実なことは単体テスト（`SummaryEvalSetTest`、CI）で確かめる: 事実のキーワードがその根拠の行の文に一致すること、`mustNot` が会話のどこにも一致しないこと、16 会話すべてに 3〜6 個の事実があること。

### 7.2 採点

採点は決まった手順の関数（[`SummaryScoring.kt`](../../tools/harness/src/main/kotlin/app/watchaudio/harness/SummaryScoring.kt)、LLM による採点はしない）。文はすべて CER と同じ正規化（`CerText.normalize`: NFKC・小文字・句読点と空白と記号の除去・漢数字を算用数字に）をしてから比べるので、「十二万円」「12万円」「120,000円」は同じになる。

| 指標 | 測り方（自動の部分） | 目安（仮。最初の評価で見直す） |
| --- | --- | --- |
| 事実の再現率 | 要約の文（`summary` と各要点の `title`・`body`）が事実の `keys` に一致した割合。外れたもの・言い換えで取りこぼしたものは `summaries.md` を見て人が確認 | 台本の文で 70% 以上、wrist の認識結果で 50% 以上 |
| 作り話 | 要約の文が `mustNot` の `keys` に一致した数。リストにない作り話は人が確認 | 要約 10 件あたり 1 件以下 |
| 引用の正しさ | 要点ごとに、その `title`・`body` が一致する事実を探す。あれば、要点が引用した行（プロンプトの行 → セグメント → 台本の行）のどれかが、その事実の根拠の行から **±1 行以内**なら正しい。一致する事実がない要点は「判定外」として数を出す | 80% 以上 |
| 形の成功率 | モデルが返した答え（`Ok`）のうち、出力の形（`SummaryPrompts.SCHEMA`）にそのまま合う割合。答えの中の JSON（前後の文やコードの囲みはアプリと同じく許す）を厳密に解析し、すべての項目があり型が合い（行番号は数値）、要点が 3 件以内 | 95% 以上 |
| 拒否の率 | モデルに送った会話のうち、`HourOutcome.Refused`（guardrail）になった割合 | 日常会話で 0〜数 % |
| 日本語の自然さ | 1〜5 の 5 段階（人が `summaries.md` を読む） | 平均 4 以上 |
| 時間・トークン | 会話ごとの呼び出し回数・プロンプトの文字数（日本語はほぼトークン数）・時間（呼び出しの合計）。中央値・90 パーセンタイル・最大 | §4.2 の見積もりと比べる |

- 1 会話を「1 時間」として、アプリと同じ経路で要約する: セグメント → `PromptLines`（行にまとめる）→ `HourSummarizer`（入らなければ分けてまとめる）。文字数が `LITTLE_SPEECH_CHARS`（200）未満なら、アプリと同じく呼ばずに「発話が少ない」とし、事実はすべて取りこぼしとして数える。
- 台本の文の入力は、文ごとに 1 セグメント（1 文字 約 150 ms・文の間 0.4 秒の作った時刻）にする。認識結果の入力は、`segments.tsv` のセグメントを CER の採点と同じ規則（会話の時間帯 ±1 秒と最も重なる会話）で会話に分け、正解の発話と時間が重なるセグメントをその台本の行に対応づける（引用の検査に使う）。
- 失敗（形の作り直しでも直らない、文脈が足りない、答えがないなど）はモデルに送った会話の「失敗」の列に数え、事実はすべて取りこぼしにする。

### 7.3 どこで動かすか

- **ハーネスの `summary-eval`**（[`SummaryEval.kt`](../../tools/harness/src/main/kotlin/app/watchaudio/harness/SummaryEval.kt)）が入力を作り、要約し、採点する。モデルは `--provider` で選ぶ。
  - `fake`（既定）: モデルを使わない `ExtractiveLlmProvider`。プロンプトの最初の行を要約に、長い 3 行を要点（その行を引用）にした、形の正しい答えを返す。経路の確認用で、品質の数字ではない（台本の文を写すので、キーワードの一致だけで取れる下限の目安にはなる）。CI の単体テスト（`SummaryEvalTest`）がこれで全体を動かす。
  - `replay=<ディレクトリ>`: 別の場所で動くモデルの答えを読む `ReplayLlmProvider`。答えのない要求を `requests.jsonl` に書き出し、`responses.jsonl` の答えを使う（形式は §7.4）。Apple Foundation Models（Mac）と Android の端末は、この 2 つのファイルでつなぐ。
  - JVM で動くモデル（LiteRT-LM の `litertlm-jvm`）は、`LlmProvider` を実装して `SummaryEval.provider` に名前を足せば直接使える（§7.4.3）。
- **Apple Foundation Models**: `LanguageModelTests.testAnswersSummaryEvaluationRequests`（`apps/ios/Tests/LanguageModelTests`）が `requests.jsonl` をアプリと同じ `OnDeviceLanguageModel.generate` に通し、`responses.jsonl` を書く。環境変数 `SUMMARY_EVAL_DIR` がないときは飛ばす（CI では飛ぶ）。macOS 26 以降・Apple Intelligence オンの Mac のシミュレータは、その Mac のモデルを使う見込み（CI の GitHub の仮想マシンでは使えない、§6.1）。モデルの版が変わる OS の更新（例 27.x）ごとに測り直す。
- **Android の候補**: Gemini Nano（ML Kit）は対応端末の上でしか動かない。Gemma 4 E2B（LiteRT-LM）は JVM のハーネスでも端末でも動く。どちらも同じ `requests.jsonl` / `responses.jsonl` で採点できる（§7.4.3、§7.4.4）。

### 7.4 手順

#### 7.4.1 経路の確認（モデルなし、Linux）

```bash
./gradlew --max-workers=2 :tools:harness:run --args="summary-eval build/summary-eval-dry"
#   認識結果も入力にする（evaluate の作業ディレクトリ。clean・wrist・cafe の segments.tsv があるものを使う）:
#   --args="summary-eval build/summary-eval-dry --asr build/test-audio-eval --manifest tools/test-audio/cache/out/manifest.json"
#   入力・会話を絞る:     --inputs script,wrist --conversations 01,05
#   文脈の大きさ（トークン）: --context 4096（既定、Apple Foundation Models）
```

出力（`<出力ディレクトリ>/`）:

| ファイル | 内容 |
| --- | --- |
| `results.md` / `results.tsv` | 入力（`script`・`clean`・`wrist`・`cafe`）ごとの指標の表（§7.2）。先頭にモデルの id・版・文脈・プロンプトの版・正解の版 |
| `conversations.tsv` | 会話ごとの結果（状態、文字数、行数、呼び出し回数、プロンプトの文字数、時間、取れた・取りこぼした事実、作り話、引用、形） |
| `summaries.md` | 会話ごとの要約・要点（引用した台本の行と判定）・事実のチェックリスト・作り話。人が確かめる用 |

認識結果の `segments.tsv` は、[test-audio.md](../dev/test-audio.md) の手順で音声セットを作り、`evaluate`（`clean wrist cafe` だけでよい）を実行すると `<作業ディレクトリ>/<条件>/segments.tsv` にできる（ASR のモデルと約 300 MB の音声が要る）。

#### 7.4.2 Apple Foundation Models（Mac）

条件: Apple シリコンの Mac、macOS 26 以降、Apple Intelligence がオンで日本語が使える設定、Xcode 26.x、XcodeGen、JDK 21（ハーネス用）。

```bash
# 1. 要求を書き出す（答えがまだないので、全会話が「失敗 no_response」になり、requests.jsonl ができる）
OUT=$PWD/build/summary-eval-apple
./gradlew --max-workers=2 :tools:harness:run \
  --args="summary-eval $OUT --provider replay=$OUT/replay --asr build/test-audio-eval --manifest tools/test-audio/cache/out/manifest.json"

# 2. Mac のシミュレータで Apple のモデルに答えさせる（responses.jsonl に 1 要求 1 行で追記。中断しても続きから）
(cd apps/ios && xcodegen generate)
TEST_RUNNER_SUMMARY_EVAL_DIR=$OUT/replay xcodebuild test \
  -project apps/ios/WatchAudio.xcodeproj -scheme LanguageModelTests \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:LanguageModelTests/LanguageModelTests/testAnswersSummaryEvaluationRequests
#   記録: build/swift-evidence/llm-summary-eval.txt（要求の id・結果・時間だけ。文は書かない）

# 3. 同じコマンドで採点する（答えのない要求が残れば、その分だけ requests.jsonl に足されるので 2 から繰り返す）
./gradlew --max-workers=2 :tools:harness:run \
  --args="summary-eval $OUT --provider replay=$OUT/replay --asr build/test-audio-eval --manifest tools/test-audio/cache/out/manifest.json"
```

- 要求の `id` はプロンプトの中身のハッシュなので、1 と 3 は同じコミット・同じ入力で実行する（プロンプトの版や認識結果が変わると、新しい要求として書き出される）。
- 1 会話は約 450〜800 字で、4,096 トークンの 1 回の呼び出しに入る。分けて要約する場合は、まとめの要求が部分の答えに依存するので、2 と 3 を 2 回繰り返す。
- 時間はシミュレータでの値（Mac の性能）。iPhone の実機での時間・発熱・拒否の率は #60 で測る。実機でこのテストを動かすには、`requests.jsonl` を端末に置く仕組みが要る（未実装。同じ版の OS なら Mac と iPhone のモデルは同じ見込みなので、品質は Mac で測る）。
- 答えのファイルの形式: `{"id": "<要求の id>", "ok": "<答えの JSON の文字列>", "latencyMs": 1234, "model": "ios-26.4"}`、失敗は `"ok"` の代わりに `"error": "<コード>"`（`OnDeviceLanguageModel.errorCode` のコード。`guardrail_violation`・`refusal` は拒否、`exceeded_context_window` は文脈超過として、アプリと同じに扱う）。

#### 7.4.3 Gemma 4 E2B（LiteRT-LM）

モデル（約 2.6 GB）が要るので、空きのある環境で行う（このクラウドのコンテナはディスクが足りない）。

1. JVM（Linux・Mac）: `tools/harness/build.gradle.kts` に `com.google.ai.edge.litertlm:litertlm-jvm`（版を固定）を足し、`LlmProvider` を実装する（モデルのパスから `Engine` を作り、要求ごとに新しい会話で `instructions` と `prompt` を渡す。形は `request.schema.toJson()` をプロンプトに足して JSON を求める、§4.1）。`SummaryEval.provider` に `litertlm=<モデルのパス>` を足し、`--provider litertlm=models/gemma-4-E2B-it.litertlm --context <エンジンに設定した文脈>` で実行する。CPU での時間は端末の目安にならないので、時間は 2 で測る。
2. 端末（Android）: A1 の `LlmProvider` ができたら、§7.4.2 と同じ 2 つのファイルでつなぐ。`adb push $OUT/replay/requests.jsonl /sdcard/Android/data/<アプリの id>/files/summary-eval/` → 計測用の instrumented test（`SUMMARY_EVAL_DIR` に相当する引数で、要求ごとに `generate` して `responses.jsonl` を書く。Swift のテストと同じ形）→ `adb pull .../responses.jsonl $OUT/replay/` → §7.4.2 の 3。中位機種の時間・メモリ・発熱は #61。

#### 7.4.4 Gemini Nano（ML Kit GenAI Prompt API）

- 対応端末（AICore と Gemini Nano がある機種）の上でしか動かない。§8 の 3 の決定（2026-10-17）で、アプリの `GeminiNanoLlmProvider`（#205）を使える。計測用の instrumented test でこれを呼ぶ（ML Kit を使うのは `MlKitGeminiNano.kt` だけ、§5.2）。
- 手順は §7.4.3 の 2 と同じ: `requests.jsonl` を端末に置き、Prompt API に `instructions` と `prompt`（形は `schema` をプロンプトに足して JSON を求める）を渡し、答え（または失敗のコード）と時間を `responses.jsonl` に書いて取り出し、`--provider replay=<ディレクトリ> --context <Prompt API の文脈の上限>` で採点する。
- 入力は検証用の合成の会話だけなので、ML Kit が送る利用状況のログ（内容は含まない、§2.2）があっても個人の情報は出ない。

### 7.5 結果

モデルなしの経路の確認（`fake`、`./gradlew --max-workers=2 :tools:harness:run --args="summary-eval build/summary-eval-dry"`、2026-10-14）。品質の数字ではなく、台本の行をそのまま写した答えでキーワードの一致だけで取れる量の目安:

| 入力 | 会話 | 事実の再現率 | 作り話（mustNot） | 引用の正しさ | 形の成功率 | 拒否の率 | 失敗 | 発話が少ない | 呼び出し | 時間/会話 中央値・p90・最大 (s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| script | 16 | 39% (33/84) | 0 | 100% (29/29)、判定外 19 | 100% (16/16) | 0% (0/16) | 0 | 0 | 16 | 0.0 / 0.0 / 0.0 |

- 16 会話とも 1 回の呼び出しに入った（プロンプト 約 520〜910 字、4,096 トークンの文脈）。

Apple Foundation Models・Gemma 4 E2B・Gemini Nano の表は、§7.4 の手順で実行して #197 に貼る。

## 8. 決めること

「推奨」は Claude の案。「決める人」が Claude の行は、この案で進める（反対があれば止める）。

| # | 決めること | 選択肢 | 推奨 | 決める人 |
| --- | --- | --- | --- | --- |
| 1 | 最初に作るもの | (a) 1 時間ごとの要約（#27）を「この日を要約」ボタンで作る / (b) 充電中に自動で作る / (c) 1 日の振り返り（#29）から | **(a)**。データの形は (b) と同じにして、評価のあとで (b) を足す | オーナー（2026-10-13 に (a) で決定） |
| 2 | 自動で作るか | (a) 押したときだけ / (b) 設定「充電中に自動で要約する」（既定オフ）/ (c) 既定オン | 最初は **(a)**、評価と実機の確認のあと **(b)** を足す。既定オンにするかはその後 | オーナー（2026-10-13 に (a) で決定） |
| 3 | Android の手段 | (A) ML Kit GenAI（Gemini Nano）: ダウンロード不要、内容を含まない利用状況のログを Google に送る、前面でだけ動く、対応端末は上位機種中心、Prompt API はベータで日本語は未記載 / (B) LiteRT-LM + Gemma 4 E2B を本人が選んだらダウンロード（約 2.6 GB、Wi-Fi）: 送信なし、充電中のバックグラウンドで動く、RAM 8 GB 程度の端末なら機種を問わない / (C) Android は当面なし（iOS だけ先に） | §5 の基準（内容を出さない）では (A) も採れる。最初の機能は押したときだけ・画面を開いている間に作るので、前面だけの制約は効かない。**対応端末では (A)、非対応端末の (B) は評価のあとで検討**を推奨。iOS を先に作り、Android に着手するときにオーナーと最終確認する | オーナー（**2026-10-17 に (A) で決定**、#205。非対応端末は iPhone と同じ「この端末では使えません」。(B) の Gemma は評価（#197）のあとで検討） |
| 4 | 内容を含まない利用状況のログを送る依存（ML Kit）を受け入れるか | 受け入れる（許可リスト・ポリシー・ストアの申告を変える）/ 受け入れない | **受け入れてよい**（2026-10-13 にオーナーが基準を確認: 出さないのは音声・文字起こしなどの個人的な内容） | オーナー（**2026-10-17 に受け入れると決定**、#205。CLAUDE.md の「オーナーが承認した SDK の診断ログ」に当たる。許可リスト・ポリシー・ストアの申告を更新した） |
| 5 | Apple の Private Cloud Compute | 使わない / 本人が選んだら使う | **使わない**（Apple のサーバーに文字起こしが届く。端末内だけという約束と合わない。BYOK と同じ扱いで将来検討するなら別の Issue） | オーナー |
| 6 | iPhone の非対応端末での代わり | 出さない（「使えません」と表示）/ 自分でモデルを持つ | **出さない**（非対応の iPhone は RAM が少なく、大きいモデルが載らない。BYOK（#26）が来たらそちらで補う） | オーナー |
| 7 | 要約の保存先 | バックアップに入れる / 入れない、書き出しに入れる / 入れない | バックアップには**入れない**（作り直せる）、書き出し（#155）には**入れる** | Claude |
| 8 | 出力の言語 | 日本語だけ / 設定で日英 | 最初は**日本語だけ**（英語の会話も日本語で要約）。日英の設定は #27 の残りとして後で | Claude |
| 9 | iOS の guardrail | 既定 / `permissiveContentTransformations` | **`permissiveContentTransformations`**（本人の会話の変換なので。拒否の率は実機で測る） | Claude |
| 10 | Android の最低条件 | RAM と OS の版 | 実機の測定（#61）で決める。それまでは RAM 8 GB 以上を目安に表示を出し分ける | Claude（測定のあと報告） |

## 9. 作業 Issue（案）

UC9（#118）の sub-issue にする。#27 は「共通部分」に合わせて書き直すか、下の S1 を #27 の中身として扱う。#39 は下の I1・A1・E1 の親として残すか、閉じて分ける（lead の判断）。

### S1. 1 時間ごとの要約の共通部分（`shared/*`）

- **目的**: どの端末でも同じ手順で、1 時間ごとの要約と根拠つきの要点を作って保存できるようにする（端末ごとに違うのは LLM の呼び出しだけにする）。
- **やること**:
  - `LlmProvider` の最小のインターフェース（§4.1）と `FakeLlmProvider`
  - 行のまとめ方・予算・分けて要約してまとめる手順・印の優先・発話が少ない時間帯の扱い（§4.2）、情報量のスコア
  - 出力の形（`OutputSchema`）・JSON の検査・引用の検査（§4.5）
  - マイグレーション（§4.3）、古い印のトリガ、日・全削除への追加、書き出し（#155）への追加
  - `SummaryRun`（時間帯ごとに作って保存する。止められる。失敗は要約の中で止める）
  - 画面: 日別タイムラインの時間帯ごとの要約・要点、「この日を要約」ボタン、§4.6 の状態、要点から根拠の発話と再生へ（`shared/ui`、`AppServices` に要約の口を追加）
- **完了条件**:
  - [ ] 4,096 / 8,192 / 32K の文脈で、1 時間 20,000 文字の入力が予算を超えずに分かれ、まとめまで終わる — 確認: 単体テストの CI 結果
  - [ ] 存在しない行番号・時間帯の外の引用が捨てられ、根拠のない要点が残らない — 確認: `FakeLlmProvider` の単体テスト
  - [ ] セグメントの入れ替えで要約が「更新待ち」になり、日・全削除で要約が消える — 確認: `sqliteTest`（JVM・Android エミュレータ・iOS シミュレータ）
  - [ ] 偽の LLM で「この日を要約」→ 時間帯ごとに表示 → 要点から再生、と各状態の表示 — 確認: UI テスト（JVM）とスクリーンショット
  - [ ] iOS の Kotlin のクロスコンパイルが通る — 確認: CI

### I1. iPhone: Apple Foundation Models での要約

- **目的**: Apple Intelligence 対応の iPhone で、API キーも通信もなしに 1 時間ごとの要約を作れるようにする。
- **やること**:
  - Swift の `LlmProvider` の実装（`SystemLanguageModel.default` だけ。可用性と理由の対応、`supportsLocale` で日本語を確認、`contextSize` / `tokenCount`（26.4 未満は見積もり）、`DynamicGenerationSchema`、`permissiveContentTransformations`、エラーの対応づけ）と `IosAppGraph` への組み込み
  - `tools/privacy-check` の規則（§5.2）
  - [ios-watchos.md](../platforms/ios-watchos.md) に節を追加
- **完了条件**:
  - [ ] iOS のビルドと、モデルを使わない部分の XCTest が通る。シミュレータで使えないときは理由が記録されて飛ぶ — 確認: CI（`ios.yml`）のジョブサマリー
  - [ ] PCC・他社のモデル・フィードバックの添付を使うコードが入ると `tools/privacy-check` が失敗する — 確認: `test_check.py` の CI 結果
  - [ ] 実機での確認（機内モードでの生成、非対応端末の表示、時間・発熱、拒否の率）を #60 に追加した — 確認: #60 の本文

### A1. Android: 端末内の LLM での要約（§8 の 3 の決定に従う。以下は (B) の場合）

> 2026-10-17: §8 の 3 は (A) に決まり、[#205](https://github.com/Soichiro5/watch-audio/issues/205) で ML Kit GenAI（Gemini Nano）を実装した（[android-phone.md §4.7](../platforms/android-phone.md#47-1-時間ごとの要約gemini-nano205)）。以下の (B) の案は、評価（#197）のあとで非対応端末向けに検討するときのために残す。

- **目的**: Android でも、データを外に出さずに 1 時間ごとの要約を作れるようにする。
- **やること**:
  - LiteRT-LM（`litertlm-android`、版を固定）の `LlmProvider` の実装、メモリの判定、モデルのダウンロード（既存の `ModelDownloader` / `ModelManifest`、コミット固定・SHA-256・Wi-Fi のみ・本人が選んだときだけ・削除できる）
  - `tools/privacy-check` の許可リスト（§5.2）と ML Kit の禁止
  - 小さいモデルで経路を確かめるテスト（JVM のハーネスとエミュレータ）
  - [android-phone.md](../platforms/android-phone.md) に節を追加、プライバシーポリシーの第 5 条
- **完了条件**:
  - [ ] 小さいモデルで、読み込み → 生成 → JSON の検査 → 保存がエミュレータで通り、その間に外向きの接続がない — 確認: `emulator.yml` の CI 結果
  - [ ] 依存の検査が LiteRT-LM を許し、ML Kit・`datatransport` を拒む — 確認: `tools/privacy-check` の CI 結果
  - [ ] 実機での確認（ダウンロード、中位機種の時間・メモリ・発熱、機内モードでの生成）を #61 に追加した — 確認: #61 の本文

### E1. 日本語の要約の品質の評価

- **目的**: 端末内の LLM の要約が、日本語の会話を振り返るのに使える品質かを数字で確かめ、自動で作るか・Android の手段（§8）を決める材料にする。
- **やること**: §7 の評価セット（正解の事実）、ハーネスの評価のコマンド（LiteRT-LM、JVM）、Mac で Apple のモデルを動かす手順、結果の表（台本の文と認識結果の条件ごと）。評価セット・採点・`summary-eval`・Mac で答えさせるテストは #197 で作った（§7）
- **完了条件**:
  - [ ] 16 会話の正解の事実と、採点のロジックの単体テスト — 確認: CI
  - [ ] Gemma 4 E2B（LiteRT-LM）での §7.2 の指標の表 — 確認: Issue のコメント（コマンドと結果）
  - [ ] Apple Foundation Models での同じ表 — 確認: Issue のコメント（Mac または実機で実行。CI では動かない）

### D1. 文書とポリシー（lead）

- CLAUDE.md の AI の行（§5.4）、[02 §6.5・§7](../architecture/02-architecture.md#65-将来p2-ai-プロバイダbyok)、[03](../product/03-roadmap.md) の UC9 の行、プライバシーポリシー（§5.3）、ストアの申告の確認。

## 10. 参考（2026-10-13 に閲覧）

- Apple: [Foundation Models](https://developer.apple.com/documentation/foundationmodels)、[SystemLanguageModel](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel)、[TN3193 文脈の扱い](https://developer.apple.com/documentation/technotes/tn3193-managing-the-on-device-foundation-model-s-context-window)、[WWDC26 What's new in the Foundation Models framework](https://developer.apple.com/videos/play/wwdc2026/241/)、[WWDC26 グループラボ](https://developer.apple.com/videos/play/wwdc2026/8121/)、[Apple Intelligence の対応機種と言語](https://www.apple.com/apple-intelligence/)、[利用要件](https://developer.apple.com/apple-intelligence/acceptable-use-requirements-for-the-foundation-models-framework/)、フォーラム（[793787](https://developer.apple.com/forums/thread/793787)、[790860](https://developer.apple.com/forums/thread/790860)、[815397](https://developer.apple.com/forums/thread/815397)）
- Google: [ML Kit GenAI](https://developers.google.com/ml-kit/genai)、[要約 API](https://developers.google.com/ml-kit/genai/summarization/android)、[Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)、[Gemini Nano](https://developer.android.com/ai/gemini-nano)、[ML Kit のデータ開示](https://developers.google.com/ml-kit/android-data-disclosure)、[ML Kit GenAI 追加規約](https://developers.google.com/ml-kit/genai-terms)、Google Maven の `com.google.mlkit:genai-prompt:1.0.0-beta4`・`genai-summarization:1.0.0-beta1`・`com.google.ai.edge.litertlm:litertlm-android:0.18.0` の POM と AAR
- LiteRT-LM と Gemma 4: [LiteRT-LM の解説](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)、[gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)、[MediaPipe LLM Inference（保守のみ）](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference)
