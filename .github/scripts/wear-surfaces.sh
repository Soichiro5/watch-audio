#!/usr/bin/env bash
# Wear OS emulator evidence for the tile and the complication (#41), run by emulator.yml after the
# smoke check. Uses the Wear OS debug surface broadcasts (developer.android.com/training/wearables/tiles/debug):
#
#   1. stopped: shows the tile (and sets the complication on the current watch face) -> screenshots,
#   2. taps the tile's button -> the app opens and starts recording -> screenshot,
#   3. recording: shows the tile and the watch face again -> screenshots.
#
# Evidence only: system UI broadcasts differ between emulator images, so a step that does not work
# is reported (WEAR_SURFACE_CHECK ... ok=false) instead of failing the job. Screenshots land in
# /data/local/tmp/watchaudio-screenshots (pulled by the workflow).
set -uo pipefail

PKG=app.watchaudio
TILE=$PKG/app.watchaudio.wear.tile.RecorderTileService
COMPLICATION=$PKG/app.watchaudio.wear.tile.RecorderComplicationService
SHOTS=/data/local/tmp/watchaudio-screenshots
OUT=wear-surfaces
mkdir -p "$OUT"

log() { echo "$*" | tee -a "$OUT/summary.txt"; }
shot() {
  sleep 3
  adb shell screencap -p "$SHOTS/$1.png"
  log "SCREENSHOT name=$1.png bytes=$(adb shell stat -c %s "$SHOTS/$1.png" 2>/dev/null | tr -d '\r')"
}
check() { log "WEAR_SURFACE_CHECK step=$1 ok=$2 ${3:-}"; }
broadcast() { adb shell am broadcast -a "$1" "${@:2}" 2>&1 | tr -d '\r' | tail -1; }

show_tile() {
  local result index
  result=$(broadcast com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component "$TILE")
  index=$(echo "$result" | sed -n 's/.*Index=\[\([0-9]*\)\].*/\1/p')
  check "add-tile($1)" "$([[ $result == *result=1* ]] && echo true || echo false)" "result='$result'"
  result=$(broadcast com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index "${index:-0}")
  check "show-tile($1)" "$([[ $result == *result=1* ]] && echo true || echo false)" "result='$result'"
}

# The watch face is a wallpaper service; complications are set on one of its slots.
set_complication() {
  local face result slot
  face=$(adb shell dumpsys wallpaper | tr -d '\r' | sed -n 's/.*mWallpaperComponent=ComponentInfo{\([^}]*\)}.*/\1/p' | head -1)
  log "watch face: ${face:-unknown}"
  [ -n "$face" ] || { check set-complication false "no watch face found"; return; }
  for slot in 0 1 2 3 100 101 102 103; do
    # type 3 = SHORT_TEXT (ComplicationType wire value)
    result=$(broadcast com.google.android.wearable.app.DEBUG_SURFACE --es operation set-complication \
      --ecn component "$COMPLICATION" --ecn watchface "$face" --ei slot "$slot" --ei type 3)
    log "set-complication slot=$slot: $result"
    if [[ $result == *result=1* ]]; then check set-complication true "slot=$slot face=$face"; return; fi
  done
  check set-complication false "face=$face"
}

go_home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }

# Taps the tile button ("Start"/"Resume") found in the view hierarchy, else the lower middle of the screen.
tap_tile_button() {
  local xy size
  adb shell uiautomator dump /data/local/tmp/wear-ui.xml > /dev/null 2>&1
  adb pull /data/local/tmp/wear-ui.xml "$OUT/tile-ui.xml" > /dev/null 2>&1
  xy=$(python3 - "$OUT/tile-ui.xml" <<'EOF'
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    sys.exit(0)
for node in re.finditer(r'<node [^>]*>', xml):
    attrs = node.group(0)
    if re.search(r'text="(Start|Resume|Open)"', attrs) or re.search(r'content-desc="(Start|Resume|Open)"', attrs):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
EOF
)
  if [ -z "$xy" ]; then
    size=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)/\1 \2/p' | tail -1)
    xy=$(echo "$size" | awk '{print int($1/2), int($2*0.68)}')
    log "tile button not found in the view hierarchy; tapping at $xy"
  else
    log "tile button at $xy"
  fi
  adb shell input tap $xy
}

recording() { adb shell dumpsys activity services "$PKG" | grep -q "RecordingService"; }

adb shell mkdir -p "$SHOTS"
# The recorder pauses while charging, and the emulator reports AC power by default.
adb shell dumpsys battery unplug
adb shell dumpsys battery set level 80
# So that the tap goes straight to recording instead of the permission dialogs.
for permission in RECORD_AUDIO POST_NOTIFICATIONS BODY_SENSORS; do adb shell pm grant "$PKG" "android.permission.$permission" || true; done
adb shell am force-stop "$PKG"

# 1. Stopped.
set_complication
go_home
shot wear-complication-stopped
show_tile stopped
shot wear-tile-stopped

# 2. Tap the tile.
tap_tile_button
sleep 8
shot wear-tile-tap-opens-app
if recording; then check tile-tap-starts-recording true; else check tile-tap-starts-recording false "RecordingService not running"; fi
adb shell dumpsys activity services "$PKG" | tr -d '\r' | grep -E "ServiceRecord|isForeground" > "$OUT/services.txt" || true

# 3. Recording.
go_home
shot wear-complication-recording
show_tile recording
shot wear-tile-recording

adb logcat -d > "$OUT/logcat-wear-surfaces.txt" || true
adb shell dumpsys battery reset || true
exit 0
