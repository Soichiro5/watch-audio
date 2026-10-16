# shellcheck shell=bash
# `check.sh doctor` (tools, disk space, what each stage needs), the disk check before a stage, and
# `check.sh clean` (what the checks created; with --all also the documented caches). Local only:
# no workflow calls these. Sourced by check.sh. Sizes in KiB.
#
# First-run sizes: "measured" ones were measured on Linux (2026-10, this repository at #202);
# the others are estimates (marked so) that docs/dev/04 「手元での確認」 repeats.

gradle_home() { printf '%s' "${GRADLE_USER_HOME:-$HOME/.gradle}"; }
konan_home() { printf '%s' "${KONAN_DATA_DIR:-$HOME/.konan}"; }
avd_home() { printf '%s' "${ANDROID_AVD_HOME:-$HOME/.android/avd}"; }
android_sdk() { printf '%s' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$CHECK_ROOT/local.properties" 2>/dev/null)}}"; }
models_dir() { if [ -n "${WATCHAUDIO_MODELS_DIR:-}" ]; then printf '%s' "$WATCHAUDIO_MODELS_DIR"; else printf '%s' "$CHECK_ROOT/tools/harness/models"; fi; }
derived_data_dir() {
  case "${DERIVED_DATA:-build/DerivedData}" in
    /*) printf '%s' "$DERIVED_DATA" ;;
    *) printf '%s/%s' "$CHECK_ROOT" "${DERIVED_DATA:-build/DerivedData}" ;;
  esac
}
xcode_app() {
  local dev
  dev=$(xcode-select -p 2>/dev/null) || return 0
  case "$dev" in */Contents/Developer) printf '%s' "${dev%/Contents/Developer}" ;; esac
}

GB=1048576
MB=1024

