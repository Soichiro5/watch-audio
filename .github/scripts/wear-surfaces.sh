#!/usr/bin/env bash
# Wear OS emulator evidence for the tile and the complication (#41), run by emulator.yml after the
# smoke check. Uses the Wear OS debug surface broadcasts (developer.android.com/training/wearables/tiles/debug):
#
#   0. fresh install, start tapped in the app: the BODY_SENSORS explanation, the system dialog,
#      declined -> still recording, with the "not paused when taken off" note (#161) -> screenshots,
#   1. stopped: shows the tile (and sets the complication on the current watch face) -> screenshots,
#   2. taps the tile's button -> the app opens and starts recording -> screenshot,
#   3. recording: shows the tile and the watch face again -> screenshots.
#
# Evidence only: system UI broadcasts differ between emulator images, so a step that does not work
# is reported (WEAR_SURFACE_CHECK ... ok=false) instead of failing the job. Each screenshot is
# pulled into screenshots-wear/ right away (the emulator has gone offline at the end of a run).
#
# First CI run (38047480914) showed: the Wear system UI still shows "Starting…" for a while after
# boot_completed, so the script waits for it; the default watch face (Orbita) has its complication
# slots disabled, so the script tries the installed watch faces in turn.
set -uo pipefail

PKG=app.watchaudio
TILE=$PKG/app.watchaudio.wear.tile.RecorderTileService
COMPLICATION=$PKG/app.watchaudio.wear.tile.RecorderComplicationService
SHOTS=/data/local/tmp/watchaudio-screenshots
OUT=wear-surfaces
LOCAL_SHOTS=screenshots-wear
mkdir -p "$OUT" "$LOCAL_SHOTS"

log() { echo "$*" | tee -a "$OUT/summary.txt"; }
shot() {
  sleep 3
  adb shell screencap -p "$SHOTS/$1.png"
  adb pull "$SHOTS/$1.png" "$LOCAL_SHOTS/$1.png" > /dev/null 2>&1
  log "SCREENSHOT name=$1.png bytes=$(stat -c %s "$LOCAL_SHOTS/$1.png" 2>/dev/null || echo missing)"
}

# Dumps the view hierarchy to $OUT/$1.xml.
dump_ui() {
  adb shell uiautomator dump /data/local/tmp/wear-ui.xml > /dev/null 2>&1
  adb pull /data/local/tmp/wear-ui.xml "$OUT/$1.xml" > /dev/null 2>&1
}

# Waits (up to 5 min) until the system UI has finished its "Starting…" screen.
wait_for_system_ui() {
  local i
  for i in $(seq 1 60); do
    adb shell input keyevent KEYCODE_WAKEUP
    dump_ui ready-check
    if [ -s "$OUT/ready-check.xml" ] && ! grep -q "starting_screen" "$OUT/ready-check.xml"; then
      check system-ui-ready true "after_s=$((i * 5))"
      return
    fi
    sleep 5
  done
  check system-ui-ready false "still starting after 300 s"
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

current_face() {
  adb shell dumpsys wallpaper | tr -d '\r' | sed -n 's/.*mWallpaperComponent=ComponentInfo{\([^}]*\)}.*/\1/p' | head -1
}

# Tries the slots of watch face $1; returns 0 once one accepts the complication.
try_slots() {
  local face=$1 result slot
  for slot in 0 1 2 3 4 5 6 7 100 101 102 103; do
    # type 3 = SHORT_TEXT (ComplicationType wire value)
    result=$(broadcast com.google.android.wearable.app.DEBUG_SURFACE --es operation set-complication \
      --ecn component "$COMPLICATION" --ecn watchface "$face" --ei slot "$slot" --ei type 3)
    log "set-complication face=$face slot=$slot: $result"
    if [[ $result == *result=1* ]]; then check set-complication true "slot=$slot face=$face"; return 0; fi
    # Past the last slot of this face.
    [[ $result == *"out of range"* ]] && [ "$slot" -lt 100 ] && return 1
  done
  return 1
}

# The watch face is a wallpaper service. The current one first, then every installed watch face
# (made current with the debug broadcast set-watchface) until one has an enabled slot.
set_complication() {
  local face candidate result
  face=$(current_face)
  log "watch face: ${face:-unknown}"
  [ -n "$face" ] && try_slots "$face" && return
  adb shell cmd package query-services --brief -a android.service.wallpaper.WallpaperService | tr -d '\r' | grep "/" > "$OUT/watch-faces.txt"
  log "installed watch faces: $(tr '\n' ' ' < "$OUT/watch-faces.txt")"
  # fd 3: adb shell would otherwise read the rest of the list from stdin.
  while read -r candidate <&3; do
    # Expand the short form pkg/.Class to pkg/pkg.Class, like dumpsys prints it.
    [[ $candidate == */.* ]] && candidate="${candidate%%/*}/${candidate%%/*}${candidate#*/}"
    [ "$candidate" = "$face" ] && continue
    result=$(broadcast com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --ecn component "$candidate")
    log "set-watchface $candidate: $result"
    sleep 5
    [ "$(current_face)" = "$candidate" ] || continue
    try_slots "$candidate" && return
  done 3< "$OUT/watch-faces.txt"
  check set-complication false "no watch face with an enabled slot"
}

go_home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }

