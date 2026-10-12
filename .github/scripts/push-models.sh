#!/usr/bin/env bash
# Pushes the speech models, the embedding model and reference recordings (tools/harness/models, from
# tools/harness/download-models.sh) to /data/local/tmp/watchaudio-test/models on the emulator, where
# the instrumented tests copy them into the app (SpeechFixtures). Used by release-asr.sh and
# phone-scenarios.sh; the files survive uninstalling the app, so the second call skips the push.
# Prints one MODELS line; exit status 1 if the models are missing locally.
set -euo pipefail

MODELS=tools/harness/models
ASR=sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
DEVICE_MODELS=/data/local/tmp/watchaudio-test/models

if [ "$(adb shell "test -f $DEVICE_MODELS/.pushed && echo yes" | tr -d '\r')" = "yes" ]; then
  echo "MODELS already on the device in $DEVICE_MODELS"
  exit 0
fi
if [ ! -f "$MODELS/$ASR/tokens.txt" ] || [ ! -f "$MODELS/silero_vad.onnx" ]; then
  echo "MODELS missing in $MODELS (run tools/harness/download-models.sh)"
  exit 1
fi
adb shell mkdir -p "$DEVICE_MODELS/$ASR/test_wavs"
adb push "$MODELS/silero_vad.onnx" "$DEVICE_MODELS/"
for f in encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt; do
  adb push "$MODELS/$ASR/$f" "$DEVICE_MODELS/$ASR/"
done
adb push "$MODELS/$ASR/test_wavs/." "$DEVICE_MODELS/$ASR/test_wavs/"
# The embedding model of semantic search (#24), for EmbeddingDeviceTest.
RURI=ruri-v3-30m
if [ -f "$MODELS/$RURI/model_quantized.onnx" ] && [ -f "$MODELS/$RURI/tokenizer.json" ]; then
  adb shell mkdir -p "$DEVICE_MODELS/$RURI"
  adb push "$MODELS/$RURI/model_quantized.onnx" "$MODELS/$RURI/tokenizer.json" "$DEVICE_MODELS/$RURI/"
fi
adb shell chmod -R a+rX /data/local/tmp/watchaudio-test
adb shell touch "$DEVICE_MODELS/.pushed"
echo "MODELS pushed to $DEVICE_MODELS"
