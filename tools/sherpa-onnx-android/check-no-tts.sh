#!/usr/bin/env bash
# Fails if a sherpa-onnx JNI library contains text-to-speech or speaker diarization code (#173).
#
#   tools/sherpa-onnx-android/check-no-tts.sh <app.apk | libsherpa-onnx-jni.so>...
#
# The JitPack AAR's libsherpa-onnx-jni.so statically links eSpeak NG (GPL-3.0) for TTS. The app
# ships a build without it (docs/dev/04-dev-environment.md §7.3). Markers:
# - eSpeak NG's data file names (espeak-ng-data, phontab, phonindex);
# - the JNI entry points of the Kotlin TTS and speaker diarization classes (exported names).
# An APK must contain libsherpa-onnx-jni.so for at least one ABI; each one is checked.
# Used by build-jni.sh and .github/actions/sherpa-onnx-jni (the built .so) and by the workflows that
# build distributable or release-test APKs (android.yml, deliver.yml, emulator.yml) on those APKs.
# The Gradle task VerifySherpaOnnxJniTask (build-logic) checks the same markers.
set -euo pipefail

MARKERS='espeak-ng-data|phontab|phonindex|Java_com_k2fsa_sherpa_onnx_OfflineTts|Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization'

[ $# -gt 0 ] || { echo "usage: $0 <app.apk | libsherpa-onnx-jni.so>..." >&2; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failed=0
# check_lib <label> <file>: prints the markers found (at most 5) and records a failure.
check_lib() {
  local label=$1 file=$2 found
  found=$(grep -a -o -E "$MARKERS" "$file" | sort | uniq -c | sort -rn | head -n 5 || true)
  if [ -n "$found" ]; then
    echo "FAIL $label ($(wc -c < "$file") bytes) contains TTS / diarization code:"
    echo "$found" | sed 's/^/  /'
    failed=1
  else
    echo "OK   $label ($(wc -c < "$file") bytes, sha256 $(sha256sum "$file" | cut -d' ' -f1)): no eSpeak NG / TTS / diarization markers"
  fi
}

for input in "$@"; do
  case "$input" in
    *.apk)
      entries=$(unzip -Z1 "$input" | grep -E '^lib/[^/]+/libsherpa-onnx-jni\.so$' || true)
      if [ -z "$entries" ]; then
        echo "FAIL $input: no lib/<abi>/libsherpa-onnx-jni.so"
        failed=1
        continue
      fi
      for entry in $entries; do
        out="$work/$(echo "$entry" | tr '/' '_')"
        unzip -p "$input" "$entry" > "$out"
        check_lib "$(basename "$input")!$entry" "$out"
      done
      ;;
    *)
      check_lib "$input" "$input"
      ;;
  esac
done

exit $failed
