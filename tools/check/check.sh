#!/usr/bin/env bash
# The one entry point for the repository's checks, on a developer's machine and in CI (#202).
# The workflows call the same steps (`check.sh step <name>`), so local runs and CI cannot drift;
# tools/check/test_parity.py fails when a workflow step does real work without going through here.
#
#   tools/check/check.sh [options] <stage>     fast | harness | android-emulator | ios-build | ios | all
#   tools/check/check.sh step <name> [args]     one step (what a workflow step runs)
#   tools/check/check.sh list                   stages and their steps
#   tools/check/check.sh doctor [stage]         tools, disk space and what each stage needs
#   tools/check/check.sh clean [--all] [--yes]  remove what the checks created (--all: also the caches)
#   tools/check/check.sh install-hook [--quick] optional git pre-push hook (uninstall-hook removes it)
#
# Options (stages):
#   --only <steps>        run only these steps (comma-separated, shell patterns such as 'bridge-*')
#   --skip <steps>        leave these steps out
#   --quick               fast without the slow builds (pre-push): tests, static checks, bridge
#   --fail-fast           stop at the first failure (default: run everything, report at the end)
#   --dry-run             print the plan only
#   --gradle-args "<a>"   extra Gradle arguments, e.g. "--max-workers=2" (also CHECK_GRADLE_ARGS)
#   --no-jni              do not build the sherpa-onnx JNI library without TTS (check-no-tts is skipped)
#   --ignore-disk         run even if the disk-space estimate says the stage does not fit
#   --clean-after         remove the repository's build outputs when the stage ends
#   --ci                  CI mode (also when GITHUB_ACTIONS=true)
# android-emulator:
#   --targets phone,wear  which devices (default: both)
#   --phone-serial <s>    --wear-serial <s>   pick devices (default: detected from `adb devices`)
#   --allow-real-device   run on a real phone/watch (uninstalls app.watchaudio there: erases its data)
#   --windows-adb         WSL: use Windows' adb.exe (an emulator running on Windows)
# ios / ios-build:
#   --no-watch            leave out the watchOS-only builds and the Apple Watch simulator checks
#   --keep-simulators     keep the simulator devices this run created (default: deleted at the end)
#   --existing-simulators use the newest existing iPhone / Apple Watch simulators instead
#   --keep-derived-data   keep build/DerivedData (default: removed after ios-build and ios)
#
# Results: a table of ran / passed / failed / skipped steps with durations, the same table in
# build/check/results.md, evidence in build/check/summary.md, a log per step in build/check/logs/.
# Exit status: 0 if no step failed, 1 if one did, 2 on a usage error, 3 if the disk is too small.
# Docs: docs/dev/04-dev-environment.md 「手元での確認」.

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=tools/check/lib.sh
. "$HERE/lib.sh"
# shellcheck source=tools/check/steps-build.sh
. "$HERE/steps-build.sh"
# shellcheck source=tools/check/steps-bridge.sh
. "$HERE/steps-bridge.sh"
# shellcheck source=tools/check/steps-emulator.sh
. "$HERE/steps-emulator.sh"
# shellcheck source=tools/check/steps-ios.sh
. "$HERE/steps-ios.sh"
# shellcheck source=tools/check/doctor.sh
. "$HERE/doctor.sh"

usage() {
  sed -n '2,/^# Docs:/p' "$HERE/check.sh" | sed 's/^# \{0,1\}//'
  exit 2
}

STAGES="fast harness android-emulator ios-build ios all"
QUICK_SKIP="sherpa-onnx-jni,search-benchmark,lint,assemble-*,privacy-merged,check-no-tts,release-test-apks,ios-cross-compile,bridge-fake-phone,bridge-e2e"
TARGETS=phone,wear
PHONE_SERIAL=""
WEAR_SERIAL=""
ALLOW_REAL=0
WINDOWS_ADB=0
NO_WATCH=0
KEEP_SIMULATORS=0
EXISTING_SIMULATORS=0
KEEP_DERIVED_DATA=0
IGNORE_DISK=0
CLEAN_AFTER=0
CHECK_NO_JNI=${CHECK_NO_JNI:-0}
CHECK_QUICK=0

# --- stages ------------------------------------------------------------------------------------

