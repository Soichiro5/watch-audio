#!/usr/bin/env bash
# Agent access on the phone emulator against the real bridge CLI on the CI host (#184), called by
# phone-scenarios.sh. The emulator reaches the host's loopback at 10.0.2.2; the CLI listens there.
#
#   1. pairing: the host runs `watch-audio-mcp pair --address 10.0.2.2` (emulator-host.js pair) and
#      prints the pairing string; AgentAccessScenarioTest#pair enters it on the 「エージェント連携」
#      screen, shows the 6-digit code and confirms; the host answers y. Both codes must match.
#   2. serving: the host starts the CLI as an MCP server and calls search_transcripts every second
#      (emulator-host.js serve); AgentAccessScenarioTest#serve turns access on (foreground service),
#      off, on again and revokes the pairing. The host must see answers only while it is on and
#      paired: connected, disconnected, connected, disconnected.
#
# Needs tools/agent-bridge built (npm ci && npm run build). Evidence: AGENT_CHECK / AGENT_HOST lines
# in scenario-logs/summary.txt, screenshots agent-*.png. Exit status 1 if a check failed.
set -uo pipefail

RUNNER=app.watchaudio.test/androidx.test.runner.AndroidJUnitRunner
OUT=${OUT:-scenario-logs}
HOST_JS=tools/agent-bridge/dist/e2e/emulator-host.js
mkdir -p "$OUT"
status=0
home=$(mktemp -d)
trap 'rm -rf "$home"; jobs -p | xargs -r kill 2>/dev/null' EXIT

log() { echo "$*" | tee -a "$OUT/summary.txt"; }

# instrument <name> <am instrument args...>: one test method; it must pass.
instrument() {
  local name=$1
  shift
  adb shell am instrument -w "$@" "$RUNNER" > "$OUT/$name.txt" 2>&1
  cat "$OUT/$name.txt"
  if grep -q "^OK (1 test)" "$OUT/$name.txt" && ! grep -q "FAILURES" "$OUT/$name.txt"; then
    log "SCENARIO $name passed"
  else
    log "SCENARIO $name FAILED (see $OUT/$name.txt)"
    status=1
  fi
}

if [ ! -f "$HOST_JS" ]; then
  log "SCENARIO agent-access FAILED ($HOST_JS missing: build tools/agent-bridge first)"
  exit 1
fi

# 1. Pairing.
node "$HOST_JS" pair "$home" > "$OUT/agent-host-pair.txt" 2> "$OUT/agent-host-pair-stderr.txt" &
pair_pid=$!
uri=""
for _ in $(seq 1 300); do
  uri=$(sed -n 's/^URI //p' "$OUT/agent-host-pair.txt" | head -n 1)
  [ -n "$uri" ] && break
  sleep 0.1
done
if [ -z "$uri" ]; then
  log "SCENARIO agent-pair FAILED (the CLI printed no pairing string)"
  cat "$OUT/agent-host-pair-stderr.txt"
  exit 1
fi
log "AGENT_HOST phase=pair uri_prefix=${uri%%&t=*}"
encoded=$(printf %s "$uri" | base64 -w0 | tr '+/' '-_' | tr -d '=')
instrument agent-pair -e class app.watchaudio.android.AgentAccessScenarioTest#pair -e scenario agent-pair -e pairingUri "$encoded"
wait "$pair_pid"
pair_status=$?
pc_code=$(sed -n 's/^PC_CODE //p' "$OUT/agent-host-pair.txt" | head -n 1)
phone_code=$(grep -ho "AGENT_CHECK phase=paired code=[0-9]*" "$OUT/agent-pair.txt" <(adb logcat -d -s EmulatorEvidence 2>/dev/null) | head -n 1 | sed 's/.*code=//')
log "AGENT_HOST phase=paired exit=$pair_status pc_code=$pc_code phone_code=${phone_code:-?}"
if [ "$pair_status" -ne 0 ] || [ -z "$pc_code" ] || [ "$pc_code" != "$phone_code" ]; then
  log "SCENARIO agent-pair-host FAILED (pair exit $pair_status, codes PC=$pc_code phone=${phone_code:-?})"
  status=1
fi

# 2. Serving while on, then off, on again, revoked.
node "$HOST_JS" serve "$home" > "$OUT/agent-host-serve.txt" 2> "$OUT/agent-host-serve-stderr.txt" &
serve_pid=$!
sleep 2
instrument agent-serve -e class app.watchaudio.android.AgentAccessScenarioTest#serve -e scenario agent-serve
# The host exits after its fourth phase; give it a little time after the test ended.
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
  log "SCENARIO agent-serve-host FAILED (exit $serve_status; see $OUT/agent-host-serve*.txt)"
  status=1
else
  log "SCENARIO agent-serve-host passed"
fi
exit $status
