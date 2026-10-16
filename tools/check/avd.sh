#!/usr/bin/env bash
# Android emulators like emulator.yml's, for `tools/check/check.sh android-emulator` (#202):
#   phone: API 35, Google APIs (google_apis)   wear: API 34, Wear OS (android-wear)
# x86_64 images on Intel/AMD (Linux needs KVM; Windows: WHPX / Hyper-V), arm64-v8a on Apple Silicon.
#
#   tools/check/avd.sh create <phone|wear>   install the system image and create watch-audio-<target>
#   tools/check/avd.sh start  <phone|wear> [--window]   boot it (headless unless --window), wait for the boot
#   tools/check/avd.sh stop   <phone|wear>   shut it down
#   tools/check/avd.sh delete <phone|wear>   delete the AVD (the system image stays; check.sh clean --all removes it)
#
# Needs ANDROID_HOME (or ANDROID_SDK_ROOT) with cmdline-tools. ANDROID_AVD_HOME moves the AVDs (e.g. to
# an external drive). Linux, macOS and Git Bash on Windows; in WSL run the emulator on Windows
# (Android Studio's Device Manager or Git Bash) and use check.sh --windows-adb. docs/dev/04 「手元での確認」.
set -euo pipefail

command=${1:-}
target=${2:-}
window=0
[ "${3:-}" = "--window" ] && window=1

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[ -n "$command" ] && [ -n "$target" ] || usage

case "$target" in
  phone) api=35; tag=google_apis; device=pixel_6; data_size=3072M ;;
  wear) api=34; tag=android-wear; device=wearos_large_round; data_size=2048M ;;
  *) usage ;;
esac
name=watch-audio-$target

if grep -qi microsoft /proc/version 2>/dev/null; then
  echo "WSL: run the emulator on Windows (Android Studio > Device Manager, or this script in Git Bash), then" \
    "tools/check/check.sh --windows-adb android-emulator (docs/dev/04)." >&2
  exit 1
fi

case "$(uname -m)" in
  arm64 | aarch64) abi=arm64-v8a ;;
  *) abi=x86_64 ;;
esac
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$sdk" ] || { echo "Set ANDROID_HOME to the Android SDK" >&2; exit 1; }

# tool <path without extension>: the path, or its .bat / .exe on Windows.
tool() {
  local p
  for p in "$1" "$1.bat" "$1.exe"; do [ -f "$p" ] && { echo "$p"; return; }; done
  echo "$1 not found (install it with Android Studio's SDK Manager)" >&2
  exit 1
}
image="system-images;android-$api;$tag;$abi"

serial_of() {
  local s
  for s in $(adb devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1 }'); do
    [ "$(adb -s "$s" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)" = "$name" ] && { echo "$s"; return; }
  done
}

case "$command" in
  create)
    sdkmanager=$(tool "$sdk/cmdline-tools/latest/bin/sdkmanager")
    avdmanager=$(tool "$sdk/cmdline-tools/latest/bin/avdmanager")
    yes | "$sdkmanager" --licenses > /dev/null 2>&1 || true
    "$sdkmanager" --install emulator platform-tools "$image"
    echo no | "$avdmanager" create avd -n "$name" -k "$image" -d "$device" --force
    config="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$name.avd/config.ini"
    # Room for the speech models (~160 MB, twice while they are copied into the app), as in CI.
    if [ -f "$config" ]; then
      grep -v '^disk.dataPartition.size=' "$config" > "$config.tmp" || true
      echo "disk.dataPartition.size=$data_size" >> "$config.tmp"
      mv "$config.tmp" "$config"
    fi
    echo "Created $name ($image). Start it: tools/check/avd.sh start $target"
    ;;
  start)
    emulator=$(tool "$sdk/emulator/emulator")
    if [ -n "$(serial_of)" ]; then echo "$name is already running ($(serial_of))"; exit 0; fi
    args=(-avd "$name" -no-snapshot -no-boot-anim -camera-back none)
    [ $window = 1 ] || args+=(-no-window)
    log="${TMPDIR:-/tmp}/$name.log"
    nohup "$emulator" "${args[@]}" > "$log" 2>&1 &
    echo "Booting $name (log: $log) ..."
    for _ in $(seq 1 300); do
      serial=$(serial_of || true)
      if [ -n "$serial" ] && [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        # As the emulator runner's disable-animations in CI.
        for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
          adb -s "$serial" shell settings put global "$setting" 0
        done
        echo "$name is up: $serial"
        exit 0
      fi
      sleep 2
    done
    echo "$name did not boot within 10 minutes (see $log)" >&2
    exit 1
    ;;
  stop)
    serial=$(serial_of || true)
    [ -n "$serial" ] || { echo "$name is not running"; exit 0; }
    adb -s "$serial" emu kill
    ;;
  delete)
    avdmanager=$(tool "$sdk/cmdline-tools/latest/bin/avdmanager")
    "$avdmanager" delete avd -n "$name"
    ;;
  *) usage ;;
esac
