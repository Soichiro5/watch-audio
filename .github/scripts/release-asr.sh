#!/usr/bin/env bash
# Release ASR check on the phone emulator (emulator.yml, #78), the last step of the phone job (after
# the launch checks, the scenarios and the debug instrumented tests):
#
#   0. in CI (RELEASE_TEST_ARTIFACT set), waits for the APKs of step 2, built in parallel by the release-test-apks
#      job of the same run, and downloads them (artifact RELEASE_TEST_ARTIFACT, with `gh`; GH_TOKEN, GITHUB_RUN_ID);
#      otherwise uses the local build outputs,
#   1. pushes the speech models and reference recordings (push-models.sh),
#   2. installs, on a clean state, the release APK built with `-Pwatchaudio.testBuildType=release`
#      (the shipped R8 setup plus keep rules for what the test calls, apps/android/proguard-release-test.pro)
#      and the instrumented test APK built for it (both signed with the same throwaway key),
#   3. runs ReleaseAsrTest: a known Japanese chunk is stored through the app's ingestor and the
#      app's own WorkManager run transcribes it into its database with sherpa-onnx.
#
# Evidence: the RELEASE_ASR_CHECK line, appended to launch-checks.txt (job summary); logs in
# release-asr/. Exit status 1 if the check failed.
set -uo pipefail

RUNNER=app.watchaudio.test/androidx.test.runner.AndroidJUnitRunner
APP_APK=apps/android/build/outputs/apk/release/android-release.apk
TEST_APK=apps/android/build/outputs/apk/androidTest/release/android-release-androidTest.apk
OUT=release-asr
mkdir -p "$OUT"

fail() {
  echo "RELEASE_ASR_CHECK FAILED: $*" | tee -a launch-checks.txt
  exit 1
}

if [ -n "${RELEASE_TEST_ARTIFACT:-}" ]; then
  dir=$RELEASE_TEST_ARTIFACT
  APP_APK=$dir/android-release.apk
  TEST_APK=$dir/android-release-androidTest.apk
  waited=0
  until [ -f "$APP_APK" ] && [ -f "$TEST_APK" ]; do
    gh run download "$GITHUB_RUN_ID" -R "$GITHUB_REPOSITORY" -n "$RELEASE_TEST_ARTIFACT" -D "$dir" > "$OUT/download.txt" 2>&1 && continue
    job=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs" \
      --jq ".jobs[] | select(.name == \"$RELEASE_TEST_ARTIFACT\") | .status + \" \" + (.conclusion // \"\")" 2>/dev/null || true)
    case "$job" in
      "completed success" | "") ;;
      completed*) fail "the $RELEASE_TEST_ARTIFACT job ended: $job" ;;
    esac
    [ "$waited" -ge 900 ] && fail "no $RELEASE_TEST_ARTIFACT artifact after $waited s ($OUT/download.txt)"
    sleep 10
    waited=$((waited + 10))
  done
  echo "APKs from the $RELEASE_TEST_ARTIFACT job (waited $waited s for them)" | tee "$OUT/apks.txt"
fi

bash .github/scripts/push-models.sh > "$OUT/push-models.txt" 2>&1 || fail "models not pushed ($OUT/push-models.txt)"
tail -1 "$OUT/push-models.txt"
# The debug builds of the app and its test APK are signed with another key.
adb uninstall app.watchaudio > /dev/null || true
adb uninstall app.watchaudio.test > /dev/null || true
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
