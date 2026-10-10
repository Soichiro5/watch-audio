# 01. 調査・実現性評価

> 調査日: 2026-10-10
> 対象: 依頼文の「audey.ai」は DNS が存在せず到達不能だったため、リポジトリ名 `watch-audio` と合致する
> **Auday — All-Day Watch Recorder（[auday.ai](https://auday.ai/)）** をベンチマーク対象とした。
> 別のプロダクトを想定していた場合はこのドキュメントの前提から見直すこと。

## 1. ベンチマーク: Auday とは

「Apple Watch を一日中回し続ける AI ライフレコーダー」。Watch で録音し、iPhone 側で文字起こし・検索・AI 振り返りを行う。

| 項目 | 内容 |
| --- | --- |
| コンセプト | "Back to every day you've lived" — 声・心拍/ストレス推定・場所を時間軸で束ね、一日を振り返る |
| 対応 | iPhone (iOS 18+) + Apple Watch (watchOS 11+) のみ。**Android / Wear OS 非対応** |
| 価格 | 買い切り $19.8（ローンチ時 $9.9）。サブスクなし。AI は BYOK（ユーザー自身の API キー）で従量課金はユーザー負担 |
| 文字起こし言語 | 中国語・英語のみ（**日本語の文字起こし非対応**。AI 出力のみ v1.0.1 で日本語対応） |
| サーバー | **バックエンドなし**。アカウント不要。アーカイブは iCloud バックアップからも除外 |
| 開発者 | 個人開発（GUO HANGJIANG）。2026-10-02 リリース |

### 1.1 機能一覧

| 機能 | 詳細 |
| --- | --- |
| Watch 録音 | タップで開始、タップでマーク（Mark）、1 秒長押しで停止。画面オフでも継続。電話・Siri 等の割り込みで停止し、自動再開はできない |
| バッテリー | 自社テスト: Series 11 で 12.5 時間連続録音して残量 49%（保証値ではない） |
| 同期 | Watch 側で約 15 分ごとにチャンク化 → iPhone へ転送。iPhone の受領確認まで Watch に保持 |
| ストレージ | 1 日 8 時間録音で 7 日 ≈ 800MB / 30 日 ≈ 3.5GB（≈ 32kbps 相当）。「確実に無音」の区間は音声を保存せずタイムライン上の位置のみ保持 |
| 文字起こし・検索 | iPhone 上でローカル ASR（リアルタイムではなく後追い）。キーワード検索 + セマンティック検索もローカル。話者分離なし |
| Highlights / Summary | 1 時間ごとに AI がレビューし、ハイライト・要約・情報密度スコア（Home の棒グラフ）を生成。Mark した箇所を優先 |
| Daily Reflection | 1 日の振り返り文を AI が生成 |
| Chat | Reflect / Recall / Replay。ローカル検索結果 + 記憶を文脈に BYOK の LLM が回答し、原文への引用リンク付き |
| Memory | 人物・好み・予定などの事実を抽出して保存。閲覧・編集・無効化可能 |
| Body（任意） | HealthKit から心拍・HRV(SDNN)・安静時心拍・歩数・睡眠・ワークアウトを読み取り、過去 14 日のベースラインとの比較でストレスを推定（医療用途ではない） |
| Places（任意） | 録音中のみ Watch で位置を取得 → iPhone でクラスタリング → Apple のジオコーディングで地名化。生座標は 7 日で削除 |
| AI に送る情報 | テキストと要約済みコンテキスト（体調ラベル・おおよその地名・時間帯）のみ。**音声・生の心拍値・正確な座標は送らない** |

### 1.2 競合・市場環境

- **Apple 純正 "Audio Intelligence"**（2026-09-09 発表）: Apple Watch Series 12 / Ultra 4 に *Live Rewind*（直近 15 秒を文字起こし）と *Siri Recap*（会話要約）を搭載予定。2026 年末にベータ、**英語のみ・iPhone 16 以降必須**。iOS × 英語の領域では純正と競合する。
- 類似アプリ: WristNote、Wave、Speechy、Transkriptor など（Watch 録音 + クラウド文字起こし型が中心）。
- 専用ハードウェア型: Plaud、Limitless 等のペンダント/カード型レコーダー。

### 1.3 私たちの勝ち筋（差別化）

1. **Android / Wear OS 対応** — Auday も Apple 純正も iOS 専用。Galaxy Watch / Pixel Watch ユーザーには空白地帯。
2. **日本語の文字起こし** — Auday は中英のみ、Apple 純正は英語のみ。
3. **データ消失リスクへの対策** — Auday はバックアップ不可。ユーザー所有クラウドへの E2E 暗号化バックアップ（後続フェーズ）で差別化可能。
4. **エクスポート**（Markdown / Obsidian 等）— 「自分のデータを自分で持つ」路線を強化。

## 2. 作るべきもの（プロダクト定義）

### プロダクトのテーマ（2026-10-10 確定）

> **常に起動しっぱなしで 24 時間録音を取り続け、会話をすべてデータとして残せること。**

ここから導かれる要件:

| 要件 | 内容 |
| --- | --- |
| 24 時間の連続性 | 録音が途切れないことを最優先する。Watch の充電中・非装着時は**スマホのマイクで録音を引き継ぐ**（ハンドオフ）。止まったら自動再開を試み、無理ならすぐに気づける（通知・コンプリケーション/Tile からワンタップ再開） |
| すべて残す | 文字起こしは無期限に保存。音声も既定は無期限（設定で変更可）。端末紛失に備え **E2E 暗号化バックアップを MVP に含める** |
| 録音状況の可視化 | 「いつ録れていて、いつ録れていなかったか」をタイムラインで明示する（欠損区間と理由） |
| 周囲への配慮 | 常時録音であることを前提に、同意の導線と「録らない場所・時間」のルールを用意する |

24 時間化の制約と方針は [02-architecture.md §9](02-architecture.md#9-24-時間連続録音の設計) を参照。


**「スマートウォッチを、ローカルファーストの AI ライフジャーナルにするアプリ」**（iOS + watchOS / Android + Wear OS）

### MVP に含める（Auday のコア体験を再現）

- Watch での長時間バックグラウンド録音、Mark、停止
- Watch → スマホへのチャンク転送（受領確認付き）
- スマホでのオンデバイス文字起こし（**日本語・英語**）
- タイムライン（Days）と音声再生
- キーワード検索 + セマンティック検索（ローカル）
- BYOK による 1 時間ごとの Highlights / Summary、Daily Reflection、引用付き Chat、Memory
- 音声の保持期間設定、プライバシー/同意の説明

### MVP の次（Phase 2）

- Body（HealthKit / Health Connect）とストレス推定
- Places（位置 → 地名）
- オンデバイス LLM（Apple Foundation Models / Gemini Nano）による「完全オフライン」モード
- E2E 暗号化バックアップ、エクスポート
- 話者分離、ウォッチフェイス用コンプリケーション/タイル

> Body と Places は Auday の特徴だが、録音〜検索〜AI のコアループが成立しないと価値が出ないため Phase 2 に回す。

## 3. 実現性評価

評価: ◎ 実証済み / ○ 実現可能・要検証 / △ 大きなリスクあり

| 領域 | iOS + watchOS | Android + Wear OS | 根拠・リスク |
| --- | --- | --- | --- |
| Watch 長時間録音 | ◎ | ○ | watchOS は audio セッションを前景で開始すれば画面オフでも録音継続（Auday・Just Press Record で実証）。割り込みで停止し自動再開不可。Wear OS は `foregroundServiceType="microphone"` の前景サービス + Ongoing Activity で実現可能だが、**前景からの開始が必須**（Android 14+ の while-in-use 制約）。**Galaxy Watch / Pixel Watch での電池持ちは未知数で最大のリスク** |
| Watch → スマホ転送 | ◎ | ◎ | WatchConnectivity `transferFile` / Wearable Data Layer `ChannelClient`。いずれも OS がキューイング |
| オンデバイス ASR（日本語） | ○ | ○ | iOS 26+ の `SpeechAnalyzer` / `SpeechTranscriber` は日本語をサポートし、モデルは OS 管理（アプリサイズ増なし）。Android は sherpa-onnx + SenseVoice（日中英韓粤）や Whisper 系。精度・速度は実機で要検証 |
| 後追い処理（バックグラウンド） | ○ | ◎ | iOS は `BGProcessingTask`（主に充電中・夜間）。8 時間/日の音声でも VAD で発話区間のみ処理すれば現実的。Android は WorkManager で柔軟 |
| セマンティック検索 | ○ | ○ | multilingual-e5-small 等の多言語埋め込みを ONNX Runtime で実行。1 年分（数十万ベクトル）でも int8 + 総当たりで 100ms オーダー |
| BYOK LLM | ◎ | ◎ | OpenAI 互換 / Anthropic / Gemini API を端末から直接呼ぶだけ。キーは Keychain / Android Keystore |
| Health | ◎ | ○ | HealthKit は HRV(SDNN) 含め豊富。Health Connect は HRV の書き込み元（Samsung Health 等）次第で欠損あり → 心拍のみで劣化動作させる設計に |
| ストア審査 | ○ | ○ | iOS: バックグラウンド音声の正当性・HealthKit 利用説明。Google Play: マイク前景サービスの申告（デモ動画）、Health Connect 権限申請、データセーフティ |
| 法務 | ○ | ○ | 会話録音は相手への告知・同意が望ましい（国・地域で法規制が異なる）。アプリ内で注意喚起と同意導線を用意する。※法的助言ではないため、リリース前に専門家確認を推奨 |

### 結論

- **実現可能**。コア技術（Watch 録音・転送・オンデバイス ASR・BYOK LLM）はいずれも公開 API と既存 OSS で構成でき、サーバー不要のためインフラ費もゼロ。
- **最大のリスクは Wear OS の電池持ちとオンデバイス日本語 ASR の精度**。いずれも最初のスパイク（M0）で実機計測して Go/No-Go を判断する。
- iOS×英語は Apple 純正と競合するため、**Android/Wear OS と日本語**を主戦場に据える。

## 参考

- [Auday 公式](https://auday.ai/) / [FAQ](https://auday.ai/faq) / [Privacy](https://auday.ai/privacy)
- [Auday - App Store](https://apps.apple.com/us/app/auday-all-day-watch-recorder/id6814210373)
- [Apple Watch Live Rewind（iPhone in Canada）](https://www.iphoneincanada.ca/2026/09/09/apple-watch-adds-live-rewind-to-transcribe-the-last-15-seconds/)
- [Apple Audio Intelligence（Macworld）](https://www.macworld.com/article/3232620/why-apples-new-audio-ai-features-arent-the-privacy-nightmare-you-think-they-are.html)
- [watchOS Background execution](https://developer.apple.com/documentation/watchkit/background-execution)
- [watchOS 録音の割り込み（Apple Developer Forums）](https://developer.apple.com/forums/thread/750432)
- [Android 14 前景サービスタイプ](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [SpeechAnalyzer 概要](https://blog.addpipe.com/apple-speechanalyzer-api/)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