# fast: everything the Linux PR jobs run except the emulators and the harness (android.yml,
# deliver.yml, agent-bridge.yml, links.yml, emulator.yml's release-test-apks), plus the iOS Kotlin
# type-check that CLAUDE.md asks for before pushing iOS changes.
stage_fast() {
  run_step version
  run_step privacy-static
  run_step parity
  run_step links
  run_step sherpa-onnx-jni
  run_step unit-tests
  run_step search-benchmark
  run_step lint
  run_step assemble-debug
  run_step privacy-merged
  run_step assemble-release
  run_step check-no-tts
  run_step release-test-apks
  run_step ios-cross-compile
  run_step bridge-npm-ci
  run_step bridge-lint
  run_step bridge-test
  run_step bridge-fake-phone
  run_after bridge-fake-phone bridge-e2e
}

# harness: android.yml's device-free end-to-end run with the real Japanese ASR models.
stage_harness() {
  run_step harness
}

# Lines "<serial> <phone|wear> <emulator|device>" for the devices adb sees.
list_devices() {
  local s chars qemu kind
  adb devices 2> /dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }' | while read -r s; do
    chars=$(adb -s "$s" shell getprop ro.build.characteristics 2> /dev/null | tr -d '\r')
    qemu=$(adb -s "$s" shell getprop ro.kernel.qemu 2> /dev/null | tr -d '\r')
    [ -n "$qemu" ] || qemu=$(adb -s "$s" shell getprop ro.boot.qemu 2> /dev/null | tr -d '\r')
    case "$s" in emulator-*) qemu=1 ;; esac
    kind=device
    [ "$qemu" = "1" ] && kind=emulator
    case "$chars" in *watch*) echo "$s wear $kind" ;; *) echo "$s phone $kind" ;; esac
  done
}

# WSL: an `adb` on PATH that runs Windows' adb.exe, so the emulator that runs on Windows (and the
# devices plugged into Windows) are reachable. Gradle's connected tests reach the same adb server
# through localhost:5037 (WSL's mirrored networking; docs/dev/04).
use_windows_adb() {
  have adb.exe || { echo "adb.exe not found on PATH (Windows' Android SDK platform-tools)" >&2; return 1; }
  mkdir -p "$CHECK_OUT/bin"
  printf '#!/bin/sh\nexec adb.exe "$@"\n' > "$CHECK_OUT/bin/adb"
  chmod +x "$CHECK_OUT/bin/adb"
  export PATH="$CHECK_OUT/bin:$PATH"
  if have wslinfo && [ "$(wslinfo --networking-mode 2> /dev/null)" != "mirrored" ]; then
    echo "WARNING: WSL networking is not 'mirrored': the instrumented tests (Gradle) and the agent access check need it (docs/dev/04)." >&2
  fi
}

# android-emulator: emulator.yml's smoke jobs against a running emulator or a connected device.
stage_android_emulator() {
  if [ "$CHECK_DRY_RUN" = "1" ]; then
    run_step sherpa-onnx-jni
    run_step models
    run_step bridge-build
    local t
    for t in $(echo "$TARGETS" | tr ',' ' '); do
      run_step emulator-build "$t"
      [ "$t" = phone ] && run_step release-test-apks
      run_step emulator-run "$t"
      run_step emulator-evidence "$t"
    done
    return 0
  fi
  if [ "$WINDOWS_ADB" = "1" ] || { [ "$CHECK_OS" = "wsl" ] && ! have adb && have adb.exe; }; then
    use_windows_adb || { skip_step "android-emulator" "--windows-adb: adb.exe not found"; return 0; }
  fi
  if ! have adb; then
    skip_step "android-emulator" "adb not on PATH (Android SDK platform-tools; WSL: --windows-adb)"
    return 0
  fi
  local devices
  devices=$(list_devices)
  echo "Devices:"
  echo "${devices:-  (none)}"
  local target serial kind
  for target in $(echo "$TARGETS" | tr ',' ' '); do
    serial=""
    if [ "$target" = phone ]; then serial=$PHONE_SERIAL; else serial=$WEAR_SERIAL; fi
    [ -n "$serial" ] || serial=$(printf '%s\n' "$devices" | awk -v t="$target" '$2 == t { print $1; exit }')
    if [ -z "$serial" ]; then
      skip_step "emulator-run $target" "no $target device: start an emulator (tools/check/avd.sh start $target) or connect one"
      continue
    fi
    kind=$(printf '%s\n' "$devices" | awk -v s="$serial" '$1 == s { print $3 }')
    if [ "$kind" != "emulator" ] && [ "$ALLOW_REAL" != "1" ]; then
      skip_step "emulator-run $target" "$serial is a real device: the checks uninstall app.watchaudio there (its recordings are erased); pass --allow-real-device"
      continue
    fi
    export ANDROID_SERIAL=$serial
    if [ "$kind" = "emulator" ]; then export CHECK_REAL_DEVICE=0; else export CHECK_REAL_DEVICE=1; fi
    echo "== $target: $serial ($kind)"
    if [ "$target" = phone ]; then
      [ -n "$(status_of sherpa-onnx-jni)" ] || run_step sherpa-onnx-jni
      run_step models
      run_step bridge-build
      run_step emulator-build phone
      run_step release-test-apks
      # release-asr.sh takes the APKs from release-test-apks/ (built above) as CI does from the artifact.
      export RELEASE_TEST_ARTIFACT=release-test-apks
      if passed "emulator-build phone" && passed release-test-apks && passed models && passed bridge-build; then
        run_step emulator-run phone
      else
        skip_step "emulator-run phone" "needs emulator-build phone, release-test-apks, models and bridge-build"
      fi
      unset RELEASE_TEST_ARTIFACT
    else
      run_step emulator-build wear
      run_after "emulator-build wear" emulator-run wear
    fi
    run_step emulator-evidence "$target"
    unset ANDROID_SERIAL CHECK_REAL_DEVICE
  done
}

