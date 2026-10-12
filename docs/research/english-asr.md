# 英語・日英混在の文字起こし（Android）: 方式の比較と採用

- Issue: [#81 Android で英語・日英混在の会話を文字起こしできる](https://github.com/Soichiro5/watch-audio/issues/81)
- 実施日: 2026-10-12
- 環境: この開発環境（Linux、4 vCPU、ASR 2 スレッド。他の評価と並行したため速度は参考値）
- 前提: [M0-5 スパイク](m0-5-asr-desktop.md)（Zipformer ReazonSpeech を採用、SenseVoice Small は日英の言語自動判定ができるが日本語の会話調で誤りが多い）
- 評価データ・採点: [検証用音声セット](../dev/test-audio.md)（日本語 8 条件 + 英語・日英混在の 4 条件を追加）

## TL;DR

<!-- TLDR -->

## 1. 背景

Android の文字起こしは sherpa-onnx + Zipformer ReazonSpeech（int8、約 160 MB）で、日本語専用。英語の発話を聞かせると、英語として認識しないだけでなく、10 秒の英文に「はい」「そう」程度の 1 語しか出さないか、固有名詞などをカタカナの別の語にする（「JVキャンストレーション」）。英語の会議・英語を話す相手との会話は記録に残らない。

iOS は SpeechAnalyzer（OS の音声認識）で、本件の対象外（§7）。

## 2. 評価データ

| データ | 内容 | 使い方 |
| --- | --- | --- |
| 日本語 8 条件（#56） | 合成音声の会話 16 本 505 発話を clean・wrist・office・cafe・outdoor・rub に加工、1 時間の day、ReazonSpeech テスト 150 文（reazon_clean） | 日本語の精度が落ちないことの確認（`--english off` と `on`） |
| `english_clean` / `english_wrist`（新規） | FLEURS en テスト（実際の人の英語の読み上げ）60 文を、装着者と相手が交互に話す 6 文 × 10 会話に並べたもの。11.3 分 | 英語の WER、英語の発話が英語として保存されるか |
| `mixed_clean` / `mixed_wrist`（新規） | コーパスの日本語の発話 1〜3 つごとに英語の発話（FLEURS en の残り 60 文）を挟んだ日英の会話（日本語 114・英語 60 発話、18.6 分） | 話者・発話ごとに言語が変わる会話で、発話ごとに正しい言語・モデルに振り分けられるか |

- 生成は `tools/test-audio/generate.py --fleurs-manifest research/asr-benchmark/data/manifest.jsonl`（乱数は `--seed 56` で固定、再現可能）。FLEURS は CC BY 4.0 で、音声はローカルの評価にだけ使いコミットしない（[test-audio.md §5](../dev/test-audio.md#5-ライセンス)）。
- 採点: 日本語は CER（従来どおり）、英語は WER（M0-5 と同じ正規化）。保存されたセグメントの言語（`lang`）が発話の言語と違うものを「言語の誤り」として数える。

## 3. 候補

| 方式 | 内容 | 追加のダウンロード |
| --- | --- | --- |
| A. 日本語のみ（現在） | Zipformer だけ | なし |
| B. SenseVoice に置き換え | すべての区間を SenseVoice（言語自動判定）で | 240 MB（Zipformer は不要になる） |
| C. 全区間で SenseVoice を併用 | 区間ごとに SenseVoice と Zipformer の両方で認識し、SenseVoice が英語と判定した区間だけ SenseVoice の文を使う | 240 MB |
| **D. 必要な区間だけ SenseVoice（採用）** | まず Zipformer で認識し、出力が区間の長さに対して少ない（1.5 文字/秒未満）かカタカナ・ラテン文字ばかり（8 割以上）の区間だけ SenseVoice でも認識して、英語ならその文を使う | 240 MB |
| E. 言語識別モデル + 振り分け | Whisper の言語識別（sherpa-onnx の `SpokenLanguageIdentification`）で区間の言語を判定し、英語なら SenseVoice で認識 | 240 MB + Whisper（base で 160 MB） |

### 3.1 言語判定の部品比較（発話単位、AAC 24 kbps）

M0-5 のデータ（FLEURS ja 120・ReazonSpeech 150・FLEURS en 120 発話、1 発話ずつ）で、SenseVoice の言語タグと Whisper base の言語識別を比べた（`sherpa_onnx` 1.13.8、2 スレッド）。

| 判定器 | 日本語 270 発話のうち英語と判定 | 英語 120 発話のうち英語と判定 | 処理時間（RTF） | 追加サイズ |
| --- | ---: | ---: | ---: | ---: |
| SenseVoice Small の言語タグ（認識のついでに出る） | **0** | **120** | 0.098（認識込み） | 240 MB |
| Whisper base の言語識別 | 2 | 120 | 0.060（判定のみ。英語の文には別途認識が要る） | 160 MB |

SenseVoice の判定は認識と同時に出るので、英語の文を得るのに追加の計算がない。Whisper の言語識別は判定だけで SenseVoice（または Whisper の認識）をもう 1 回走らせる必要があり、日本語を英語と取り違える数も多く、ダウンロードも増える。**E は不採用**とし、言語判定は SenseVoice のタグを使う。ReazonSpeech の 4 発話は SenseVoice が韓国語・中国語と判定したが、英語以外はすべて Zipformer の文を使うので影響しない。

### 3.2 Zipformer の出力で英語らしさを見分けられるか

全区間で SenseVoice を動かす（C）と、日本語の録音でも計算量がおよそ倍になる（M0-5 で SenseVoice の RTF は Zipformer の約 2 倍）。そこで、Zipformer が英語に対してほとんど何も出さない性質を使い、SenseVoice を動かす区間を絞れるか調べた。

- 発話単位（上のデータ）: Zipformer の出力の文字数 ÷ 音声長は、英語 120 発話で最大 0.92 文字/秒（中央値 0.21）、日本語は FLEURS ja で最小 1.95・ReazonSpeech で 10 パーセンタイル 2.07 文字/秒。
- アプリと同じ区間の切り方（VAD + 余白 0.3 秒 + 話者交代での分割）で保存された区間（`--english off` の評価結果）では次のとおり。カタカナ率 = カタカナ（「ー」「・」を含む）とラテン文字が文字に占める割合。

| SenseVoice も動かす区間の条件 | 英語の区間のうち対象 | 日本語の区間のうち対象（余分な計算） |
| --- | ---: | ---: |
| 1.5 文字/秒未満 | 87% | 4% |
| 2.0 文字/秒未満 | 90% | 8% |
| **1.5 文字/秒未満 または カタカナ率 80% 以上** | **93%** | **4%** |
| 1.5 文字/秒未満 または カタカナ率 50% 以上 | 93% | 6% |
| すべて（C） | 100% | 100% |

（英語 400 区間・日本語 4,426 区間。対象から漏れる英語の区間は、英語の発話が細かく分かれた断片に Zipformer が「はい」「え」などを出したもので、短い。）

<!-- RESULTS -->