# Prints the center "x y" of the first node in the view dump $1 whose text, content-desc or
# resource-id matches the regex $2 (whole attribute value); prints nothing if there is none.
node_center() {
  python3 - "$1" "$2" <<'EOF'
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    sys.exit(0)
for node in re.finditer(r'<node [^>]*>', xml):
    attrs = node.group(0)
    if re.search(r'(text|content-desc|resource-id)="(' + sys.argv[2] + ')"', attrs):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
EOF
}

# Lower middle of the screen, where the tile button and the app's edge button sit.
lower_middle() {
  adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)/\1 \2/p' | tail -1 | awk '{print int($1/2), int($2*0.68)}'
}

# Taps the node matching the regex $2 in a fresh view dump saved as $1.xml, else the lower middle
# of the screen (returns 1 then).
tap_node() {
  local xy
  dump_ui "$1"
  xy=$(node_center "$OUT/$1.xml" "$2")
  if [ -z "$xy" ]; then
    xy=$(lower_middle)
    log "'$2' not found in $1.xml; tapping at $xy"
    adb shell input tap $xy
    return 1
  fi
  log "'$2' at $xy"
  adb shell input tap $xy
}

# Taps the tile button (action_start / action_resume / action_open in apps/wear strings.xml: 録音開始 / 再開 /
# 開く, #144) found in the view hierarchy, else the lower middle of the screen.
tap_tile_button() { tap_node tile-ui "録音開始|再開|開く" || true; }

recording() { adb shell dumpsys activity services "$PKG" | grep -q "RecordingService"; }

# 0. #161: a fresh install explains BODY_SENSORS before the system asks for it, and recording goes
# on when the wearer declines (strings body_sensors_* / off_body_detection_off in apps/wear).
body_sensors_flow() {
  local granted
  adb shell pm clear "$PKG" > /dev/null
  adb shell pm revoke "$PKG" android.permission.BODY_SENSORS 2> /dev/null || true
  # Microphone and notifications granted: the start tap goes straight to recording, then the explanation.
  adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb shell am start -W -n "$PKG/app.watchaudio.wear.MainActivity" > /dev/null
  sleep 3
  tap_node app-stopped "録音開始" || true
  sleep 5
  shot wear-body-sensors-explanation
  dump_ui body-sensors-explanation
  if grep -q "身体センサーの許可" "$OUT/body-sensors-explanation.xml"; then
    check body-sensors-explanation-before-request true
  else
    check body-sensors-explanation-before-request false "explanation text not on screen"
  fi
  if recording; then check body-sensors-recording-while-explaining true; else check body-sensors-recording-while-explaining false; fi
  tap_node body-sensors-continue "次へ" || true
  sleep 3
  shot wear-body-sensors-system-dialog
  dump_ui body-sensors-system-dialog
  # Decline: the dialog's deny button (resource id or label), else back, which also answers "not granted".
  if ! tap_node body-sensors-deny ".*permission_deny.*|Don.{1,6}t allow|Deny|許可しない"; then adb shell input keyevent KEYCODE_BACK; fi
  sleep 3
  shot wear-body-sensors-declined
  dump_ui body-sensors-declined
  granted=$(adb shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/.*android.permission.BODY_SENSORS: granted=\([a-z]*\).*/\1/p' | head -1)
  check body-sensors-declined "$([ "$granted" = false ] && echo true || echo false)" "granted=${granted:-unknown}"
  if recording; then check body-sensors-declined-still-records true; else check body-sensors-declined-still-records false "RecordingService not running"; fi
  if grep -q "身体センサー未許可" "$OUT/body-sensors-declined.xml"; then
    check body-sensors-declined-note true
  else
    check body-sensors-declined-note false "note not on screen"
  fi
  # Back to a fresh install for the tile checks.
  adb shell pm clear "$PKG" > /dev/null
}

adb shell mkdir -p "$SHOTS"
wait_for_system_ui
# The recorder pauses while charging, and the emulator reports AC power by default.
adb shell dumpsys battery unplug
adb shell dumpsys battery set level 80
body_sensors_flow
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
dump_ui after-tile-tap
adb logcat -d -s RecordingService:* ChunkRecorder:* AndroidRuntime:E > "$OUT/recorder-log.txt" 2>/dev/null || true
if recording; then check tile-tap-starts-recording true; else check tile-tap-starts-recording false "RecordingService not running"; fi
adb shell dumpsys activity services "$PKG" | tr -d '\r' | grep -E "ServiceRecord|isForeground" > "$OUT/services.txt" || true

# 3. Recording.
go_home
shot wear-complication-recording
show_tile recording
shot wear-tile-recording

adb logcat -d > "$OUT/logcat-wear-surfaces.txt" || true
exit 0
