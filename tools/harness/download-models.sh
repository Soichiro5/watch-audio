#!/usr/bin/env bash
# Downloads the on-device ASR models used by the harness (and by the Android app) into
# tools/harness/models/. Only a download: nothing is ever uploaded.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p models && cd models
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
NAME=sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
if [ ! -f "$NAME/tokens.txt" ]; then
  curl -fL --retry 4 "$GH/$NAME.tar.bz2" | tar xj \
    "$NAME/encoder-epoch-99-avg-1.int8.onnx" "$NAME/decoder-epoch-99-avg-1.int8.onnx" \
    "$NAME/joiner-epoch-99-avg-1.int8.onnx" "$NAME/tokens.txt" "$NAME/test_wavs"
fi
[ -f silero_vad.onnx ] || curl -fL --retry 4 "$GH/silero_vad.onnx" -o silero_vad.onnx
ls -la . "$NAME"
