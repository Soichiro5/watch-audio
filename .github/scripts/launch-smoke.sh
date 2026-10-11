#!/usr/bin/env bash
# Launch check on an emulator (emulator.yml, #44 / #78): installs the APK, launches app.watchaudio
# and fails if our process is not running 15 s later or crashed. Crashes of other processes (system
# apps such as the Wear setup wizard sometimes crash on CI images) do not fail the check.
#
# Usage: launch-smoke.sh <apk> <logcat output file>
# The LAUNCH_CHECK result line is also appended to launch-checks.txt (job summary).
set -uo pipefail

PKG=app.watchaudio
apk=$1
out=$2

result() { echo "LAUNCH_CHECK $(basename "$apk") $*" | tee -a launch-checks.txt; }

adb install -r "$apk" || { result "FAILED: install"; exit 1; }
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 15
adb logcat -d > "$out" || (sleep 5 && adb wait-for-device && adb logcat -d > "$out")
if grep -q "AndroidRuntime: Process: $PKG" "$out"; then
  grep -B2 -A25 "AndroidRuntime: Process: $PKG" "$out"
  result "FAILED: crash"
  exit 1
fi
if ! adb shell pidof "$PKG"; then
  result "FAILED: app is not running"
  exit 1
fi
result "ok: running after 15 s, no crash"
