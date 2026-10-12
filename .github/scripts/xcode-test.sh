#!/usr/bin/env bash
# Runs an Xcode test scheme (apps/ios/project.yml) on the newest available iPhone simulator and
# lists every test case, with skip reasons and failure messages, in the job summary.
#
# Usage: xcode-test.sh <scheme> <summary title> [extra xcodebuild build settings...]
#   e.g. xcode-test.sh SwiftUnitTests "Swift unit tests on the iOS simulator"
# Env: UNINSTALL_APP=<bundle id> removes that app from the simulator before the run (hosted tests),
#      ERASE_SIMULATOR=1 erases the whole simulator first (slow: the next boot redoes the
#      data migration), DERIVED_DATA (default build/DerivedData, shared with the build steps so a
#      hosted test reuses the app build), RESULTS_DIR (default build/xcresults).
# The simulator is left booted for the next step (#92).
# Exits with xcodebuild's status (skipped tests do not fail it).
set -uo pipefail

scheme=$1
title=$2
shift 2
extra_settings=("$@")
derived_data=${DERIVED_DATA:-build/DerivedData}
results_dir=${RESULTS_DIR:-build/xcresults}
result="$results_dir/$scheme.xcresult"
log="xcodebuild-$scheme.log"
mkdir -p "$results_dir"
rm -rf "$result"

udid=$("$(dirname "$0")/simulator-udid.sh" iOS iPhone) || exit 1
echo "Simulator: $udid"

if [ "${ERASE_SIMULATOR:-0}" = "1" ]; then
  xcrun simctl shutdown "$udid" 2>/dev/null || true
  xcrun simctl erase "$udid"
  echo "Erased simulator $udid"
fi

# The first step on the iPhone boots it in the background while the tests build: the boot's
# data migration and the build overlap (#92). Later steps find it booted.
xcrun simctl boot "$udid" > /dev/null 2>&1 &

# Build and run separately so the log shows where the time goes.
xcode() {
  xcodebuild -project apps/ios/WatchAudio.xcodeproj -scheme "$scheme" \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived_data" \
    CODE_SIGNING_ALLOWED=NO ${extra_settings[@]+"${extra_settings[@]}"} "$@"
}
SECONDS=0
xcode build-for-testing > "$log" 2>&1
status=$?
echo "Timing: build-for-testing took ${SECONDS}s"

SECONDS=0
wait
xcrun simctl bootstatus "$udid" -b > /dev/null
echo "Timing: then waited ${SECONDS}s for the boot"

# While the first boot of the iPhone still migrates its data, xcodebuild can give up resolving the
# destination ("Unable to find a device matching the provided destination specifier", no build
# ran). The device is booted now, so build once more.
if [ $status -ne 0 ] && grep -q "Unable to find a device matching the provided destination specifier" "$log"; then
  echo "::warning::$scheme: the simulator was not available to xcodebuild during its boot; building again now that it is booted"
  SECONDS=0
  xcode build-for-testing >> "$log" 2>&1
  status=$?
  echo "Timing: build-for-testing again took ${SECONDS}s"
fi

# Hosted tests: start from a fresh install of the host app. With the app still installed from
# the launch smoke step (or the previous hosted test), the test host was launched from the
# replaced (dead) container and the test bundle could not be loaded ("Failed to create a bundle
# instance ... Check that the bundle exists"). Uninstalling the app gives it a new container and
# empty app data, like an erase, but keeps the booted device (#92).
if [ -n "${UNINSTALL_APP:-}" ]; then
  xcrun simctl terminate "$udid" "$UNINSTALL_APP" 2>/dev/null || true
  xcrun simctl uninstall "$udid" "$UNINSTALL_APP" 2>/dev/null || true
  if xcrun simctl get_app_container "$udid" "$UNINSTALL_APP" > /dev/null 2>&1; then
    echo "::error::$UNINSTALL_APP is still installed on $udid after simctl uninstall"
    exit 1
  fi
  echo "Uninstalled $UNINSTALL_APP from $udid (fresh install for the test host)"
fi

if [ $status -eq 0 ]; then
  SECONDS=0
  xcode -resultBundlePath "$result" test-without-building >> "$log" 2>&1
  status=$?
  echo "Timing: test-without-building took ${SECONDS}s"
else
  echo "::error::$scheme did not build, no tests ran"
fi

# Keep the console readable: test progress, our check logs, errors and the final verdict.
grep -E "^Test (Case|Suite)|^\[[a-z-]+\] |error:|\*\* TEST" "$log" || true
if [ $status -ne 0 ]; then
  echo "::error::$scheme failed (exit $status); the full log is in the xcodebuild-logs artifact"
  tail -n 60 "$log"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  python3 .github/scripts/xcresult-summary.py "$title" "$result" >> "$GITHUB_STEP_SUMMARY" || true
fi
exit $status