# Simulator devices for the ios stage: made here (fresh state, like CI's runners) and deleted at the
# end unless --keep-simulators, or the newest existing ones with --existing-simulators.
SIM_CREATED=""
SIM_PREFIX="watch-audio-check"

# newest_runtime <iOS|watchOS>: identifier and a device type of the newest available runtime.
newest_runtime() {
  xcrun simctl list runtimes available -j 2> /dev/null | python3 -c '
import json, re, sys
platform = sys.argv[1]
family = "iPhone" if platform == "iOS" else "Apple Watch"
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r.get("platform") == platform and r.get("isAvailable", True)]
if not runtimes:
    sys.exit(0)
r = runtimes[-1]
types = [t for t in r.get("supportedDeviceTypes", []) if t.get("productFamily") == family]
plain = [t for t in types if re.fullmatch(r"(iPhone \d+( Pro)?|Apple Watch Series \d+ \(\d+mm\))", t["name"])]
pick = (plain or types or [{"identifier": ""}])[-1]
print(r["identifier"], pick["identifier"])
' "$1"
}

create_simulator() {
  local platform=$1 runtime type udid name
  read -r runtime type <<EOF
$(newest_runtime "$platform")
EOF
  [ -n "$runtime" ] && [ -n "$type" ] || return 1
  name="$SIM_PREFIX $platform"
  udid=$(xcrun simctl create "$name" "$type" "$runtime") || return 1
  SIM_CREATED="$SIM_CREATED $udid"
  echo "$udid" >> "$CHECK_OUT/created-simulators.txt"
  echo "Created simulator $name ($type, $runtime): $udid" >&2
  echo "$udid"
}

delete_created_simulators() {
  local udid
  for udid in $SIM_CREATED; do
    xcrun simctl shutdown "$udid" 2> /dev/null || true
    xcrun simctl delete "$udid" 2> /dev/null && echo "Deleted simulator $udid"
  done
  rm -f "$CHECK_OUT/created-simulators.txt"
}

clean_derived_data() {
  [ "$KEEP_DERIVED_DATA" = "1" ] && return 0
  [ -n "${DERIVED_DATA:-}" ] && return 0
  if [ -d build/DerivedData ]; then
    echo "Removing build/DerivedData ($(human_kib "$(size_kib build/DerivedData)")); keep it with --keep-derived-data"
    rm -rf build/DerivedData
  fi
}

# What Xcode has: the iOS / watchOS simulator SDKs (needed to build; the iOS app embeds the watchOS
# app) and simulator runtimes (needed to boot a simulator).
xcode_has_sdk() { xcodebuild -showsdks 2> /dev/null | grep -q -- "-sdk $1"; }
xcode_has_runtime() { [ -n "$(newest_runtime "$1")" ]; }

