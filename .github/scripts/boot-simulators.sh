#!/usr/bin/env bash
# Starts booting the iPhone and Apple Watch simulators in the background and returns at once
# (#92). A first boot spends 1-2 min in data migration; started before the Xcode builds, it is
# done by the time the simulator steps need it. Those steps still run `simctl boot` (a no-op on a
# booted device) and `simctl bootstatus -b`, so they wait for the boot and also work without this.
#
# Do not call this before `./gradlew iosSimulatorArm64Test`: the Kotlin tests run in standalone
# mode (`simctl spawn --standalone`), which boots and shuts down its own device.
#
# Usage: boot-simulators.sh   Env: OUT_DIR (default simulator-smoke) for the boot logs.
set -euo pipefail

out_dir=${OUT_DIR:-simulator-smoke}
mkdir -p "$out_dir"
scripts=$(cd "$(dirname "$0")" && pwd)

for spec in "iOS:iPhone" "watchOS:Apple Watch"; do
  platform=${spec%%:*}
  prefix=${spec#*:}
  udid=$("$scripts/simulator-udid.sh" "$platform" "$prefix")
  label=$(echo "$platform" | tr '[:upper:]' '[:lower:]')
  echo "Booting $platform simulator $udid in the background (log: $out_dir/boot-$label.txt)"
  nohup bash -c "date -u +%T; xcrun simctl boot '$udid'; xcrun simctl bootstatus '$udid'; date -u +%T" \
    > "$out_dir/boot-$label.txt" 2>&1 &
done
