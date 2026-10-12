#!/usr/bin/env bash
# Builds sherpa-onnx's Android JNI library without text-to-speech (eSpeak NG, GPL-3.0) and speaker
# diarization from the upstream tag, for every ABI the app ships (#173, docs/dev/04 §7.3). Not a fork:
# the upstream sources and build scripts of the tag are used unchanged; only CMake options differ.
# Run by the composite action .github/actions/sherpa-onnx-jni when its cache misses.
#
#   tools/sherpa-onnx-android/build-jni.sh <aar-info work dir> <output dir>
#
# <aar-info work dir> is the output of aar-info.sh (info.env: version = the tag, ABIs, NDK; the AAR's
# libraries). Options that differ from the upstream defaults (the JitPack AAR's build):
# - SHERPA_ONNX_ENABLE_TTS=OFF: no TTS code, eSpeak NG and piper-phonemize;
# - SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF: no diarization code and hclust-cpp;
# - SHERPA_ONNX_ENABLE_C_API=OFF: the C/C++ API libraries are not shipped (apps/android excludes them).
# The NDK is the one the AAR was built with: $ANDROID_NDK if set, else $ANDROID_HOME/ndk/<revision>,
# else downloaded from Google. Each library must link against the AAR's libonnxruntime.so (the app
# keeps that file) and pass check-no-tts.sh. Writes <output dir>/<abi>/libsherpa-onnx-jni.so and
# <output dir>/sherpa-onnx-jni.properties (what was built; the app build checks it). Needs git, cmake,
# make, wget, unzip and curl.
set -euo pipefail

info_dir=$(cd "${1:?aar-info work dir}" && pwd)
mkdir -p "${2:?output dir}"
out=$(cd "$2" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=/dev/null
. "$info_dir/info.env"
: "${version:?}" "${abis:?}" "${ndk:?}" "${ndk_release:?}"

work="$info_dir/build"
rm -rf "$work"
mkdir -p "$work"

if [ -z "${ANDROID_NDK:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$ndk" ]; then
    ANDROID_NDK="$ANDROID_HOME/ndk/$ndk"
  else
    url="https://dl.google.com/android/repository/android-ndk-$ndk_release-linux.zip"
    echo "Downloading $url"
    curl -fsSL --retry 3 -o "$work/ndk.zip" "$url"
    unzip -q "$work/ndk.zip" -d "$work"
    rm "$work/ndk.zip"
    ANDROID_NDK="$work/android-ndk-$ndk_release"
  fi
fi
ndk_revision=$(grep -E '^Pkg.Revision' "$ANDROID_NDK/source.properties" | cut -d= -f2 | tr -d ' ')
[ "$ndk_revision" = "$ndk" ] || { echo "NDK $ndk_revision at $ANDROID_NDK, but the AAR was built with $ndk" >&2; exit 1; }
echo "NDK $ndk_revision: $ANDROID_NDK"

git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$version" https://github.com/k2-fsa/sherpa-onnx "$work/sherpa-onnx"
src="$work/sherpa-onnx"
commit=$(git -C "$src" rev-parse HEAD)
echo "sherpa-onnx $version: $commit"

# Evidence that only TTS / diarization code depends on the switched-off options.
echo "Sources testing SHERPA_ONNX_ENABLE_TTS / SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION:"
grep -rlE 'SHERPA_ONNX_ENABLE_(TTS|SPEAKER_DIARIZATION)' "$src/sherpa-onnx" --include='*.cc' --include='*.h' \
  | sed "s|^$src/|  |" | sort

rm -rf "${out:?}"/*
meta="$out/sherpa-onnx-jni.properties"
{
  echo "# libsherpa-onnx-jni.so without TTS (#173), written by tools/sherpa-onnx-android/build-jni.sh"
  echo "version=$version"
  echo "commit=$commit"
  echo "ndk=$ndk"
  echo "abis=${abis// /,}"
  echo "options=SHERPA_ONNX_ENABLE_TTS=OFF,SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF,SHERPA_ONNX_ENABLE_C_API=OFF"
} > "$meta"

for abi in $abis; do
  case "$abi" in
    arm64-v8a) script=build-android-arm64-v8a.sh; dir=build-android-arm64-v8a ;;
    x86_64) script=build-android-x86-64.sh; dir=build-android-x86-64 ;;
    # Another ABI in apps/android's abiFilters: add its upstream build-android-*.sh here.
    *) echo "unsupported ABI: $abi" >&2; exit 2 ;;
  esac
  (
    cd "$src"
    export ANDROID_NDK
    export BUILD_SHARED_LIBS=ON
    export SHERPA_ONNX_ENABLE_TTS=OFF
    export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
    export SHERPA_ONNX_ENABLE_C_API=OFF
    export SHERPA_ONNX_ENABLE_JNI=ON
    export SHERPA_ONNX_ENABLE_BINARY=OFF
    "./$script"
  )
  lib="$src/$dir/install/lib/libsherpa-onnx-jni.so"
  ort_sha=$(sha256sum "$src/$dir/install/lib/libonnxruntime.so" | cut -d' ' -f1)
  aar_ort_sha=$(sha256sum "$info_dir/aar/$abi/libonnxruntime.so" | cut -d' ' -f1)
  if [ "$ort_sha" != "$aar_ort_sha" ]; then
    echo "$abi: linked against libonnxruntime.so $ort_sha, but the AAR ships $aar_ort_sha" >&2
    exit 1
  fi
  echo "libonnxruntime.so ($abi) is the AAR's: $ort_sha"
  "$here/check-no-tts.sh" "$lib"
  mkdir -p "$out/$abi"
  cp "$lib" "$out/$abi/libsherpa-onnx-jni.so"
  echo "onnxruntime.sha256.$abi=$ort_sha" >> "$meta"
  rm -rf "${src:?}/$dir"
done

rm -rf "$work"
echo "Built:"
cat "$meta"
