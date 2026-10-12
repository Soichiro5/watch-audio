#!/usr/bin/env bash
# Builds sherpa-onnx's Android JNI library without text-to-speech (eSpeak NG, GPL-3.0) and speaker
# diarization, for one ABI (#173). Runs in CI (.github/workflows/sherpa-onnx-android.yml); needs the
# Android NDK named in gradle/sherpa-onnx-jni.properties (ANDROID_NDK), cmake, make and wget.
#
#   tools/sherpa-onnx-android/build-jni.sh <arm64-v8a|x86_64> <sherpa-onnx checkout> <output dir>
#
# Uses the upstream build script of the checked-out tag unchanged (same NDK, ONNX Runtime and CMake
# options as the JitPack AAR) except:
# - SHERPA_ONNX_ENABLE_TTS=OFF: no TTS code, eSpeak NG and piper-phonemize;
# - SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF: no diarization code and hclust-cpp;
# - SHERPA_ONNX_ENABLE_C_API=OFF: the C/C++ API libraries are not shipped (apps/android excludes them).
# The app keeps the AAR's libonnxruntime.so, so this checks that the library was linked against the
# same file (onnxruntime.sha256.<abi>), then checks the result with check-no-tts.sh.
# Writes <output dir>/libsherpa-onnx-jni-<abi>.so.
set -euo pipefail

abi=${1:?abi}
src=$(cd "${2:?sherpa-onnx checkout}" && pwd)
mkdir -p "${3:?output dir}"
out=$(cd "$3" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
props="$here/../../gradle/sherpa-onnx-jni.properties"

prop() { grep -E "^$1=" "$props" | head -n 1 | cut -d= -f2-; }

case "$abi" in
  arm64-v8a) script=build-android-arm64-v8a.sh; dir=build-android-arm64-v8a ;;
  x86_64) script=build-android-x86-64.sh; dir=build-android-x86-64 ;;
  *) echo "unsupported ABI: $abi" >&2; exit 2 ;;
esac

: "${ANDROID_NDK:?set ANDROID_NDK to the NDK $(prop ndk)}"
ndk_revision=$(grep -E '^Pkg.Revision' "$ANDROID_NDK/source.properties" | cut -d= -f2 | tr -d ' ')
[ "$ndk_revision" = "$(prop ndk)" ] || { echo "NDK $ndk_revision, expected $(prop ndk)" >&2; exit 1; }

commit=$(git -C "$src" rev-parse HEAD)
[ "$commit" = "$(prop commit)" ] || { echo "sherpa-onnx checkout is at $commit, expected $(prop commit)" >&2; exit 1; }

cd "$src"
rm -rf "$dir"
export ANDROID_NDK
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_JNI=ON
export SHERPA_ONNX_ENABLE_BINARY=OFF
"./$script"

lib="$dir/install/lib/libsherpa-onnx-jni.so"
ort="$dir/install/lib/libonnxruntime.so"
expected_ort=$(prop "onnxruntime.sha256.$abi")
actual_ort=$(sha256sum "$ort" | cut -d' ' -f1)
if [ "$actual_ort" != "$expected_ort" ]; then
  echo "linked against libonnxruntime.so $actual_ort, but the AAR ships $expected_ort" >&2
  exit 1
fi
echo "libonnxruntime.so ($abi) is the AAR's: $actual_ort"

"$here/check-no-tts.sh" "$lib"
cp -v "$lib" "$out/libsherpa-onnx-jni-$abi.so"
