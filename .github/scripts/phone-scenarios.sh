#!/usr/bin/env bash
# Phone emulator checks that need steps outside a single instrumentation run (emulator.yml).
# Runs against the app the smoke step installed (built in the same job as the test APK):
#
#   1. pushes the speech models and reference recordings (tools/harness/models) to
#      /data/local/tmp/watchaudio-test/models (push-models.sh); the instrumented tests copy them into the app,
#   2. #32 PermissionDeniedTest: refuses the notification permission in the system dialog on a
#      fresh install (revoking a permission kills the app, so it must come first),
#   3. #20 ResumeAfterKillTest: seeds chunks, opens the app, force-stops it in the middle of the
#      transcription run and verifies that every chunk is transcribed exactly once after the restart,
#   4. #77 DiagnosticsScenarioTest: receives and transcribes a chunk, force-stops the app and checks
#      the diagnostics log (events, previous exit reason) and its export from the Status tab.
#
# Evidence: *_CHECK lines in scenario-logs/ (logcat and `am instrument` output), screenshots in
# /data/local/tmp/watchaudio-screenshots (pulled by the workflow). Exit status 1 if a check failed.
set -uo pipefail

PKG=app.watchaudio
RUNNER=app.watchaudio.test/androidx.test.runner.AndroidJUnitRunner
TEST_APK=androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
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

# 1. Models and reference recordings (already there when release-asr.sh ran first).
bash .github/scripts/push-models.sh > "$OUT/push-models.txt" 2>&1 || status=1
log "$(tail -1 "$OUT/push-models.txt")"

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

# 4. Diagnostics log (#77): a chunk is received and transcribed, the running app is force-stopped,
#    and the next start finds the events and the exit reason in the log and exports it.
instrument diagnostics-receive -e class app.watchaudio.android.DiagnosticsScenarioTest#receive -e scenario diagnostics-receive -e requireModels true
save_logcat
adb shell am start -W -n "$PKG/app.watchaudio.android.MainActivity"
sleep 3
stopped_from=$(adb shell date +%s | tr -d '\r')
adb shell am force-stop "$PKG"
stopped_until=$(adb shell date +%s | tr -d '\r')
log "DIAGNOSTICS_CHECK phase=stopped command='am force-stop $PKG' device_time_s=$stopped_from..$stopped_until"
save_logcat
instrument diagnostics-verify -e class app.watchaudio.android.DiagnosticsScenarioTest#verify -e scenario diagnostics-verify \
  -e stoppedFrom "${stopped_from}000" -e stoppedUntil "${stopped_until}999"
save_logcat
# The exported CSV (debug build, so run-as can read the app's cache) and an excerpt for the summary.
adb exec-out "run-as $PKG sh -c 'cat cache/diagnostics-export/*.csv'" > "$OUT/diagnostics-export.csv" 2>/dev/null || true
if [ -s "$OUT/diagnostics-export.csv" ]; then
  log "DIAGNOSTICS_EXPORT rows=$(($(wc -l < "$OUT/diagnostics-export.csv") - 1)) file=$OUT/diagnostics-export.csv; excerpt:"
  grep -E "chunk=diagnostics-check|,app_start,|,previous_exit," "$OUT/diagnostics-export.csv" | tail -n 8 | sed 's/^/  /' | tee -a "$OUT/summary.txt"
fi

# Lines can be logged twice (Log + System.out); keep the first of each.
grep -ho "[A-Z_]*_CHECK .*\|SCREENSHOT .*\|MODEL_INSTALL .*" "$OUT/logcat-scenarios.txt" | awk '!seen[$0]++' >> "$OUT/summary.txt" || true
exit $status
