# M0-5 ASR desktop pre-selection benchmark (sherpa-onnx)

Issue #11 "[M0-5] [Spike] オンデバイス日本語 ASR の精度・速度比較" の **デスクトップ予備選定** 用ベンチマーク。
Android (sherpa-onnx) 候補モデルを、公開データ + 時計パイプライン模擬 (16 kHz mono / AAC-LC 24・32 kbps / 雑音)
で比較する。iOS (SpeechAnalyzer) と実機・実録音での検証は対象外 (レポートのチェックリスト参照)。

レポート: [`docs/research/m0-5-asr-desktop.md`](../../docs/research/m0-5-asr-desktop.md)
結果: [`results/`](results/) (`summary.md`, `accuracy.csv`, `accuracy_subset25_aac24.csv`, `rtf.csv`, `vad_default.json`, `vad_tuned.json`, `mixed_lang.jsonl`, `per_utt/*.csv`)

## 再現手順

Python 3.10+、`ffmpeg` (無ければ `pip install imageio-ffmpeg` で自動使用)、curl。ダウンロード合計 約 3.7 GB
(モデル 約 2.6 GB + データ 約 1.0 GB)。

```bash
cd research/asr-benchmark
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 1. モデル取得 (sherpa-onnx GitHub release `asr-models` + HF csukuangfj ミラー、int8 のみ)
bash scripts/download_models.sh

# 2. 評価データ取得 + 条件生成 (raw / aac32 / aac24 / noisy_aac24)
.venv/bin/python scripts/prepare_data.py --n-fleurs-ja 120 --n-reazon 150 --n-fleurs-en 120

# 3. 全ステージ実行 (accuracy -> rtf -> mixed -> vad -> summarize)
bash scripts/run_all.sh            # または: bash scripts/run_all.sh accuracy|rtf|mixed|vad

# 4. 集計のみやり直す場合
.venv/bin/python scripts/summarize.py
```

`bench.py accuracy` は `results/per_utt/<model>.csv` に追記し、既に済んだ (cond, id) はスキップする (中断再開可)。
`rtf.jsonl` / `mixed_lang.jsonl` は追記式なので、やり直す場合は削除してから実行する。

## 構成

| file | 内容 |
|---|---|
| `scripts/download_models.sh` | 候補モデル + Silero VAD のダウンロード |
| `scripts/prepare_data.py` | FLEURS ja/en・ReazonSpeech test の取得、16 kHz 化、AAC 往復、バブル雑音付加、`data/manifest.jsonl` 生成 |
| `scripts/models.py` | モデル定義 (sherpa-onnx `OfflineRecognizer` の生成) |
| `scripts/bench.py` | `accuracy` (CER/WER) / `rtf` (1/2/4 threads, peak RSS, timestamps) / `mixed` (言語自動判定) |
| `scripts/vad_bench.py` | 約 10 分の連結ファイルで Silero VAD → ASR と VAD なし固定 20 s チャンクを比較 |
| `scripts/summarize.py` | 集計 → `results/*.csv`, `results/summary.md` |

## 条件

| cond | 内容 |
|---|---|
| `raw` | 元音声を 16 kHz mono に |
| `aac32` | raw → AAC-LC 32 kbps (16 kHz mono, ffmpeg native `aac`) → デコード |
| `aac24` | raw → AAC-LC 24 kbps → デコード |
| `noisy_aac24` | raw + 5 話者バブル雑音 (SNR 10 dB, ja/en 混在) → AAC-LC 24 kbps → デコード |

## 正規化

- ja CER: NFKC → 小文字化 → Unicode カテゴリ P/Z/S/C (句読点・空白・記号・制御) を除去。
  漢字/かな/数字表記の揺れ (例: 「二十」vs「20」、「身に着け」vs「身につけ」) は正規化せずエラーとして数える。
- en WER: NFKC → 小文字化 → ハイフンを空白に → 英数字以外を除去 → 空白正規化 (`jiwer`)。
  数字表記 ("twenty" vs "20") も正規化しない。
