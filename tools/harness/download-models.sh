#!/usr/bin/env bash
# Downloads the on-device ASR models used by the harness into tools/harness/models/: the same
# files, URLs and SHA-256 as the Android app (androidApp/.../asr/ModelManifest.kt; the unit test
# ModelManifestTest checks that they match), plus the model's test_wavs for EndToEndTest.
# Only a download: nothing is ever uploaded. Interrupted downloads resume from <file>.part.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p models && cd models
HF=https://huggingface.co/csukuangfj/reazonspeech-k2-v2/resolve/7ebed8f70b8ef67f656aa08e3084e5885202d0e6
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
NAME=sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01

verified() { echo "$2  $1" | sha256sum -c --status 2>/dev/null; }

# fetch <path> <sha256> <url>: skips a verified file, resumes a .part, verifies, then renames.
# A resumed .part that fails verification (or cannot be resumed) is fetched once from scratch.
fetch() {
  local path=$1 sha=$2 url=$3
  verified "$path" "$sha" && return
  mkdir -p "$(dirname "$path")"
  if ! verified "$path.part" "$sha"; then
    curl -fL --retry 4 -C - -o "$path.part" "$url" || true
  fi
  if ! verified "$path.part" "$sha"; then
    rm -f "$path.part"
    curl -fL --retry 4 -o "$path.part" "$url"
    verified "$path.part" "$sha" || { rm -f "$path.part"; echo "SHA-256 mismatch: $url" >&2; exit 1; }
  fi
  mv -f "$path.part" "$path"
}

fetch "silero_vad.onnx" 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6 "$GH/silero_vad.onnx"
fetch "$NAME/tokens.txt" 2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def "$HF/tokens.txt"
fetch "$NAME/decoder-epoch-99-avg-1.int8.onnx" 8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485 "$HF/decoder-epoch-99-avg-1.int8.onnx"
fetch "$NAME/joiner-epoch-99-avg-1.int8.onnx" 49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de "$HF/joiner-epoch-99-avg-1.int8.onnx"
fetch "$NAME/encoder-epoch-99-avg-1.int8.onnx" 2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d "$HF/encoder-epoch-99-avg-1.int8.onnx"
# Test audio for the harness only (not downloaded by the app).
fetch "$NAME/test_wavs/1.wav" 7c8ccaa1878720165a8034763f2f3fa4fc3333472b09b75d71cdf1017db7af32 "$HF/test_wavs/1.wav"
fetch "$NAME/test_wavs/2.wav" aea37375438a3d285b7c4b80434d23c2647b5d988c4373933c817308313f14fe "$HF/test_wavs/2.wav"
fetch "$NAME/test_wavs/3.wav" 8195ae4c0b5e3cad89e5e92aa7e19d681cea73ca8cf193649e423ecb5a19a0c7 "$HF/test_wavs/3.wav"
fetch "$NAME/test_wavs/4.wav" fed64f1cdd19a72c4ef66053d2a0a66e8b35a46b6d98a359acacd3bd81478cfa "$HF/test_wavs/4.wav"
fetch "$NAME/test_wavs/5.wav" eaa18f4be5e77a340bea3d0bc25f84feaa352b3d5cba541197c2b2740e7f1dd1 "$HF/test_wavs/5.wav"
ls -la . "$NAME" "$NAME/test_wavs"