# Components: id|what|path|first-run KiB|source of the size|grows (1: counts est - present)
components() {
  local konan_est=$((9 * GB / 10)) konan_src="measured (Linux)"
  if [ "$CHECK_OS" = "macos" ]; then konan_est=$((2 * GB)); konan_src="estimate"; fi
  local repo_est=$((6 * GB))
  [ "${CHECK_QUICK:-0}" = "1" ] && repo_est=$((2 * GB))
  cat <<EOF
gradle|Gradle caches (dependencies, wrapper, build cache) of this project|$(gradle_home)|$((4 * GB))|estimate|0
konan|Kotlin/Native toolchain (KONAN_DATA_DIR)|$(konan_home)|$konan_est|$konan_src|0
android-sdk|Android SDK: platforms, build-tools, platform-tools|$(android_sdk)|$((1 * GB))|measured (Linux)|0
repo-build|build outputs of fast (all modules, APKs, benchmark DB ~0.7 GB)|$CHECK_ROOT/build|$repo_est|estimate|1
node-modules|tools/agent-bridge/node_modules|$CHECK_ROOT/tools/agent-bridge/node_modules|$((60 * MB))|measured (Linux)|0
jni-temp|NDK + sherpa-onnx build tree for the JNI library (removed after the build)|$CHECK_OUT/sherpa-onnx-aar|$((5 * GB))|estimate|1
jni-cache|sherpa-onnx JNI library without TTS|$WATCHAUDIO_CACHE_DIR/sherpa-onnx-jni|$((20 * MB))|measured (CI)|0
models|ASR + embedding models and reference audio (WATCHAUDIO_MODELS_DIR)|$(models_dir)|$((500 * MB))|estimate|0
emulator|Android emulator package|$(android_sdk)/emulator|$((1 * GB))|estimate|0
image-phone|system image android-35 google_apis|$(android_sdk)/system-images/android-35/google_apis|$((3 * GB))|estimate|0
image-wear|system image android-34 android-wear|$(android_sdk)/system-images/android-34/android-wear|$((2 * GB))|estimate|0
avd-phone|AVD watch-audio-phone (ANDROID_AVD_HOME)|$(avd_home)/watch-audio-phone.avd|$((7 * GB / 2))|estimate|1
avd-wear|AVD watch-audio-wear|$(avd_home)/watch-audio-wear.avd|$((2 * GB))|estimate|1
xcode|Xcode 26 (without extra platforms)|$(xcode_app)|$((12 * GB))|estimate|0
ios-runtime|iOS simulator runtime|/Library/Developer/CoreSimulator|$((17 * GB / 2))|estimate|0
watch-runtime|watchOS simulator runtime|/Library/Developer/CoreSimulator|$((9 * GB / 2))|estimate|0
sim-devices|simulator devices of the ios stage (deleted at the end)|$HOME/Library/Developer/CoreSimulator/Devices|$((3 * GB))|estimate|1
derived-data|Xcode DerivedData of this project (DERIVED_DATA; removed after the run)|$(derived_data_dir)|$((6 * GB))|estimate|1
kn-build|Kotlin/Native outputs in shared/*/build (frameworks, test binaries)|$CHECK_ROOT/shared/ui/build|$((3 * GB))|estimate|1
swiftpm|Swift package cache (ONNX Runtime)|$HOME/Library/Caches/org.swift.swiftpm|$((500 * MB))|estimate|0
EOF
}

stage_components() {
  case "$1" in
    fast)
      echo "gradle konan android-sdk repo-build node-modules jni-cache"
      # The JNI build tree only when the library is not cached yet and the build runs here.
      if { [ "$CHECK_OS" = "linux" ] || [ "$CHECK_OS" = "wsl" ]; } && [ "${CHECK_NO_JNI:-0}" != "1" ] &&
        [ "${CHECK_QUICK:-0}" != "1" ] && [ -z "$(ls -A "$WATCHAUDIO_CACHE_DIR/sherpa-onnx-jni" 2>/dev/null)" ]; then
        echo "jni-temp"
      fi ;;
    harness) echo "gradle android-sdk repo-build models" ;;
    android-emulator)
      echo "gradle android-sdk repo-build models node-modules jni-cache"
      # The emulator runs on Windows when the stage runs in WSL; a connected device needs none.
      if [ "$CHECK_OS" != "wsl" ] && ! android_device_connected; then echo "emulator image-phone image-wear avd-phone avd-wear"; fi ;;
    ios-build) echo "gradle konan xcode derived-data kn-build swiftpm" ;;
    ios) echo "gradle konan xcode derived-data kn-build swiftpm ios-runtime watch-runtime sim-devices node-modules" ;;
    all)
      stage_components fast
      stage_components harness
      stage_components android-emulator
      [ "$CHECK_OS" = "macos" ] && stage_components ios ;;
  esac
}

android_device_connected() {
  have adb && [ -n "$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device"')" ]
}

# present_kib <id> <path>: what is there now (runtimes from simctl; big shared caches only with --measure).
present_kib() {
  local id=$1 path=$2
  case "$id" in
    ios-runtime | watch-runtime)
      [ "$CHECK_OS" = "macos" ] && have xcrun || { echo 0; return; }
      local platform=iOS
      [ "$id" = watch-runtime ] && platform=watchOS
      xcrun simctl runtime list -j 2>/dev/null | python3 -c '
import json, sys
total = sum(r.get("sizeBytes", 0) for r in json.load(sys.stdin).values() if ".SimRuntime." + sys.argv[1] + "-" in r.get("runtimeIdentifier", ""))
print(total // 1024)' "$platform" 2>/dev/null || echo 0
      return ;;
    repo-build)
      # Every build/ directory of the repository (modules and the root).
      find "$CHECK_ROOT" \( -name .git -o -name node_modules -o -name .claude \) -prune -o \
        -type d -name build -prune -print 2>/dev/null | while IFS= read -r p; do size_kib "$p"; done |
        awk '{ s += $1 } END { print s + 0 }'
      return ;;
    gradle | xcode | sim-devices)
      if [ -e "$path" ] && [ -n "$path" ]; then
        if [ "${DOCTOR_MEASURE:-0}" = "1" ]; then size_kib "$path"; else echo -1; fi
      else
        echo 0
      fi
      return ;;
  esac
  [ -n "$path" ] && size_kib "$path" || echo 0
}

component_field() { components | awk -F'|' -v id="$1" -v f="$2" '$1 == id { print $f }'; }

# needed_lines <stage>: "<mount> <KiB still needed> <id>" for each component of the stage.
needed_lines() {
  local id path est grows present need mount
  for id in $(stage_components "$1"); do
    path=$(component_field "$id" 3)
    est=$(component_field "$id" 4)
    grows=$(component_field "$id" 6)
    [ -n "$path" ] || path=$CHECK_ROOT
    present=$(present_kib "$id" "$path")
    if [ "$present" = "-1" ]; then
      need=0
    elif [ "$grows" = "1" ]; then
      need=$((est - present))
      [ "$need" -lt 0 ] && need=0
    elif [ "$present" -gt 0 ]; then
      need=0
    else
      need=$est
    fi
    mount=$(mount_of "$path")
    echo "${mount:-/} $need $id"
  done
}

# disk_fits <stage>: prints the estimate per disk; false if a disk lacks the space.
disk_fits() {
  local lines ok=0 mount need free ids
  lines=$(needed_lines "$1" | sort -u)
  echo "Disk space this stage may still need (first-run estimates, docs/dev/04 「手元での確認」):"
  for mount in $(printf '%s\n' "$lines" | awk '{ print $1 }' | sort -u); do
    need=$(printf '%s\n' "$lines" | awk -v m="$mount" '$1 == m { s += $2 } END { print s + 0 }')
    ids=$(printf '%s\n' "$lines" | awk -v m="$mount" '$1 == m && $2 > 0 { printf "%s%s", sep, $3; sep = ", " }')
    free=$(free_kib "$mount")
    printf '  %-24s needs ~%-9s free %-9s %s\n' "$mount" "$(human_kib "$need")" "$(human_kib "${free:-0}")" "${ids:+($ids)}"
    if [ "${free:-0}" -lt "$need" ]; then
      echo "  WARNING: $mount may run out of space for this stage."
      ok=1
    fi
  done
  return $ok
}

tool_version() {
  local name=$1
  shift
  if have "$name"; then
    printf '  %-12s %s\n' "$name" "$("$@" 2>&1 | grep -v '^Picked up' | head -n 1)"
  else
    printf '  %-12s %s\n' "$name" "(not found)"
  fi
}

doctor_command() {
  local stage="" a
  for a in "$@"; do
    case "$a" in
      --measure) DOCTOR_MEASURE=1 ;;
      *) stage=$a ;;
    esac
  done
  echo "OS: $CHECK_OS ($CHECK_ARCH), bash $BASH_VERSION"
  [ "$CHECK_OS" = "wsl" ] && have wslinfo && echo "WSL networking: $(wslinfo --networking-mode 2>/dev/null)"
  echo "Tools:"
  tool_version java java -version
  tool_version node node --version
  tool_version npm npm --version
  tool_version python3 python3 --version
  tool_version adb adb version
  [ "$CHECK_OS" = "wsl" ] && tool_version adb.exe adb.exe version
  tool_version ffmpeg ffmpeg -version
  tool_version cmake cmake --version
  tool_version xcodebuild xcodebuild -version
  tool_version xcodegen xcodegen --version
  echo "Locations:"
  printf '  %-26s %s\n' "repository" "$CHECK_ROOT" \
    "GRADLE_USER_HOME" "$(gradle_home)" \
    "KONAN_DATA_DIR" "$(konan_home)" \
    "Android SDK" "$(android_sdk)" \
    "ANDROID_AVD_HOME" "$(avd_home)" \
    "WATCHAUDIO_MODELS_DIR" "$(models_dir)" \
    "WATCHAUDIO_CACHE_DIR" "$WATCHAUDIO_CACHE_DIR" \
    "DERIVED_DATA" "$(derived_data_dir)"
  echo
  echo "Components (present = measured now; -: present, not measured, use --measure; first run = what a first run downloads or writes):"
  printf '  %-14s %-10s %-10s %-17s %s\n' "component" "present" "first run" "size source" "path"
  local id what path est src grows present shown
  while IFS='|' read -r id what path est src grows; do
    case "$id" in xcode | ios-runtime | watch-runtime | sim-devices | derived-data | kn-build | swiftpm)
      [ "$CHECK_OS" = "macos" ] || continue ;;
    esac
    present=$(present_kib "$id" "${path:-/nonexistent}")
    case "$present" in
      -1) shown="-" ;;
      0) shown="none" ;;
      *) shown=$(human_kib "$present") ;;
    esac
    printf '  %-14s %-10s %-10s %-17s %s\n' "$id" "$shown" "$(human_kib "$est")" "$src" "${path:-?}"
  done <<EOF
$(components)
EOF
  echo
  local st
  for st in ${stage:-fast harness android-emulator ios-build ios}; do
    case "$st" in ios | ios-build) [ "$CHECK_OS" = "macos" ] || continue ;; esac
    echo "== $st"
    disk_fits "$st" || true
  done
}

# --- clean -------------------------------------------------------------------------------------

# Paths that the checks write into the repository (outside build/ directories).
REPO_LEFTOVERS="release-apk release-test-apks scenario-logs release-asr wear-surfaces screenshots-phone screenshots-wear
logcat-release.txt logcat.txt logcat-device-tests.txt launch-checks.txt device-tests-failed simulator-smoke agent-access-logs
release-checks.txt audio-devices.txt apps/ios/WatchAudio.xcodeproj tools/agent-bridge/node_modules tools/agent-bridge/dist"

# Lines "<path>" of what `clean` removes (existing paths only).
clean_targets() {
  local all=$1 p
  find "$CHECK_ROOT" \( -name .git -o -name node_modules -o -name .claude \) -prune -o \
    -type d \( -name build -o -name .gradle -o -name .kotlin \) -prune -print 2>/dev/null
  for p in $REPO_LEFTOVERS; do [ -e "$CHECK_ROOT/$p" ] && echo "$CHECK_ROOT/$p"; done
  ls -d "$CHECK_ROOT"/xcodebuild-*.log 2>/dev/null || true
  [ -e "$CHECK_ROOT/tools/harness/models" ] && echo "$CHECK_ROOT/tools/harness/models"
  [ -n "${WATCHAUDIO_MODELS_DIR:-}" ] && [ -d "$WATCHAUDIO_MODELS_DIR" ] && echo "$WATCHAUDIO_MODELS_DIR"
  [ -n "${DERIVED_DATA:-}" ] && [ -d "$(derived_data_dir)" ] && derived_data_dir && echo
  [ -d "$WATCHAUDIO_CACHE_DIR" ] && echo "$WATCHAUDIO_CACHE_DIR"
  for p in "$(avd_home)"/watch-audio-phone.avd "$(avd_home)"/watch-audio-phone.ini "$(avd_home)"/watch-audio-wear.avd "$(avd_home)"/watch-audio-wear.ini; do
    [ -e "$p" ] && echo "$p"
  done
  if [ "$all" = "1" ]; then
    [ -d "$(gradle_home)/caches" ] && echo "$(gradle_home)/caches"
    [ -d "$(gradle_home)/wrapper/dists" ] && echo "$(gradle_home)/wrapper/dists"
    [ -d "$(konan_home)" ] && konan_home && echo
    [ -d "$HOME/.npm/_cacache" ] && echo "$HOME/.npm/_cacache"
    [ -d "$HOME/Library/Caches/org.swift.swiftpm" ] && echo "$HOME/Library/Caches/org.swift.swiftpm"
    local sdk
    sdk=$(android_sdk)
    if [ -n "$sdk" ]; then
      for p in "$sdk/system-images/android-35/google_apis" "$sdk/system-images/android-34/android-wear"; do
        [ -d "$p" ] && echo "$p"
      done
    fi
  fi
}

# Simulator devices the ios stage created ("watch-audio-check ..."), as "<udid> <name>".
check_simulators() {
  [ "$CHECK_OS" = "macos" ] && have xcrun || return 0
  xcrun simctl list devices -j 2>/dev/null | python3 -c '
import json, sys
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["name"].startswith("watch-audio-check"):
            print(d["udid"], d["name"])'
}

# The repository's build outputs only (--clean-after).
clean_build_outputs() {
  local p
  echo "Removing the repository's build outputs (--clean-after)"
  find "$CHECK_ROOT" \( -name .git -o -name node_modules -o -name .claude \) -prune -o \
    -type d -name build -prune -print 2>/dev/null | while read -r p; do
    case "$p" in "$CHECK_ROOT"/build) ;; *) rm -rf "$p" ;; esac
  done
  # Keep build/check (this run's results and logs).
  find "$CHECK_ROOT/build" -mindepth 1 -maxdepth 1 ! -name check -exec rm -rf {} + 2>/dev/null || true
}

clean_command() {
  local all=0 yes=0 a
  for a in "$@"; do
    case "$a" in
      --all) all=1 ;;
      --yes) yes=1 ;;
      *) echo "clean: unknown option $a" >&2; return 2 ;;
    esac
  done
  local targets sims total=0 p k
  targets=$(clean_targets "$all" | awk 'NF' | sort -u)
  sims=$(check_simulators)
  if [ -z "$targets" ] && [ -z "$sims" ]; then
    echo "Nothing to remove."
    return 0
  fi
  echo "tools/check clean$([ $all = 1 ] && echo " --all") would remove:"
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    k=$(size_kib "$p")
    total=$((total + k))
    printf '  %10s  %s\n' "$(human_kib "$k")" "$p"
  done <<EOF
$targets
EOF
  if [ -n "$sims" ]; then
    printf '%s\n' "$sims" | while read -r p k; do printf '  %10s  simulator %s (%s)\n' "-" "$k" "$p"; done
  fi
  echo "  total ~$(human_kib "$total")"
  [ $all = 1 ] && echo "  (--all: the Gradle, Kotlin/Native, npm and SwiftPM caches are shared with other projects; they are downloaded again when needed)"
  echo "Not removed: Xcode, simulator runtimes (xcrun simctl runtime delete <id>), the Android SDK, JDK, Node."
  if [ $yes != 1 ]; then
    if [ ! -t 0 ]; then
      echo "Not removed: pass --yes to confirm without a terminal." >&2
      return 1
    fi
    printf 'Remove these? [y/N] '
    read -r a
    case "$a" in y | Y | yes) ;; *) echo "Nothing removed."; return 1 ;; esac
  fi
  while IFS= read -r p; do
    [ -n "$p" ] && rm -rf "$p"
  done <<EOF
$targets
EOF
  if [ -n "$sims" ]; then
    printf '%s\n' "$sims" | while read -r p k; do
      xcrun simctl shutdown "$p" 2>/dev/null || true
      xcrun simctl delete "$p" && echo "Deleted simulator $p"
    done
  fi
  echo "Removed ~$(human_kib "$total")."
}
