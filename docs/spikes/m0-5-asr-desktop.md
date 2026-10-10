# M0-5 スパイク結果（デスクトップ編）: オンデバイス日本語 ASR の比較

- Issue: [#11 [M0-5] [Spike] オンデバイス日本語 ASR の精度・速度比較](https://github.com/Soichiro5/watch-audio/issues/11)
- 実施日: 2026-10-10
- 再現用スクリプト: [`spikes/asr-benchmark/`](../../spikes/asr-benchmark/README.md)、集計表: [`results/summary.md`](../../spikes/asr-benchmark/results/summary.md)
- 対象: Android 用の候補（sherpa-onnx で動くモデル）。iOS の SpeechAnalyzer は Linux では評価できないため対象外

## TL;DR

1. **Android の既定エンジンは「sherpa-onnx + Zipformer ReazonSpeech（int8）」に決定**。日本語の会話調データ（ReazonSpeech）で CER 7.1%（Watch 想定の AAC 24kbps でも同じ）、最速（RTF 0.03）、最小（160MB）。
2. 弱点は**英語を認識できない**こと。英語が必要になったら SenseVoice Small（日英自動判定、240MB）を併用する（Phase 2 で検討）。
3. **AAC 24kbps でも精度はほぼ劣化しない**（32kbps・無圧縮との差は誤差範囲）。Watch の録音は 24kbps で十分。
4. **VAD（Silero）は閾値 0.3 + 前後 0.3 秒のパディング**が最良（発話検出 23/23、文字誤り率も改善）。この設定はアプリと検証ハーネスに反映済み。
5. Whisper（base/small）は日本語で弱く遅いため不採用。Qwen3-ASR 0.6B は会話調で挿入エラーが暴発（CER 160%）し不採用。

## 1. 環境・データ・条件

| 項目 | 内容 |
| --- | --- |
| CPU | Xeon 4 vCPU（他ジョブと共有。速度は参考値） |
| データ | 日本語: FLEURS ja（読み上げ 120 文）、**ReazonSpeech テスト（テレビ音声・会話調 150 文）**。英語: FLEURS en（120 文） |
| 条件 | raw（16kHz）／**AAC 32kbps**／**AAC 24kbps**／AAC 24kbps + 雑音（SNR 10dB） |
| 指標 | 日本語 CER（NFKC・句読点/空白除去後）、英語 WER |

## 2. 精度（誤り率 %、低いほど良い）

| モデル | サイズ | FLEURS ja (AAC24) | **ReazonSpeech ja (AAC24)** | +雑音 | FLEURS en (AAC24) |
| --- | --- | --- | --- | --- | --- |
| **Zipformer ReazonSpeech int8** | 160MB | 6.5 | **7.1** | 8.1 | ✕（英語不可） |
| Parakeet TDT-CTC 0.6B ja int8 | 656MB | 5.7※ | 7.8※ | 9.5※ | ✕ |
| SenseVoice Small int8 | 240MB | 6.7 | 26.7（挿入が多い。置換+脱落のみなら 13.3） | 29.4 | **9.0** |
| Dolphin base CTC int8 | 104MB | 16.0 | 15.4 | 18.4 | ✕ |
| Whisper small int8 | 375MB | 20.1※ | 35.5※ | - | 10.5※ |
| Whisper base int8 | 161MB | 36.1※ | 49.7※ | - | 14.4※ |
| Qwen3-ASR 0.6B int8 | 987MB | 12.6※ | 159.8※（挿入暴発） | - | 8.4※ |

※ 計算時間の都合で 25〜60 文のサブセット。

- 圧縮の影響: Zipformer は raw 7.1 → AAC32 7.1 → AAC24 7.1（変化なし）。SenseVoice も 23.6 → 25.1 → 26.7 と小さい。
- 雑音の影響: Zipformer は +1pt 程度と頑健。SenseVoice は +3〜6pt。
- 日英混在: Zipformer は英語部分を日本語として誤認識する。SenseVoice・Qwen3 は言語を自動判定できる。

## 3. 速度・メモリ（コンテナ CPU、低負荷時）

| モデル | RTF 1 スレッド | RTF 2 スレッド | ピークメモリ | 単語/トークン時刻 |
| --- | --- | --- | --- | --- |
| **Zipformer ReazonSpeech** | **0.032** | 0.023 | 469MB | ○ |
| SenseVoice Small | 0.077 | 0.044 | 377MB | ○ |
| Parakeet 0.6B | 0.143 | 0.083 | 958MB | ○ |
| Whisper small | 0.625 | 0.458 | 1.2GB | セグメントのみ |

RTF = 処理時間 ÷ 音声長（0.03 なら 1 時間の音声を約 2 分で処理）。

## 4. VAD（Silero）の効果（10 分の連結音声、AAC24）

| 設定 | 発話検出 | フレーム再現率/適合率 | 文字誤り率 | 処理時間の削減 |
| --- | --- | --- | --- | --- |
| 閾値 0.5、パディングなし | 22/23 | 0.84 / 0.92 | 12.3% | 60% |
| **閾値 0.3、前後 0.3 秒** | **23/23** | 0.93 / 0.84 | **9.7%** | **64%** |

パディングなしでは発話の頭が欠ける（例:「ヤンバルクイナ」→「クイナ」）。検証ハーネスの E2E テストでも同じ現象を確認し、上記設定で解消した。

## 5. 1 日分の処理時間の見積もり（スマホ）

仮定: 装着 16 時間/日、VAD 後の発話 約 31%（≈ 5 時間）、スマホ 1 コアはこのコンテナの 2〜4 倍遅い。

| 処理 | コンテナ | スマホ推定 |
| --- | --- | --- |
| VAD（16 時間分） | 約 5 分 | 10〜20 分 |
| ASR（5 時間分、Zipformer 2 スレッド） | 約 7 分 | 14〜28 分 |
| **合計** | 約 12 分 | **約 25〜50 分/日** |

→ 充電中のバックグラウンド処理（Android: WorkManager、iOS: BGProcessingTask）で十分に回る。チャンク到着ごとの逐次処理でも追いつく見込み。

## 6. 決定事項

| 項目 | 決定 |
| --- | --- |
| Android 既定エンジン | sherpa-onnx + Zipformer ReazonSpeech int8（encoder/decoder/joiner int8 + tokens） |
| 英語対応 | 当面なし。必要になれば SenseVoice Small を追加（言語自動判定） |
| VAD | Silero、閾値 0.3、最小無音 0.5s、最小発話 0.25s、最大発話 20s、前後 0.3s パディング（`AsrSettings`）。認識器への入力は 20s 以下にし、区間内の 0.22s 以上の間（大きい音より 30dB 以上小さい）で分割する（#68、[02 §6.3](../02-architecture.md#発話区間の切り出しandroid--jvm-ハーネス68)） |
| Watch の音声形式 | AAC-LC 16kHz mono 24kbps |
| iOS | SpeechAnalyzer / SpeechTranscriber（日本語対応、モデルは OS 管理）。精度は実機で比較する |

## 7. 実機で確認すること（チェックリスト）

- [ ] 実際の Watch 録音（手首の位置・衣擦れ・距離）での CER（参照テキストを 30 分ぶん用意）
- [ ] 中位機種での RTF・メモリ・発熱（Zipformer、2 スレッド）
- [ ] 1 日分を充電中に処理しきれるか（WorkManager の実行時間制限との兼ね合い）
- [ ] iOS SpeechTranscriber と Zipformer の精度比較（同じ音声で）
- [ ] チャンク境界（5 分ごと）で発話が分断される影響（#45 で対応検討）
