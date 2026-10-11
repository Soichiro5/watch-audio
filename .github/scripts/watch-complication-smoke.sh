#!/usr/bin/env bash
# Complication check (#41) on an Apple Watch simulator: the widget extension is embedded in the
# watch app, and opening the complication's tap URL (watchaudio://record) launches the app and
# makes the recorder start (or explain why it could not: charging, permission, no microphone).
# A watch face cannot be configured from the command line, so the complication itself is shown
# by the rendered previews of ComplicationDisplayTests; this script covers the tap path.
#
# Usage: watch-complication-smoke.sh <path/to/WatchAudio.app> <bundle id>
# Env: OUT_DIR (default simulator-smoke)
# Leaves watchos-complication-{stopped,tap}.png, the recorder's diagnostics.csv and the shared
# status in $OUT_DIR, appends the result to the job summary, and uninstalls the app at the end.
set -uo pipefail

app=$1
bundle_id=$2
out_dir=${OUT_DIR:-simulator-smoke}
mkdir -p "$out_dir"

summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$1" >> "$GITHUB_STEP_SUMMARY"; fi
  echo "$1"
}

[ -d "$app" ] || { echo "::error::$app not found"; exit 1; }
appex="$app/PlugIns/WatchAudioWidget.appex"
if [ ! -d "$appex" ]; then
  find "$app" -maxdepth 2
  echo "::error::widget extension not embedded ($appex missing)"
  exit 1
fi
extension_point=$(/usr/libexec/PlistBuddy -c "Print :NSExtension:NSExtensionPointIdentifier" "$appex/Info.plist" 2>/dev/null || echo "?")
extension_id=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$appex/Info.plist" 2>/dev/null || echo "?")
echo "Widget extension: $extension_id ($extension_point)"

udid=$("$(dirname "$0")/simulator-udid.sh" watchOS "Apple Watch") || exit 1
device_name=$(xcrun simctl list devices --json | jq -r --arg u "$udid" '.devices[][] | select(.udid == $u) | .name')
echo "Simulator: $device_name ($udid)"

SECONDS=0
xcrun simctl boot "$udid" 2>/dev/null || true # already booted is fine
xcrun simctl bootstatus "$udid" -b
echo "Timing: waited ${SECONDS}s for the boot"
xcrun simctl uninstall "$udid" "$bundle_id" 2>/dev/null || true # start from "stopped"
xcrun simctl install "$udid" "$app"
xcrun simctl privacy "$udid" grant microphone "$bundle_id" || echo "could not pre-grant the microphone"

data=$(xcrun simctl get_app_container "$udid" "$bundle_id" data)
csv="$data/Library/Application Support/WatchAudio/diagnostics.csv"

# 1. Normal launch: nothing starts by itself (stopped).
xcrun simctl launch "$udid" "$bundle_id"
sleep 8
xcrun simctl io "$udid" screenshot "$out_dir/watchos-complication-stopped.png" || true
xcrun simctl terminate "$udid" "$bundle_id" 2>/dev/null || true
sleep 2
before=$( [ -f "$csv" ] && wc -l < "$csv" || echo 0 )

# 2. Tap: the complication's widgetURL. watchOS simulators refuse `simctl openurl` for app URL
# schemes (LSApplicationWorkspaceErrorDomain 115), so fall back to the Debug-only launch argument
# that hands the same URL to the same handler as onOpenURL.
status=0
if xcrun simctl openurl "$udid" "watchaudio://record"; then
  tap_path="simctl openurl"
else
  echo "simctl openurl failed; launching with -complicationURL instead"
  xcrun simctl launch "$udid" "$bundle_id" -complicationURL "watchaudio://record"
  tap_path="launch argument -complicationURL (Debug)"
fi
sleep 12
xcrun simctl io "$udid" screenshot "$out_dir/watchos-complication-tap.png" || true

[ -f "$csv" ] && cp "$csv" "$out_dir/watchos-complication-diagnostics.csv"
# The status the app wrote for the complication (App Group suite; a plain suite when unsigned).
for plist in "$data"/Library/Preferences/*.plist; do
  [ -f "$plist" ] || continue
  { echo "== $(basename "$plist")"; plutil -p "$plist"; } >> "$out_dir/watchos-complication-defaults.txt" 2>&1
done
status_json=$(for plist in "$data"/Library/Preferences/*.plist; do
  plutil -extract recorderStatus raw -o - "$plist" 2>/dev/null | base64 -D 2>/dev/null && break
done)
echo "Shared status: ${status_json:-none}"

after_tap=$( [ -f "$csv" ] && tail -n +"$((before + 1))" "$csv" || true )
echo "Recorder events after the tap:"
echo "$after_tap"
start_event=$(echo "$after_tap" | awk -F, '$4 ~ /^(start|resume|start_blocked|start_failed|permission_denied)$/ { print $4 ($5 == "" ? "" : " (" $5 ")"); exit }')

if [ -z "$start_event" ]; then
  echo "::error::opening watchaudio://record ($tap_path) did not make the recorder start"
  status=1
fi

if [ $status -eq 0 ]; then
  summary "- watchOS complication: **OK**. \`$extension_id\` ($extension_point) is embedded; opening \`watchaudio://record\` on $device_name (via $tap_path) launched the app and the recorder logged \`$start_event\`. Shared status: \`${status_json:-none}\` (screenshots watchos-complication-*.png in simulator-smoke)."
else
  summary "- watchOS complication: **FAILED** (see watchos-complication-* in simulator-smoke)."
fi

xcrun simctl terminate "$udid" "$bundle_id" 2>/dev/null || true
# The launch smoke step expects a fresh install that does not want to record.
xcrun simctl uninstall "$udid" "$bundle_id" 2>/dev/null || true
# The simulator stays booted for the launch smoke step (#92).
exit $status
