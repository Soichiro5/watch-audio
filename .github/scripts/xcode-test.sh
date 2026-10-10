#!/usr/bin/env bash
# Runs an Xcode test scheme (iosApp/project.yml) on the newest available iPhone simulator and
# lists every test case, with skip reasons and failure messages, in the job summary.
#
# Usage: xcode-test.sh <scheme> <summary title>
#   e.g. xcode-test.sh SwiftUnitTests "Swift unit tests on the iOS simulator"
# Env: DERIVED_DATA (default build/DerivedData, shared with the build steps so a hosted test
#      reuses the app build), RESULTS_DIR (default build/xcresults).
# Exits with xcodebuild's status (skipped tests do not fail it).
set -uo pipefail

scheme=$1
title=$2
derived_data=${DERIVED_DATA:-build/DerivedData}
results_dir=${RESULTS_DIR:-build/xcresults}
result="$results_dir/$scheme.xcresult"
log="xcodebuild-$scheme.log"
mkdir -p "$results_dir"
rm -rf "$result"

# Newest iOS runtime first: the apps and tests need iOS 26+.
udid=$(xcrun simctl list devices available --json | jq -r '
  [.devices | to_entries | sort_by(.key) | reverse | .[]
   | select(.key | test("SimRuntime\\.iOS-"))
   | .value[] | select(.name | startswith("iPhone"))][0].udid')
if [ -z "$udid" ] || [ "$udid" = "null" ]; then
  xcrun simctl list devices available
  echo "::error::no available iPhone simulator"
  exit 1
fi
echo "Simulator: $udid"

xcodebuild -project iosApp/WatchAudio.xcodeproj -scheme "$scheme" \
  -destination "platform=iOS Simulator,id=$udid" \
  -derivedDataPath "$derived_data" -resultBundlePath "$result" \
  CODE_SIGNING_ALLOWED=NO test > "$log" 2>&1
status=$?

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
