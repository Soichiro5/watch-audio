#!/usr/bin/env bash
# Launch smoke test on an Apple simulator (#7): boot a simulator (or wait for its boot), install an already built
# .app, launch it, wait, then fail if the process is gone or a crash report for it appeared.
# Leaves a screenshot and the app's log in $OUT_DIR and appends a line to the job summary.
#
# Usage: simulator-smoke.sh <iOS|watchOS> <device name prefix> <path/to/App.app> <bundle id>
#   e.g. simulator-smoke.sh iOS iPhone build/DerivedData/Build/Products/Debug-iphonesimulator/WatchAudio.app app.watchaudio
# Env: SMOKE_SECONDS (default 15), OUT_DIR (default simulator-smoke)
set -euo pipefail

platform=$1
device_prefix=$2
app=$3
bundle_id=$4
wait_seconds=${SMOKE_SECONDS:-15}
out_dir=${OUT_DIR:-simulator-smoke}
label=$(echo "$platform" | tr '[:upper:]' '[:lower:]')
mkdir -p "$out_dir"

[ -d "$app" ] || { echo "::error::$app not found (build step failed or product path changed)"; exit 1; }

udid=$("$(dirname "$0")/simulator-udid.sh" "$platform" "$device_prefix")
device_name=$(xcrun simctl list devices --json | jq -r --arg u "$udid" '.devices[][] | select(.udid == $u) | .name')
echo "Simulator: $device_name ($udid)"

# Usually booted in the background by boot-simulators.sh; booting here also works (slower).
SECONDS=0
xcrun simctl boot "$udid" 2>/dev/null || true # already booted (or booting) is fine
xcrun simctl bootstatus "$udid" -b
boot_wait=$SECONDS
SECONDS=0
xcrun simctl install "$udid" "$app"
echo "Timing: waited ${boot_wait}s for the boot, install took ${SECONDS}s"

marker=$(mktemp)
crash_dir="$HOME/Library/Logs/DiagnosticReports"
launch_output=$(xcrun simctl launch "$udid" "$bundle_id")
echo "$launch_output"
pid=${launch_output##*: }
sleep "$wait_seconds"

xcrun simctl io "$udid" screenshot "$out_dir/$label.png" || true
xcrun simctl spawn "$udid" log show --last 2m --style compact \
  --predicate "processIdentifier == $pid OR process == \"WatchAudio\"" > "$out_dir/$label-log.txt" 2>&1 || true

status=0
if ps -p "$pid" > /dev/null; then
  echo "Process $pid is still running after ${wait_seconds}s"
else
  echo "::error::$platform app ($bundle_id) exited within ${wait_seconds}s"
  status=1
fi

# .ips (JSON header line) or legacy .crash reports naming exactly this bundle id.
id_pattern=${bundle_id//./\\.}
crashes=$(find "$crash_dir" -newer "$marker" -type f \( -name '*.ips' -o -name '*.crash' \) 2>/dev/null \
  | xargs grep -lE "\"bundleID\" *: *\"$id_pattern\"|^Identifier: +$id_pattern\$" 2>/dev/null || true)
if [ -n "$crashes" ]; then
  echo "::error::crash report(s) for $bundle_id: $crashes"
  cp $crashes "$out_dir/" || true
  status=1
else
  echo "No crash report for $bundle_id"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  if [ $status -eq 0 ]; then
    echo "- $platform launch smoke: **OK**. $bundle_id on $device_name still running after ${wait_seconds}s (pid $pid), no crash report." >> "$GITHUB_STEP_SUMMARY"
  else
    echo "- $platform launch smoke: **FAILED** for $bundle_id on $device_name (see $label-log.txt)." >> "$GITHUB_STEP_SUMMARY"
  fi
fi

# The simulator stays booted for the next steps (#92).
xcrun simctl terminate "$udid" "$bundle_id" 2>/dev/null || true
exit $status
