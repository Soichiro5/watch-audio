#!/usr/bin/env bash
# Download sherpa-onnx candidate models (offline / non-streaming) + Silero VAD.
# Usage: bash scripts/download_models.sh   (run from spikes/asr-benchmark/)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p models
cd models
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
HF=https://huggingface.co/csukuangfj

fetch_tar() {  # $1 = archive base name
  local n="$1"
  if [ -d "$n" ]; then echo "skip $n"; return; fi
  echo "download $n"
  curl -fsSL "$GH/$n.tar.bz2" -o "$n.tar.bz2"
  tar xjf "$n.tar.bz2"
  rm -f "$n.tar.bz2"
}

fetch_hf_files() {  # $1 = repo name, rest = files (int8 only, saves bandwidth)
  local n="$1"; shift
  mkdir -p "$n"
  for f in "$@"; do
    [ -f "$n/$f" ] && continue
    echo "download $n/$f"
    curl -fsSL "$HF/$n/resolve/main/$f" -o "$n/$f"
  done
}

# 1) SenseVoice-Small int8 (zh/en/ja/ko/yue), original FunAudioLLM export 2024-07-17.
#    NOTE: sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09 is NOT an update of this
#    model -- it is the Cantonese fine-tune (ASLP-lab/WSYue-ASR) and outputs <|yue|> garbage
#    for Japanese. Only the int8 file is fetched (the GitHub tarball also ships fp32, ~1 GB).
fetch_hf_files sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 model.int8.onnx tokens.txt
# 2) Japanese Zipformer transducer trained on ReazonSpeech (k2)
fetch_tar sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
# 3) NVIDIA Parakeet TDT-CTC 0.6B Japanese (int8, CTC head)
fetch_tar sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8
# 4) Dolphin base CTC multi-lang int8 (40 Eastern languages incl. ja; no English)
fetch_tar sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02
# 5) Whisper base / small (multilingual) int8 encoder+decoder only
fetch_hf_files sherpa-onnx-whisper-base base-encoder.int8.onnx base-decoder.int8.onnx base-tokens.txt
fetch_hf_files sherpa-onnx-whisper-small small-encoder.int8.onnx small-decoder.int8.onnx small-tokens.txt
# 6) Qwen3-ASR 0.6B int8 (LLM-based, multilingual incl. ja/en)
fetch_tar sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
# 7) Silero VAD
[ -f silero_vad.onnx ] || curl -fsSL "$GH/silero_vad.onnx" -o silero_vad.onnx

du -sh ./*
