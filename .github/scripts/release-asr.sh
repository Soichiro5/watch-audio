#!/usr/bin/env bash
# Release ASR check on the phone emulator (emulator.yml, #78), after the launch check of the shipped
# release APK:
#
#   1. pushes the speech models and reference recordings (push-models.sh),
#   2. installs, on a clean state, the release APK built with `-Pwatchaudio.testBuildType=release`
#      (the shipped R8 setup plus keep rules for what the test calls, androidApp/proguard-release-test.pro)
#      and the instrumented test APK built for it (both signed with the job's throwaway key),
#   3. runs ReleaseAsrTest: a known Japanese chunk is stored through the app's ingestor and the
#      app's own WorkManager run transcribes it into its database with sherpa-onnx.
#
# Evidence: the RELEASE_ASR_CHECK line, appended to launch-checks.txt (job summary); logs in
# release-asr/. Exit status 1 if the check failed.
set -uo pipefail

RUNNER=app.watchaudio.test/androidx.test.runner.AndroidJUnitRunner
APP_APK=androidApp/build/outputs/apk/release/androidApp-release.apk
TEST_APK=androidApp/build/outputs/apk/androidTest/release/androidApp-release-androidTest.apk
OUT=release-asr
mkdir -p "$OUT"

fail() {
  echo "RELEASE_ASR_CHECK FAILED: $*" | tee -a launch-checks.txt
  exit 1
}

bash .github/scripts/push-models.sh > "$OUT/push-models.txt" 2>&1 || fail "models not pushed ($OUT/push-models.txt)"
tail -1 "$OUT/push-models.txt"
adb uninstall app.watchaudio > /dev/null || true
adb install -r "$APP_APK" > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; fail "install of $APP_APK"; }
adb install -r -t "$TEST_APK" >> "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; fail "install of $TEST_APK"; }
adb logcat -c || true
adb shell am instrument -w -e class app.watchaudio.android.ReleaseAsrTest -e scenario release-asr -e requireModels true \
  "$RUNNER" > "$OUT/instrument.txt" 2>&1
adb logcat -d > "$OUT/logcat.txt" || true
adb uninstall app.watchaudio.test > /dev/null || true
cat "$OUT/instrument.txt"
# Lines can be logged twice (Log + System.out); keep the first of each.
grep -o "MODEL_INSTALL .*\|RELEASE_ASR_CHECK .*" "$OUT/logcat.txt" | awk '!seen[$0]++' > "$OUT/evidence.txt" || true
if grep -q "^OK (1 test)" "$OUT/instrument.txt" && grep -q "^RELEASE_ASR_CHECK " "$OUT/evidence.txt"; then
  tee -a launch-checks.txt < "$OUT/evidence.txt"
else
  cat "$OUT/evidence.txt"
  fail "ReleaseAsrTest did not pass ($OUT/instrument.txt, $OUT/logcat.txt)"
fi
