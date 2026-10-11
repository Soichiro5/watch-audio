# 01. 調査・実現性評価

> 調査日: 2026-10-10
> 対象: 「装着中のスマートウォッチで常時録音し、スマホ上で文字起こしして端末内に蓄積する」アプリを作るにあたっての市場環境・作るべきもの・技術的な実現性。

## 1. 市場環境

### 1.1 既存の選択肢

- **Watch 録音アプリ**: Apple Watch で録音して iPhone で文字起こし・検索する常時録音アプリや、Watch 録音 + クラウド文字起こし型のアプリ（WristNote、Wave、Speechy、Transkriptor など）がある。iOS 専用のものが中心で、日本語のオンデバイス文字起こしまで揃ったものは見当たらない。
- **Apple 純正 "Audio Intelligence"**（2026-09-09 発表）: Apple Watch Series 12 / Ultra 4 に *Live Rewind*（直近 15 秒を文字起こし）と *Siri Recap*（会話要約）を搭載予定。2026 年末にベータ、**英語のみ・iPhone 16 以降必須**。iOS × 英語の領域では純正と競合する。
- **専用ハードウェア型**: Plaud、Limitless 等のペンダント/カード型レコーダー。

### 1.2 このプロダクトの方針

1. **Android / Wear OS 対応** — Watch で常時録音できる選択肢は iOS に偏っており、Galaxy Watch / Pixel Watch ユーザーには空白地帯。
2. **日本語の文字起こし** — 端末内で日本語を文字起こしできることを前提にする。
3. **データ消失リスクへの対策** — 端末内だけに保存する以上、ユーザー所有クラウドへの E2E 暗号化バックアップ（後続フェーズ）で端末紛失に備える。
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


**「装着しているスマートウォッチで常時録音し、スマホ上で文字起こしして、端末内にためていくアプリ」**（iOS + watchOS / Android + Wear OS）

### MVP に含める

- Watch での長時間バックグラウンド録音、Mark、停止
- Watch → スマホへのチャンク転送（受領確認付き）
- スマホでのオンデバイス文字起こし（**日本語・英語**）
- 日ごとのタイムラインと音声再生
- キーワード検索 + セマンティック検索（ローカル）
- BYOK による 1 時間ごとのハイライト・要約、1 日の振り返り、引用付きチャット、記憶（人物・予定などの事実）
- 音声の保持期間設定、プライバシー/同意の説明

### MVP の次（Phase 2）

- 体調データ（HealthKit / Health Connect）とストレス推定
- 場所の記録（位置 → 地名）
- オンデバイス LLM（Apple Foundation Models / Gemini Nano）による「完全オフライン」モード
- E2E 暗号化バックアップ、エクスポート
- 話者分離、ウォッチフェイス用コンプリケーション/タイル

> 体調と場所の記録は、録音〜検索〜AI のコアループが成立しないと価値が出ないため Phase 2 に回す。

## 3. 実現性評価

評価: ◎ 実証済み / ○ 実現可能・要検証 / △ 大きなリスクあり

| 領域 | iOS + watchOS | Android + Wear OS | 根拠・リスク |
| --- | --- | --- | --- |
| Watch 長時間録音 | ◎ | ○ | watchOS は audio セッションを前景で開始すれば画面オフでも録音継続（既存の Watch 録音アプリで実証済み）。割り込みで停止し自動再開不可。Wear OS は `foregroundServiceType="microphone"` の前景サービス + Ongoing Activity で実現可能だが、**前景からの開始が必須**（Android 14+ の while-in-use 制約）。**Galaxy Watch / Pixel Watch での電池持ちは未知数で最大のリスク** |
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

- [Apple Watch Live Rewind（iPhone in Canada）](https://www.iphoneincanada.ca/2026/09/09/apple-watch-adds-live-rewind-to-transcribe-the-last-15-seconds/)
- [Apple Audio Intelligence（Macworld）](https://www.macworld.com/article/3232620/why-apples-new-audio-ai-features-arent-the-privacy-nightmare-you-think-they-are.html)
- [watchOS Background execution](https://developer.apple.com/documentation/watchkit/background-execution)
- [watchOS 録音の割り込み（Apple Developer Forums）](https://developer.apple.com/forums/thread/750432)
- [Android 14 前景サービスタイプ](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [SpeechAnalyzer 概要](https://blog.addpipe.com/apple-speechanalyzer-api/)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
