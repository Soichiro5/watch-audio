#!/usr/bin/env bash
# Phone emulator checks that need steps outside a single instrumentation run (emulator.yml).
# Runs against the app the smoke step installed (built in the same job as the test APK):
#
#   1. pushes the speech models and reference recordings (tools/harness/models) to
#      /data/local/tmp/watchaudio-test/models; the instrumented tests copy them into the app,
#   2. #32 PermissionDeniedTest: refuses the notification permission in the system dialog on a
#      fresh install (revoking a permission kills the app, so it must come first),
#   3. #20 ResumeAfterKillTest: seeds chunks, opens the app, force-stops it in the middle of the
#      transcription run and verifies that every chunk is transcribed exactly once after the restart.
#
# Evidence: *_CHECK lines in scenario-logs/ (logcat and `am instrument` output), screenshots in
# /data/local/tmp/watchaudio-screenshots (pulled by the workflow). Exit status 1 if a check failed.
set -uo pipefail

PKG=app.watchaudio
RUNNER=app.watchaudio.test/androidx.test.runner.AndroidJUnitRunner
TEST_APK=androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
MODELS=tools/harness/models
ASR=sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
DEVICE_MODELS=/data/local/tmp/watchaudio-test/models
OUT=scenario-logs
mkdir -p "$OUT"
status=0

log() { echo "$*" | tee -a "$OUT/summary.txt"; }

# Keeps everything the device logged so far, then starts a fresh logcat buffer.
save_logcat() { adb logcat -d >> "$OUT/logcat-scenarios.txt" 2>/dev/null || true; adb logcat -c || true; }

# instrument <name> <am instrument args...>: runs one test method and checks it passed.
instrument() {
  local name=$1
  shift
  adb shell am instrument -w "$@" "$RUNNER" > "$OUT/$name.txt" 2>&1
  cat "$OUT/$name.txt"
  if grep -q "^OK (1 test)" "$OUT/$name.txt" && ! grep -q "FAILURES" "$OUT/$name.txt"; then
    log "SCENARIO $name passed"
  else
    log "SCENARIO $name FAILED (see $OUT/$name.txt)"
    status=1
  fi
}

adb logcat -G 16M || true
adb logcat -c || true

# 1. Models and reference recordings.
if [ -f "$MODELS/$ASR/tokens.txt" ] && [ -f "$MODELS/silero_vad.onnx" ]; then
  adb shell mkdir -p "$DEVICE_MODELS/$ASR/test_wavs"
  adb push "$MODELS/silero_vad.onnx" "$DEVICE_MODELS/"
  for f in encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt; do
    adb push "$MODELS/$ASR/$f" "$DEVICE_MODELS/$ASR/"
  done
  adb push "$MODELS/$ASR/test_wavs/." "$DEVICE_MODELS/$ASR/test_wavs/"
  adb shell chmod -R a+rX /data/local/tmp/watchaudio-test
  log "MODELS pushed to $DEVICE_MODELS"
else
  log "MODELS missing in $MODELS (run tools/harness/download-models.sh)"
  status=1
fi

adb install -r -t "$TEST_APK" || { log "SCENARIO install of the test APK FAILED"; exit 1; }

# 2. Refused permission (fresh install: the permission was never granted).
instrument permission-denied -e class app.watchaudio.android.PermissionDeniedTest -e scenario permission-denied
save_logcat

# 3. Resume after a force-stop in the middle of a transcription run.
instrument resume-seed -e class app.watchaudio.android.ResumeAfterKillTest#seed -e scenario resume-seed -e requireModels true
save_logcat
adb shell am start -W -n "$PKG/app.watchaudio.android.MainActivity"
transcribed=0
for _ in $(seq 1 600); do
  transcribed=$(adb logcat -d -s TranscriptionWorker:I | grep -c "transcribed chunk" || true)
  [ "$transcribed" -ge 1 ] && break
  sleep 0.5
done
adb shell am force-stop "$PKG"
adb logcat -d -s TranscriptionWorker:I AppGraph:I > "$OUT/resume-before-kill.txt" || true
transcribed=$(grep -c "transcribed chunk" "$OUT/resume-before-kill.txt" || true)
started=$(grep -o "transcribing chunk [^ ]*" "$OUT/resume-before-kill.txt" | awk '{print $3}' || true)
interrupted=""
for id in $started; do
  grep -q "transcribed chunk $id" "$OUT/resume-before-kill.txt" || interrupted=$id
done
log "RESUME_CHECK phase=killed command='am force-stop $PKG' transcribed_before_kill=$transcribed interrupted_chunk=${interrupted:-none}"
save_logcat
if [ -n "$interrupted" ]; then
  instrument resume-verify -e class app.watchaudio.android.ResumeAfterKillTest#verify -e scenario resume-verify \
    -e transcribedBeforeKill "$transcribed" -e interruptedChunk "$interrupted"
else
  instrument resume-verify -e class app.watchaudio.android.ResumeAfterKillTest#verify -e scenario resume-verify \
    -e transcribedBeforeKill "$transcribed"
fi
save_logcat

grep -ho "[A-Z_]*_CHECK .*\|SCREENSHOT .*\|MODEL_INSTALL .*" "$OUT/logcat-scenarios.txt" >> "$OUT/summary.txt" || true
exit $status