ios_preflight() {
  if [ "$CHECK_OS" != "macos" ]; then
    skip_step "$1" "needs macOS with Xcode 26 (CI: ios.yml)"
    return 1
  fi
  if ! have xcodebuild; then
    skip_step "$1" "xcodebuild not found (Xcode 26, then: sudo xcode-select -s /Applications/Xcode.app)"
    return 1
  fi
  return 0
}

# ios-build: only what the Mac is needed for. The Xcode project, then the unsigned builds that
# ios.yml makes: Debug for the simulators (the app build also builds the Kotlin framework through
# its "Compile Kotlin Framework" phase) and the release job's Release builds for devices, with the
# release evidence. No simulator is booted.
stage_ios_build() {
  ios_preflight ios-build || return 0
  run_step ios-xcodegen
  if ! xcode_has_sdk watchsimulator && [ "$CHECK_DRY_RUN" != "1" ]; then
    echo "WARNING: Xcode lists no watchOS SDK; the iOS app embeds the watchOS app, so its build may fail (Xcode > Settings > Components > watchOS)." >&2
  fi
  run_after ios-xcodegen ios-build-app
  if [ "$NO_WATCH" = "1" ]; then
    skip_step ios-build-watch "--no-watch"
  else
    run_after ios-xcodegen ios-build-watch
  fi
  run_after ios-xcodegen ios-release-build-app
  if [ "$NO_WATCH" = "1" ]; then
    skip_step ios-release-build-watch "--no-watch"
    run_after ios-release-build-app ios-release-evidence
  else
    run_after ios-xcodegen ios-release-build-watch
    if passed ios-release-build-app && passed ios-release-build-watch || [ "$CHECK_DRY_RUN" = "1" ]; then
      run_step ios-release-evidence
    else
      skip_step ios-release-evidence "needs ios-release-build-app and ios-release-build-watch"
    fi
  fi
  [ "$CHECK_DRY_RUN" = "1" ] || clean_derived_data
}

# ios: everything ios.yml runs (both jobs), in the build job's order, then the release job.
stage_ios() {
  ios_preflight ios || return 0
  local watch=1 watch_reason=""
  if [ "$NO_WATCH" = "1" ]; then
    watch=0
    watch_reason="--no-watch"
  elif [ "$CHECK_DRY_RUN" != "1" ] && ! xcode_has_runtime watchOS; then
    watch=0
    watch_reason="no watchOS simulator runtime (Xcode > Settings > Components; or --no-watch)"
  fi
  if [ "$CHECK_DRY_RUN" != "1" ] && ! xcode_has_runtime iOS; then
    skip_step ios "no iOS simulator runtime (Xcode > Settings > Components > iOS, or xcodebuild -downloadPlatform iOS); ios-build needs none"
    return 0
  fi
  if [ "$CHECK_DRY_RUN" != "1" ] && [ "$EXISTING_SIMULATORS" != "1" ]; then
    rm -f "$CHECK_OUT/created-simulators.txt"
    SIMULATOR_UDID_IOS=$(create_simulator iOS) || { skip_step ios "could not create an iPhone simulator"; return 0; }
    export SIMULATOR_UDID_IOS
    if [ $watch = 1 ]; then
      SIMULATOR_UDID_WATCHOS=$(create_simulator watchOS) || { watch=0; watch_reason="could not create an Apple Watch simulator"; }
      export SIMULATOR_UDID_WATCHOS
    fi
  fi

  run_step ios-kotlin-tests
  run_step ios-kotlin-evidence
  run_step ios-xcodegen
  run_after ios-xcodegen ios-build-app
  if [ $watch = 1 ]; then
    run_after ios-xcodegen ios-build-watch
    run_after ios-build-watch ios-watch-release-check
    run_after ios-build-watch ios-complication
    run_after ios-build-watch ios-watch-launch
  else
    local s
    for s in ios-build-watch ios-watch-release-check ios-complication ios-watch-launch; do skip_step $s "$watch_reason"; done
  fi
  run_after ios-xcodegen ios-swift-unit
  run_after ios-xcodegen ios-llm
  run_after ios-build-app ios-launch
  if passed ios-build-app || [ "$CHECK_DRY_RUN" = "1" ]; then
    run_step_soft ios-ja-speech
    run_step_soft ios-mixed-speech
  else
    skip_step ios-ja-speech "needs ios-build-app"
    skip_step ios-mixed-speech "needs ios-build-app"
  fi
  run_after ios-build-app ios-speech-tests
  run_after ios-build-app ios-playback
  run_after ios-build-app ios-embedding-model
  run_after ios-embedding-model ios-semantic-search
  run_after ios-build-app bridge-build
  run_after bridge-build ios-agent-access
  run_step ios-shutdown

  # The release job (Release, devices, unsigned).
  run_after ios-xcodegen ios-release-build-app
  if [ $watch = 1 ]; then
    run_after ios-xcodegen ios-release-build-watch
  else
    skip_step ios-release-build-watch "$watch_reason"
  fi
  if passed ios-release-build-app && { passed ios-release-build-watch || [ $watch = 0 ]; } || [ "$CHECK_DRY_RUN" = "1" ]; then
    run_step ios-release-evidence
  else
    skip_step ios-release-evidence "needs the Release builds"
  fi

  if [ "$CHECK_DRY_RUN" != "1" ]; then
    if [ "$KEEP_SIMULATORS" = "1" ]; then
      [ -n "$SIM_CREATED" ] && echo "Kept simulators:$SIM_CREATED (delete with: tools/check/check.sh clean)"
    else
      delete_created_simulators
    fi
    clean_derived_data
  fi
}

