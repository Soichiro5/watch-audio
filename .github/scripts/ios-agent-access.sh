#!/usr/bin/env bash
# Agent access on the iPhone simulator against the real bridge CLI on the CI host (#185), called by
# ios.yml. The simulator shares the host's network, so the CLI is reached at 127.0.0.1.
#
#   1. pairing: the host runs `watch-audio-mcp pair --address 127.0.0.1` (emulator-host.js pair) and
#      prints the pairing string; AgentAccessTests/testPair (in the app process) seeds a transcript,
#      pairs with it and confirms the 6-digit code; the host answers y. Both codes must match.
#   2. serving: the host starts the CLI as an MCP server (with mDNS) and calls search_transcripts
#      every second (emulator-host.js serve); AgentAccessTests/testServe turns access on, off, on,
#      sends the app to the background (access turns off), on, and revokes the pairing. The host must
#      see answers only while it is on, on screen and paired: connected / disconnected six times.
#
# Needs tools/agent-bridge built (npm ci && npm run build) and the iOS app built into DERIVED_DATA
# (the "Build iOS app" step). Extra xcodebuild settings are passed through (e.g.
# OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES). Evidence: AGENT_HOST / AGENT_CHECK lines in
# $OUT/summary.txt and the job summary, build/swift-evidence/agent-access-*.txt.
# Exit status 1 if a check failed.
set -uo pipefail

extra_settings=("$@")
derived_data=${DERIVED_DATA:-build/DerivedData}
results_dir=${RESULTS_DIR:-build/xcresults}
OUT=${OUT:-agent-access-logs}
HOST_JS=tools/agent-bridge/dist/e2e/emulator-host.js
evidence=build/swift-evidence
mkdir -p "$OUT" "$results_dir" "$evidence"
status=0
home=$(mktemp -d)
trap 'rm -rf "$home"; jobs -p | xargs -r kill 2>/dev/null' EXIT

log() { echo "$*" | tee -a "$OUT/summary.txt"; }

if [ ! -f "$HOST_JS" ]; then
  log "SCENARIO ios-agent-access FAILED ($HOST_JS missing: build tools/agent-bridge first)"
  exit 1
fi

udid=$("$(dirname "$0")/simulator-udid.sh" iOS iPhone) || exit 1
xcrun simctl boot "$udid" > /dev/null 2>&1 || true
xcrun simctl bootstatus "$udid" -b > /dev/null
echo "Simulator: $udid"

# The app keeps the phone's key in the Keychain, which the simulator refuses to an unsigned app
# (errSecMissingEntitlement, -34018; the other steps build with CODE_SIGNING_ALLOWED=NO). Sign
# ad hoc for this check with a Keychain access group written here: CI only, nothing is committed.
# On a device the signed app's own application identifier is its access group (no entitlement needed).
entitlements="$PWD/$OUT/agent-simulator.entitlements"
cat > "$entitlements" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>application-identifier</key>
	<string>app.watchaudio</string>
	<key>keychain-access-groups</key>
	<array>
		<string>app.watchaudio</string>
	</array>
</dict>
</plist>
PLIST
signing=(CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= "CODE_SIGN_ENTITLEMENTS=$entitlements")

xcode() {
  xcodebuild -project apps/ios/WatchAudio.xcodeproj -scheme AgentAccessTests \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived_data" \
    "${signing[@]}" ${extra_settings[@]+"${extra_settings[@]}"} "$@"
}

# run_test <name> <test method> [ENV=value...]: one test method; it must pass.
run_test() {
  local name=$1 method=$2
  shift 2
  local result="$results_dir/AgentAccessTests-$name.xcresult"
  rm -rf "$result"
  env "$@" xcodebuild -project apps/ios/WatchAudio.xcodeproj -scheme AgentAccessTests \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived_data" \
    "${signing[@]}" ${extra_settings[@]+"${extra_settings[@]}"} \
    -only-testing:"AgentAccessTests/AgentAccessTests/$method" -resultBundlePath "$result" \
    test-without-building > "$OUT/xcodebuild-$name.log" 2>&1
  local test_status=$?
  grep -E "^Test (Case|Suite)|^\[agent-access-[a-z]+\] |error:|\*\* TEST" "$OUT/xcodebuild-$name.log" || true
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    python3 .github/scripts/xcresult-summary.py "Agent access on the iOS simulator: $method (#185)" "$result" >> "$GITHUB_STEP_SUMMARY" || true
  fi
  if [ $test_status -eq 0 ] && grep -q "Executed 1 test, with 0 failures" "$OUT/xcodebuild-$name.log" && ! grep -q "skipped" <(grep "Test Case.*$method" "$OUT/xcodebuild-$name.log"); then
    log "SCENARIO ios-agent-$name passed"
  else
    log "SCENARIO ios-agent-$name FAILED (exit $test_status, see $OUT/xcodebuild-$name.log)"
    tail -n 40 "$OUT/xcodebuild-$name.log"
    status=1
  fi
}

