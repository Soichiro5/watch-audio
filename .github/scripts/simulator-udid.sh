#!/usr/bin/env bash
# Prints the UDID of the simulator the iOS CI steps use for a platform: the first device named
# <device name prefix>* on the newest runtime of that platform (the apps need iOS/watchOS 26+).
# Every simulator step picks its device through this script, so they all share one iPhone and
# one Apple Watch simulator (booted once by boot-simulators.sh, #92).
#
# Usage: simulator-udid.sh <iOS|watchOS> <device name prefix>
#   e.g. simulator-udid.sh iOS iPhone
# Exits 1 (and lists the available devices on stderr) when there is no such simulator.
set -euo pipefail

platform=$1
device_prefix=$2

udid=$(xcrun simctl list devices available --json | jq -r --arg p "$platform" --arg d "$device_prefix" '
  [.devices | to_entries | sort_by(.key) | reverse | .[]
   | select(.key | test("SimRuntime\\." + $p + "-"))
   | .value[] | select(.name | startswith($d))][0].udid')
if [ -z "$udid" ] || [ "$udid" = "null" ]; then
  xcrun simctl list devices available >&2
  echo "::error::no available $platform simulator named $device_prefix*" >&2
  exit 1
fi
echo "$udid"