stage_all() {
  stage_fast
  stage_harness
  stage_android_emulator
  if [ "$CHECK_OS" = "macos" ]; then
    stage_ios
  else
    skip_step ios "needs macOS with Xcode 26 (CI: ios.yml)"
  fi
}

# --- commands ----------------------------------------------------------------------------------

list_command() {
  local st
  echo "Stages:"
  for st in fast harness android-emulator ios-build ios; do
    printf '  %-17s' "$st"
    (
      R_NAMES=()
      CHECK_DRY_RUN=1
      CHECK_OUT=$(mktemp -d)
      init_out
      CHECK_OS=macos # list the macOS-only steps too
      ios_preflight() { return 0; }
      "stage_$(echo "$st" | tr '-' '_')" > /dev/null 2>&1
      printf '%s\n' "${R_NAMES[@]}" | awk '{ printf "%s%s", (NR > 1 ? ", " : ""), $0 } END { print "" }'
      rm -rf "$CHECK_OUT"
    )
  done
  echo "  all              fast + harness + android-emulator + ios"
  echo
  echo "Steps (tools/check/check.sh step <name>):"
  all_steps | sort | awk '{ printf "  %s\n", $0 }'
}

install_hook() {
  local hooks hook flags=""
  [ "$CHECK_QUICK" = "1" ] && flags=" --quick"
  hooks=$(git -C "$CHECK_ROOT" rev-parse --git-path hooks)
  case "$hooks" in /*) ;; *) hooks="$CHECK_ROOT/$hooks" ;; esac
  mkdir -p "$hooks"
  hook="$hooks/pre-push"
  if [ -f "$hook" ] && ! grep -q "tools/check/check.sh" "$hook"; then
    echo "$hook exists and is not ours; not replaced" >&2
    exit 1
  fi
  cat > "$hook" <<EOF
#!/usr/bin/env bash
# Installed by tools/check/check.sh install-hook (#202). Remove: tools/check/check.sh uninstall-hook.
# Skip once: git push --no-verify (or WATCHAUDIO_SKIP_CHECKS=1 git push).
[ "\${WATCHAUDIO_SKIP_CHECKS:-0}" = "1" ] && exit 0
exec "\$(git rev-parse --show-toplevel)/tools/check/check.sh"$flags fast
EOF
  chmod +x "$hook"
  echo "Installed $hook (runs: tools/check/check.sh$flags fast)"
}

uninstall_hook() {
  local hook
  hook=$(git -C "$CHECK_ROOT" rev-parse --git-path hooks)/pre-push
  case "$hook" in /*) ;; *) hook="$CHECK_ROOT/$hook" ;; esac
  if [ -f "$hook" ] && grep -q "tools/check/check.sh" "$hook"; then
    rm -f "$hook"
    echo "Removed $hook"
  else
    echo "No pre-push hook of tools/check installed"
  fi
}

# --- main --------------------------------------------------------------------------------------

COMMAND=""
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help) usage ;;
    --only) CHECK_ONLY=$2; shift ;;
    --skip) CHECK_SKIP=${CHECK_SKIP:+$CHECK_SKIP,}$2; shift ;;
    --quick) CHECK_SKIP=${CHECK_SKIP:+$CHECK_SKIP,}$QUICK_SKIP; CHECK_QUICK=1 ;;
    --fail-fast) CHECK_FAIL_FAST=1 ;;
    --dry-run) CHECK_DRY_RUN=1 ;;
    --gradle-args) CHECK_GRADLE_ARGS=$2; shift ;;
    --no-jni) CHECK_NO_JNI=1 ;;
    --ignore-disk) IGNORE_DISK=1 ;;
    --clean-after) CLEAN_AFTER=1 ;;
    --ci) CHECK_CI=1 ;;
    --targets) TARGETS=$2; shift ;;
    --phone-serial) PHONE_SERIAL=$2; shift ;;
    --wear-serial) WEAR_SERIAL=$2; shift ;;
    --allow-real-device) ALLOW_REAL=1 ;;
    --windows-adb) WINDOWS_ADB=1 ;;
    --no-watch) NO_WATCH=1 ;;
    --keep-simulators) KEEP_SIMULATORS=1 ;;
    --existing-simulators) EXISTING_SIMULATORS=1 ;;
    --keep-derived-data) KEEP_DERIVED_DATA=1 ;;
    --all | --yes | --measure) ARGS+=("$1") ;;
    -*)
      if [ -n "$COMMAND" ]; then ARGS+=("$1"); else echo "unknown option: $1" >&2; exit 2; fi ;;
    *)
      if [ -z "$COMMAND" ]; then COMMAND=$1; else ARGS+=("$1"); fi ;;
  esac
  shift
done
[ -n "$COMMAND" ] || usage
export CHECK_NO_JNI CHECK_QUICK

cd "$CHECK_ROOT" || exit 1
init_out
# macOS has shasum but no sha256sum; the repository's scripts use sha256sum.
if ! have sha256sum && have shasum; then
  mkdir -p "$CHECK_OUT/bin"
  printf '#!/bin/sh\nexec shasum -a 256 "$@"\n' > "$CHECK_OUT/bin/sha256sum"
  chmod +x "$CHECK_OUT/bin/sha256sum"
  export PATH="$CHECK_OUT/bin:$PATH"
fi

case "$COMMAND" in
  step)
    [ ${#ARGS[@]} -ge 1 ] || { echo "usage: check.sh step <name> [args]" >&2; exit 2; }
    is_ci || load_env_file
    run_step "${ARGS[@]}"
    rc=$?
    is_ci && exit $rc
    print_results
    exit $?
    ;;
  list) list_command; exit 0 ;;
  doctor) doctor_command ${ARGS[@]+"${ARGS[@]}"}; exit $? ;;
  clean) clean_command ${ARGS[@]+"${ARGS[@]}"}; exit $? ;;
  install-hook) install_hook ${ARGS[@]+"${ARGS[@]}"}; exit $? ;;
  uninstall-hook) uninstall_hook; exit $? ;;
esac

case " $STAGES " in
  *" $COMMAND "*) ;;
  *) echo "unknown stage or command: $COMMAND (stages: $STAGES)" >&2; exit 2 ;;
esac

echo "tools/check: stage $COMMAND on $CHECK_OS ($CHECK_ARCH)${CHECK_GRADLE_ARGS:+, Gradle $CHECK_GRADLE_ARGS}"
if [ "$CHECK_DRY_RUN" != "1" ] && ! is_ci; then
  rm -f "$CHECK_ENV_FILE"
  : > "$CHECK_SUMMARY"
  if [ -n "$CHECK_ONLY" ]; then
    echo "(--only: no disk-space estimate for a part of the stage; see tools/check/check.sh doctor)"
  elif ! disk_fits "$COMMAND"; then
    if [ "$IGNORE_DISK" = "1" ]; then
      echo "Continuing (--ignore-disk)."
    else
      echo "Not started: the estimate above says the stage does not fit. Free space (tools/check/check.sh clean)," \
        "move caches to another disk (docs/dev/04), or pass --ignore-disk." >&2
      exit 3
    fi
  fi
fi
"stage_$(echo "$COMMAND" | tr '-' '_')"
if [ "$CLEAN_AFTER" = "1" ] && [ "$CHECK_DRY_RUN" != "1" ]; then
  clean_build_outputs
fi
print_results
