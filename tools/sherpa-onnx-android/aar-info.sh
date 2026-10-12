#!/usr/bin/env bash
# Derives what the sherpa-onnx JNI build without text-to-speech needs from the repository and the
# JitPack AAR the app uses (#173, docs/dev/04-dev-environment.md §7.3). Nothing here is pinned by hand:
# - version: `sherpa-onnx` in gradle/libs.versions.toml (also the upstream tag that is built);
# - ABIs: `abiFilters` of apps/android/build.gradle.kts;
# - NDK: the `.note.android.ident` of the AAR's libsherpa-onnx-jni.so (the NDK upstream built it with).
#
#   tools/sherpa-onnx-android/aar-info.sh <work dir>
#
# Writes <work dir>/info.env (shell variables, for `. info.env`) and prints it:
#   version, abis (space-separated), ndk_release (e.g. r29), ndk (SDK package revision, e.g. 29.0.14206865)
# Extracts the AAR's jni/<abi>/libsherpa-onnx-jni.so and libonnxruntime.so to <work dir>/aar/<abi>/.
# The AAR is taken from the Gradle cache when present, else downloaded from JitPack (the repository
# settings.gradle.kts declares for it). Needs curl, unzip and python3.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
work=${1:?work dir}
mkdir -p "$work"
work=$(cd "$work" && pwd)

toml="$root/gradle/libs.versions.toml"
version=$(sed -nE 's/^sherpa-onnx *= *"([^"]+)".*/\1/p' "$toml")
module=$(sed -nE 's/^sherpa-onnx-android *= *\{ *module *= *"([^"]+)".*/\1/p' "$toml")
[ -n "$version" ] && [ -n "$module" ] || { echo "sherpa-onnx / sherpa-onnx-android not found in $toml" >&2; exit 1; }
group=${module%%:*}
artifact=${module#*:}

abis=$(sed -nE 's/.*abiFilters *\+= *listOf\((.*)\).*/\1/p' "$root/apps/android/build.gradle.kts" | tr -d '" ' | tr ',' ' ')
[ -n "$abis" ] || { echo "abiFilters not found in apps/android/build.gradle.kts" >&2; exit 1; }

gradle_home=${GRADLE_USER_HOME:-$HOME/.gradle}
aar=$(find "$gradle_home/caches/modules-2/files-2.1/$group/$artifact/$version" -name "$artifact-$version.aar" 2>/dev/null | head -n 1 || true)
if [ -z "$aar" ]; then
  aar="$work/$artifact-$version.aar"
  url="https://jitpack.io/${group//.//}/$artifact/$version/$artifact-$version.aar"
  echo "Downloading $url" >&2
  curl -fsSL --retry 3 -o "$aar" "$url"
fi

rm -rf "$work/aar"
for abi in $abis; do
  mkdir -p "$work/aar/$abi"
  for lib in libsherpa-onnx-jni.so libonnxruntime.so; do
    unzip -p "$aar" "jni/$abi/$lib" > "$work/aar/$abi/$lib"
    [ -s "$work/aar/$abi/$lib" ] || { echo "$aar has no jni/$abi/$lib" >&2; exit 1; }
  done
done

# struct { u32 namesz=8, descsz=0x84, type=1; "Android\0"; u32 api; char release[64]; char build[64]; }
read -r ndk_release ndk_build < <(python3 -I - "$work/aar/${abis%% *}/libsherpa-onnx-jni.so" <<'EOF'
import struct, sys
data = open(sys.argv[1], "rb").read()
header = struct.pack("<III", 8, 0x84, 1) + b"Android\0"
i = data.find(header)
if i < 0:
    sys.exit("no .note.android.ident in " + sys.argv[1])
desc = data[i + len(header) + 4 : i + len(header) + 4 + 128]
print(desc[:64].split(b"\0")[0].decode(), desc[64:].split(b"\0")[0].decode())
EOF
)
# r29 -> 29.0.<build>, r27c -> 27.2.<build> (the letter is the minor revision).
major=${ndk_release#r}
major=${major%%[a-z]*}
suffix=${ndk_release#r"$major"}
minor=0
[ -z "$suffix" ] || minor=$(( $(printf '%d' "'$suffix") - $(printf '%d' "'a") ))
ndk="$major.$minor.$ndk_build"

{
  echo "version='$version'"
  echo "abis='$abis'"
  echo "ndk_release='$ndk_release'"
  echo "ndk='$ndk'"
} | tee "$work/info.env"