SECONDS=0
if ! xcode build-for-testing > "$OUT/xcodebuild-build.log" 2>&1; then
  log "SCENARIO ios-agent-build FAILED (see $OUT/xcodebuild-build.log)"
  grep -E "error:" "$OUT/xcodebuild-build.log" | head -n 40
  tail -n 40 "$OUT/xcodebuild-build.log"
  exit 1
fi
echo "Timing: build-for-testing took ${SECONDS}s"

# A fresh install of the host app (see xcode-test.sh): empty database. The Keychain of the simulator
# keeps the phone's key across installs, which the check does not depend on.
xcrun simctl terminate "$udid" app.watchaudio 2>/dev/null || true
xcrun simctl uninstall "$udid" app.watchaudio 2>/dev/null || true

# 1. Pairing.
AGENT_HOST_ADDRESS=127.0.0.1 node "$HOST_JS" pair "$home" > "$OUT/agent-host-pair.txt" 2> "$OUT/agent-host-pair-stderr.txt" &
pair_pid=$!
uri=""
for _ in $(seq 1 300); do
  uri=$(sed -n 's/^URI //p' "$OUT/agent-host-pair.txt" | head -n 1)
  [ -n "$uri" ] && break
  sleep 0.1
done
if [ -z "$uri" ]; then
  log "SCENARIO ios-agent-pair FAILED (the CLI printed no pairing string)"
  cat "$OUT/agent-host-pair-stderr.txt"
  exit 1
fi
log "AGENT_HOST phase=pair uri_prefix=${uri%%&t=*}"
run_test pair testPair TEST_RUNNER_AGENT_PAIRING_URI="$uri"
# If the phone never got as far as the code, `pair` would wait out its 5 minutes.
for _ in $(seq 1 30); do
  kill -0 "$pair_pid" 2>/dev/null || break
  sleep 1
done
kill "$pair_pid" 2>/dev/null
wait "$pair_pid"
pair_status=$?
pc_code=$(sed -n 's/^PC_CODE //p' "$OUT/agent-host-pair.txt" | head -n 1)
phone_code=$(grep -ho "AGENT_CHECK phase=paired code=[0-9]*" "$evidence/agent-access-pair.txt" 2>/dev/null | head -n 1 | sed 's/.*code=//')
log "AGENT_HOST phase=paired exit=$pair_status pc_code=$pc_code phone_code=${phone_code:-?}"
if [ "$pair_status" -ne 0 ] || [ -z "$pc_code" ] || [ "$pc_code" != "$phone_code" ]; then
  log "SCENARIO ios-agent-pair-host FAILED (pair exit $pair_status, codes PC=$pc_code phone=${phone_code:-?})"
  cat "$OUT/agent-host-pair-stderr.txt"
  exit 1
fi

# 2. Serving: on, off, on, background, on, revoked.
AGENT_HOST_PHASES=6 AGENT_HOST_MDNS=1 node "$HOST_JS" serve "$home" > "$OUT/agent-host-serve.txt" 2> "$OUT/agent-host-serve-stderr.txt" &
serve_pid=$!
sleep 2
run_test serve testServe TEST_RUNNER_AGENT_SERVE=1
# The host exits after its last phase; give it a little time after the test ended.
for _ in $(seq 1 30); do
  kill -0 "$serve_pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$serve_pid" 2>/dev/null; then
  kill "$serve_pid"
  serve_status=124
else
  wait "$serve_pid"
  serve_status=$?
fi
sed 's/^/AGENT_HOST serve: /' "$OUT/agent-host-serve.txt" | tee -a "$OUT/summary.txt"
if [ "$serve_status" -ne 0 ]; then
  log "SCENARIO ios-agent-serve-host FAILED (exit $serve_status; see $OUT/agent-host-serve*.txt)"
  tail -n 30 "$OUT/agent-host-serve-stderr.txt"
  status=1
else
  log "SCENARIO ios-agent-serve-host passed"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "Agent access on the iOS simulator against the bridge CLI on the host (#185):"
    echo '```'
    cat "$OUT/summary.txt"
    cat "$evidence"/agent-access-*.txt 2>/dev/null
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi
exit $status
