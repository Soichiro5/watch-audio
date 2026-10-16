# shellcheck shell=bash
# Steps of the agent bridge CLI (tools/agent-bridge, #182-#185): agent-bridge.yml, and the CLI build
# that emulator.yml and ios.yml use for their agent access checks. Sourced by check.sh. Runs under
# Git Bash on Windows too (agent-bridge.yml's windows-2025 job).

# No install scripts: every dependency is plain JavaScript.
npm_ci() {
  (cd tools/agent-bridge && npm ci --ignore-scripts --no-audit --no-fund)
}

# agent-bridge.yml cli: `npm ci` (and e2e "Bridge CLI").
step_bridge_npm_ci() {
  npm_ci
}

# agent-bridge.yml cli: `npm run lint` (type-check).
step_bridge_lint() {
  (cd tools/agent-bridge && npm run lint)
}

# agent-bridge.yml cli: `npm test` (wire fixtures shared with Kotlin, certificates, pairing, TLS
# refusals, MCP over stdio).
step_bridge_test() {
  (cd tools/agent-bridge && npm test)
}

# agent-bridge.yml e2e "Shared agent logic (JVM tests) and the fake phone".
step_bridge_fake_phone() {
  gw :shared:agent:jvmTest :shared:db:jvmTest :tools:agent-fake-phone:installDist
}

# agent-bridge.yml e2e "End to end: bridge CLI + fake phone".
step_bridge_e2e() {
  local fake="$CHECK_ROOT/tools/agent-fake-phone/build/install/agent-fake-phone/bin/agent-fake-phone"
  [ -x "$fake" ] || fail "$fake missing: run the bridge-fake-phone step first"
  [ -d tools/agent-bridge/node_modules ] || npm_ci
  (cd tools/agent-bridge && FAKE_PHONE="$fake" npm run test:e2e)
}

# emulator.yml / ios.yml "Build the agent bridge CLI" (the host side of the agent access checks).
step_bridge_build() {
  npm_ci
  (cd tools/agent-bridge && npm run build)
}
