#!/usr/bin/env bash
# Downloads the embedding model of semantic search (ruri-v3-30m int8 ONNX + tokenizer, ~44 MB, #178)
# for the iOS simulator check (apps/ios/Tests/EmbeddingTests) into <dir>/ruri-v3-30m: the same files,
# pinned commits and SHA-256 as the apps download (RuriV3.FILES in shared/pipeline; kept equal by
# RuriFilesScriptTest). CI caches <dir> keyed by this file's hash (ios.yml), so a run only downloads
# when the pinned files change. Verified files are kept; anything else is fetched again.
#
# Usage: ios-embedding-model.sh [dir]   (default build/embedding-model)
# Prints one EMBEDDING_MODEL line per file; exits non-zero if a file cannot be fetched or verified.
set -euo pipefail

dir=${1:-build/embedding-model}
RURI=ruri-v3-30m
RURI_ONNX_HF=https://huggingface.co/onnx-community/ruri-v3-30m-ONNX/resolve/1b41a27f31213fc55a9c4653c067770e39406408
RURI_HF=https://huggingface.co/cl-nagoya/ruri-v3-30m/resolve/24899e5de370b56d179604a007c0d727bf144504

sha256() {
  if command -v shasum > /dev/null; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}

# fetch <path> <sha256> <url>
fetch() {
  local path=$1 expected=$2 url=$3
  local target="$dir/$path"
  mkdir -p "$(dirname "$target")"
  if [ -f "$target" ] && [ "$(sha256 "$target")" = "$expected" ]; then
    echo "EMBEDDING_MODEL $path cached"
    return
  fi
  rm -f "$target" "$target.part"
  curl -fsSL --retry 3 --retry-delay 5 -o "$target.part" "$url"
  local actual
  actual=$(sha256 "$target.part")
  if [ "$actual" != "$expected" ]; then
    echo "EMBEDDING_MODEL $path FAILED: sha256 $actual, expected $expected"
    rm -f "$target.part"
    exit 1
  fi
  mv "$target.part" "$target"
  echo "EMBEDDING_MODEL $path downloaded ($(wc -c < "$target" | tr -d ' ') bytes)"
}

fetch "$RURI/model_quantized.onnx" bd07545fa4eb8e7765939536944da1138a7e61441d81633adfa1faf218df6bb1 "$RURI_ONNX_HF/onnx/model_quantized.onnx"
fetch "$RURI/tokenizer.json" 0a94ac9a0a02c067bdef25b72ae9f4ee33f48f552e55988d444f6d25eeb1d062 "$RURI_HF/tokenizer.json"
