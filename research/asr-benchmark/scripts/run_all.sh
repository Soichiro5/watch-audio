#!/usr/bin/env bash
# Full benchmark pipeline (run from research/asr-benchmark/). See README.md.
# Each model runs in its own process so peak RSS is per model.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=.venv/bin/python
ALL=raw,aac32,aac24,noisy_aac24

stage=${1:-all}

if [[ $stage == all || $stage == accuracy ]]; then
  # Budget-constrained plan (shared 4-vCPU container; see report). 1-thread workers in parallel
  # give the best throughput; per-run RTF is therefore NOT used as the speed metric (see `rtf` stage).
  # Main candidates: every utterance, every condition.
  ( $PY scripts/bench.py accuracy --model sensevoice_small_int8 --threads 1 --conds $ALL ) &
  ( $PY scripts/bench.py accuracy --model zipformer_reazon_int8 --threads 1 --conds $ALL
    $PY scripts/bench.py accuracy --model dolphin_base_ctc_int8 --threads 1 --conds aac24,noisy_aac24 ) &
  ( $PY scripts/bench.py accuracy --model parakeet_tdt_ctc_0.6b_ja_int8 --threads 1 --conds raw,aac24,noisy_aac24 --limit 60 ) &
  # Slow models (Whisper pads every input to 30 s; Qwen3-ASR is an autoregressive LLM):
  # AAC 24 kbps only, first 25 utts per dataset.
  ( $PY scripts/bench.py accuracy --model whisper_base_int8 --threads 1 --conds aac24 --limit 25
    $PY scripts/bench.py accuracy --model qwen3_asr_0.6b_int8 --threads 1 --conds aac24 --limit 25
    $PY scripts/bench.py accuracy --model whisper_small_int8 --threads 1 --conds aac24 --limit 25 ) &
  wait
fi

if [[ $stage == all || $stage == rtf ]]; then
  for m in sensevoice_small_int8 zipformer_reazon_int8 dolphin_base_ctc_int8 parakeet_tdt_ctc_0.6b_ja_int8 \
           whisper_base_int8 whisper_small_int8 qwen3_asr_0.6b_int8; do
    for t in 1 2 4; do
      $PY scripts/bench.py rtf --model $m --threads $t --n 3
    done
  done
fi

if [[ $stage == all || $stage == mixed ]]; then
  for m in sensevoice_small_int8 zipformer_reazon_int8 parakeet_tdt_ctc_0.6b_ja_int8 dolphin_base_ctc_int8 \
           whisper_base_int8 whisper_small_int8 qwen3_asr_0.6b_int8; do
    $PY scripts/bench.py mixed --model $m --threads 1
  done
fi

if [[ $stage == all || $stage == vad ]]; then
  $PY scripts/vad_bench.py --model sensevoice_small_int8 --minutes 10 --speech-ratio 0.35 --threads 1 --out vad_default.json
  $PY scripts/vad_bench.py --model sensevoice_small_int8 --minutes 10 --speech-ratio 0.35 --threads 1 \
      --vad-threshold 0.3 --pad 0.3 --out vad_tuned.json
fi

$PY scripts/summarize.py
