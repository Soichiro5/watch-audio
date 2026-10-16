# shellcheck shell=bash
# Steps of emulator.yml's smoke jobs (phone, wear): build the APKs, then on a running emulator (or a
# connected device, locally) install, launch, run the scenarios, the instrumented tests and the
# release ASR check (#44, #78, #92, #184). Sourced by check.sh. The device is the one adb talks to
# (ANDROID_SERIAL locally; the emulator runner's only device in CI).

# emulator_target <phone|wear>: the per-target settings that were emulator.yml's matrix values.
emulator_target() {
  case "$1" in
    phone)
      MODULE=apps/android
      GRADLE_PATH=:apps:android
      APK=android
      DEVICE_TEST_APKS=":apps:android:assembleDebugAndroidTest :shared:db:assembleAndroidDeviceTest :shared:pipeline:assembleAndroidDeviceTest"
      DEVICE_TESTS=":apps:android:connectedDebugAndroidTest :shared:db:connectedAndroidDeviceTest :shared:pipeline:connectedAndroidDeviceTest"
      # Each of these must report at least one test case: the test engine can end green with 0 tests (e.g. a failed APK install).
      DEVICE_TEST_MODULES="apps/android shared/db shared/pipeline"
      # The ASR tests must not skip for lack of models (pushed by phone-scenarios.sh).
      DEVICE_TEST_ARGS="-Pandroid.testInstrumentationRunnerArguments.requireModels=true"
      # #78: ReleaseAsrTest against the R8 release APK, with the APKs of the release-test-apks step.
      RELEASE_CHECKS=.github/scripts/release-asr.sh
      SCENARIOS=.github/scripts/phone-scenarios.sh
      ;;
    wear)
      MODULE=apps/wear
      GRADLE_PATH=:apps:wear
      APK=wear
      DEVICE_TEST_APKS=""
      DEVICE_TESTS=""
      DEVICE_TEST_MODULES=""
      DEVICE_TEST_ARGS=""
      RELEASE_CHECKS=""
      SCENARIOS=.github/scripts/wear-surfaces.sh
      ;;
    *) fail "unknown emulator target: $1 (phone or wear)" ;;
  esac
}

# emulator-build <phone|wear>: "Build release and debug APKs (and instrumented test APKs)".
# The release build (R8, #78) is signed with a throwaway key made here: it only has to install on
# the emulator, and the real release key never reaches this step. The shipped release APK is set
# aside in release-apk/ for the launch check.
step_emulator_build() {
  local target=${1:?phone or wear}
  emulator_target "$target"
  throwaway_key
  # shellcheck disable=SC2086
  gw $GRADLE_PATH:assembleRelease $GRADLE_PATH:assembleDebug $DEVICE_TEST_APKS
  mkdir -p release-apk
  cp "$MODULE/build/outputs/apk/release/$APK-release.apk" release-apk/
  if [ "$target" = phone ]; then
    no_tts_check release-apk/android-release.apk apps/android/build/outputs/apk/debug/android-debug.apk
  fi
}

# emulator-run <phone|wear>: "Install, launch and check for crashes" (the emulator runner's script).
step_emulator_run() {
  local target=${1:?phone or wear}
  emulator_target "$target"
  have adb || fail "adb is not on PATH (Android SDK platform-tools)"
  adb get-state > /dev/null 2>&1 || fail "no device: start an emulator or connect a device (docs/dev/04 「手元での確認」)"
  rm -f device-tests-failed launch-checks.txt
  if ! is_ci; then
    # Leftovers of an earlier local run must not count as this run's evidence.
    rm -rf scenario-logs release-asr wear-surfaces "screenshots-$target" logcat-release.txt logcat.txt logcat-device-tests.txt
    local m
    for m in $DEVICE_TEST_MODULES; do rm -rf "$m/build/outputs/androidTest-results/connected"; done
    adb shell getprop ro.build.version.sdk | tr -d '\r' > "$CHECK_OUT/api-level-$target.txt" || true
    adb shell rm -rf /data/local/tmp/watchaudio-screenshots || true
    if [ "${CHECK_REAL_DEVICE:-0}" = "1" ] && [ "$target" = phone ]; then
      # A real phone reaches the bridge CLI on this machine through adb, not at 10.0.2.2.
      adb reverse tcp:47823 tcp:47823
      export AGENT_HOST_ADDRESS=127.0.0.1
    fi
  fi
  bash .github/scripts/launch-smoke.sh "release-apk/$APK-release.apk" logcat-release.txt
  adb uninstall app.watchaudio
  bash .github/scripts/launch-smoke.sh "$MODULE/build/outputs/apk/debug/$APK-debug.apk" logcat.txt
  if [ -n "$SCENARIOS" ]; then bash "$SCENARIOS" || touch device-tests-failed; fi
  # The test engine reinstalls both APKs itself; remove the earlier installs (smoke test, scenarios) so a signature mismatch cannot skip the tests.
  if [ -n "$DEVICE_TESTS" ]; then adb uninstall app.watchaudio || true; adb uninstall app.watchaudio.test || true; fi
  if [ -n "$DEVICE_TESTS" ]; then
    adb logcat -c
    # shellcheck disable=SC2086
    gw $DEVICE_TESTS $DEVICE_TEST_ARGS || touch device-tests-failed
    adb logcat -d > logcat-device-tests.txt || true
  fi
  # Last, so the launch checks, scenarios and debug tests run on the same device state as without it (#78).
  if [ -n "$RELEASE_CHECKS" ]; then bash "$RELEASE_CHECKS" || touch device-tests-failed; fi
  if [ ! -d "screenshots-$target" ]; then adb pull /data/local/tmp/watchaudio-screenshots "screenshots-$target" || true; fi
  if [ -f device-tests-failed ]; then echo "instrumented tests failed"; exit 1; fi
  local m n
  for m in $DEVICE_TEST_MODULES; do
    n=$(find "$m/build/outputs/androidTest-results/connected" -name '*.xml' -exec grep -o '<testcase ' {} + 2>/dev/null | wc -l | tr -d ' ')
    echo "$m: $n instrumented test cases"
    if [ "$n" -eq 0 ]; then echo "no instrumented tests ran for $m"; exit 1; fi
  done
}

# emulator-evidence <phone|wear> [API level]: "Instrumented test evidence" (the job summary).
step_emulator_evidence() {
  local target=${1:?phone or wear} api=${2:-}
  emulator_target "$target"
  [ -n "$api" ] || api=$(cat "$CHECK_OUT/api-level-$target.txt" 2>/dev/null || echo "?")
  if [ -n "$DEVICE_TESTS" ]; then
    python3 .github/scripts/junit-summary.py "Instrumented tests on the $target emulator (API $api)" \
      'apps/android/build/outputs/androidTest-results/connected/**/*.xml' \
      'shared/*/build/outputs/androidTest-results/connected/**/*.xml' >> "$CHECK_SUMMARY"
  fi
  {
    echo "Checks on the $target emulator (logcat and scenario scripts):"
    echo '```'
    cat launch-checks.txt 2>/dev/null || true
    grep -ho "[A-Z_]*_CHECK .*\|SCREENSHOT .*\|MODEL_INSTALL .*" logcat-device-tests.txt 2>/dev/null | awk '!seen[$0]++' || true
    cat scenario-logs/summary.txt wear-surfaces/summary.txt 2>/dev/null || true
    echo '```'
    echo "Screenshots (artifact screenshots-$target):"
    ls "screenshots-$target" 2>/dev/null || echo "none"
  } >> "$CHECK_SUMMARY"
}
