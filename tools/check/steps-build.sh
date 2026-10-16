# shellcheck shell=bash
# Steps of the Linux build jobs: android.yml, deliver.yml, links.yml, the sherpa-onnx JNI action and
# emulator.yml's release-test-apks job. Sourced by check.sh; every function runs from the repository
# root in a subshell with `set -eo pipefail`. docs/dev/04 「手元での確認」 maps each to its CI step.

# android.yml "Version check": gradle.properties = apps/ios/project.yml (#138).
step_version() {
  python3 -m unittest discover -s tools/check-version -v
  python3 tools/check-version/check_version.py
}

# android.yml "Local-only check (static)" (#76).
step_privacy_static() {
  python3 -m unittest discover -s tools/privacy-check -v
  python3 tools/privacy-check/check.py
}

# links.yml "Markdown link check" (#49).
step_links() {
  python3 -m unittest discover -s tools/check-links -v
  python3 tools/check-links/check_links.py
}

# links.yml "CI and local checks stay in step" (#202): every workflow run step goes through tools/check.
step_parity() {
  python3 -m unittest discover -s tools/check -p 'test_*.py' -v
}

# --- sherpa-onnx JNI without TTS (#173, .github/actions/sherpa-onnx-jni) -------------------------

# sherpa-onnx-jni-info <work dir>: version, ABIs and NDK of the AAR; prints the cache key (output `key`).
step_sherpa_onnx_jni_info() {
  local work=${1:?work dir}
  tools/sherpa-onnx-android/aar-info.sh "$work"
  # shellcheck source=/dev/null
  . "$work/info.env"
  local script
  script=$(sha256_of tools/sherpa-onnx-android/build-jni.sh | cut -c1-16)
  # shellcheck disable=SC2154
  local key="sherpa-onnx-jni-$version-ndk$ndk-$(printf '%s' "$abis" | tr ' ' '+')-$script"
  echo "key=$key"
  set_output key "$key"
}

# sherpa-onnx-jni-build <work dir> <output dir>: builds the library (the cache missed).
step_sherpa_onnx_jni_build() {
  tools/sherpa-onnx-android/build-jni.sh "${1:?work dir}" "${2:?output dir}"
}

# sherpa-onnx-jni-verify <work dir> <library dir>: same version and NDK as the AAR, linked against
# the AAR's libonnxruntime.so, no TTS markers; then the later steps pack it
# (WATCHAUDIO_SHERPA_ONNX_JNI_DIR, output `dir`).
step_sherpa_onnx_jni_verify() {
  local aar=${1:?work dir} dir=${2:?library dir}
  aar=$(cd "$aar" && pwd)
  dir=$(cd "$dir" && pwd)
  # shellcheck source=/dev/null
  . "$aar/info.env"
  prop() { sed -n "s/^$1=//p" "$dir/sherpa-onnx-jni.properties"; }
  # shellcheck disable=SC2154
  [ "$(prop version)" = "$version" ] || fail "built from $(prop version), the AAR is $version"
  # shellcheck disable=SC2154
  [ "$(prop ndk)" = "$ndk" ] || fail "built with NDK $(prop ndk), the AAR with $ndk"
  local status=0 abi aar_ort f
  # shellcheck disable=SC2154
  for abi in $abis; do
    aar_ort=$(sha256_of "$aar/aar/$abi/libonnxruntime.so")
    if [ "$(prop "onnxruntime.sha256.$abi")" = "$aar_ort" ]; then
      echo "OK   $abi: linked against the AAR's libonnxruntime.so ($aar_ort)"
    else
      echo "FAIL $abi: linked against libonnxruntime.so $(prop "onnxruntime.sha256.$abi"), the AAR ships $aar_ort"
      status=1
    fi
    [ -f "$dir/$abi/libsherpa-onnx-jni.so" ] || { echo "FAIL $abi: no libsherpa-onnx-jni.so"; status=1; }
  done
  tools/sherpa-onnx-android/check-no-tts.sh "$dir"/*/libsherpa-onnx-jni.so || status=1
  {
    echo "### sherpa-onnx JNI without TTS (#173)"
    echo
    echo "- sherpa-onnx $version ($(prop commit)), NDK $ndk, $(prop options)"
    echo "- cache: $([ "${CACHE_HIT:-}" = "true" ] && echo "hit" || echo "miss (built in this job)")"
    for abi in $abis; do
      f="$dir/$abi/libsherpa-onnx-jni.so"
      [ -f "$f" ] && echo "- $abi/libsherpa-onnx-jni.so: $(wc -c < "$f" | tr -d ' ') bytes, sha256 \`$(sha256_of "$f")\`"
    done
  } >> "$CHECK_SUMMARY"
  rm -rf "$aar"
  [ $status -eq 0 ] || exit 1
  export_env WATCHAUDIO_SHERPA_ONNX_JNI_DIR "$dir"
  set_output dir "$dir"
}

# sherpa-onnx-jni (local only; CI uses the composite action with the three steps above and its
# cache): the same library, cached under $WATCHAUDIO_CACHE_DIR/sherpa-onnx-jni/<key>, so that the
# APKs of this run are the ones CI builds and check-no-tts can run on them.
step_sherpa_onnx_jni() {
  if [ -n "${WATCHAUDIO_SHERPA_ONNX_JNI_DIR:-}" ] && [ -f "$WATCHAUDIO_SHERPA_ONNX_JNI_DIR/sherpa-onnx-jni.properties" ]; then
    echo "Using WATCHAUDIO_SHERPA_ONNX_JNI_DIR=$WATCHAUDIO_SHERPA_ONNX_JNI_DIR"
    export_env WATCHAUDIO_SHERPA_ONNX_JNI_DIR "$WATCHAUDIO_SHERPA_ONNX_JNI_DIR"
    return 0
  fi
  [ "${CHECK_NO_JNI:-0}" = "1" ] && skip "--no-jni: the APKs pack the AAR's library (with TTS); check-no-tts is left out"
  local work="$CHECK_OUT/sherpa-onnx-aar" key
  rm -rf "$work"
  key=$(step_sherpa_onnx_jni_info "$work" | sed -n 's/^key=//p' | tail -n 1)
  [ -n "$key" ] || fail "no cache key"
  local cached="$WATCHAUDIO_CACHE_DIR/sherpa-onnx-jni/$key"
  if [ -f "$cached/sherpa-onnx-jni.properties" ]; then
    echo "Cached: $cached"
    CACHE_HIT=true step_sherpa_onnx_jni_verify "$work" "$cached"
    return 0
  fi
  # build-jni.sh downloads the Linux NDK from Google (or uses $SHERPA_ONNX_ANDROID_NDK, e.g. an NDK of
  # the same revision installed with sdkmanager on macOS).
  # shellcheck source=/dev/null
  . "$work/info.env"
  if [ "$CHECK_OS" != "linux" ] && [ "$CHECK_OS" != "wsl" ] && [ -z "${SHERPA_ONNX_ANDROID_NDK:-}" ]; then
    # shellcheck disable=SC2154
    local sdk_ndk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$ndk"
    if [ -f "$sdk_ndk/source.properties" ] && [ "$CHECK_OS" = "macos" ]; then
      export SHERPA_ONNX_ANDROID_NDK=$sdk_ndk
    else
      rm -rf "$work"
      skip "the JNI build needs Linux/WSL, or on macOS the NDK $ndk (sdkmanager \"ndk;$ndk\") or SHERPA_ONNX_ANDROID_NDK"
    fi
  fi
  for tool in git cmake make unzip curl; do
    have "$tool" || { rm -rf "$work"; skip "the JNI build needs $tool"; }
  done
  # NDK (~2.5 GB unpacked, plus the zip on Linux) and the sherpa-onnx build tree; removed afterwards.
  local need=$((5 * 1024 * 1024)) free
  free=$(free_kib "$work")
  if [ "${free:-0}" -lt "$need" ]; then
    rm -rf "$work"
    skip "the JNI build needs ~5 GB free in ${work#"$CHECK_ROOT"/} (free: $(human_kib "${free:-0}")); CI builds it (android.yml)"
  fi
  mkdir -p "$cached.tmp"
  tools/sherpa-onnx-android/build-jni.sh "$work" "$cached.tmp"
  rm -rf "$cached"
  mv "$cached.tmp" "$cached"
  step_sherpa_onnx_jni_verify "$work" "$cached"
}

# no_tts_check <files...>: check-no-tts.sh on APKs. Locally without the JNI library (skipped step or
# --no-jni) the APKs pack the AAR's library, so the check is left out with a note; never in CI.
no_tts_check() {
  if ! is_ci && [ -z "${WATCHAUDIO_SHERPA_ONNX_JNI_DIR:-}" ]; then
    note "check-no-tts left out: no JNI library without TTS in this run (step sherpa-onnx-jni)"
    return 0
  fi
  tools/sherpa-onnx-android/check-no-tts.sh "$@"
}

# --- android.yml -------------------------------------------------------------------------------

# "Unit tests (shared: JVM + Android host; ONNX Runtime binding; phone and Wear OS app JVM tests)".
step_unit_tests() {
  gw jvmTest testAndroidHostTest :shared:ort:test :apps:android:testDebugUnitTest :apps:wear:testDebugUnitTest
}

# "Search benchmark (3.2M segments, target 300 ms)" (#23, #72). BENCHMARK_SEGMENTS (default 3200000)
# only for a smaller local run; the summary says how many segments ran.
step_search_benchmark() {
  local segments=${BENCHMARK_SEGMENTS:-3200000} status=0
  [ "$segments" = "3200000" ] || note "benchmark with $segments segments (CI: 3200000)"
  gw :shared:db:jvmBenchmark -Pbenchmark.segments="$segments" || status=$?
  cat shared/db/build/reports/search-benchmark/summary.md >> "$CHECK_SUMMARY" || true
  return $status
}

# "Lint".
step_lint() {
  gw :apps:android:lintDebug :apps:wear:lintDebug
}

# "Assemble phone + Wear OS" (android.yml) and "Build phone and Wear OS apps" (deliver.yml).
step_assemble_debug() {
  gw :apps:android:assembleDebug :apps:wear:assembleDebug
}

# "Local-only check (merged manifests, resolved dependencies)" (#76).
step_privacy_merged() {
  mkdir -p build/privacy-check
  gw :apps:android:processReleaseMainManifest :apps:wear:processReleaseMainManifest
  local app
  for app in android wear; do
    gw -q :apps:$app:dependencies --configuration releaseRuntimeClasspath > build/privacy-check/deps-$app.txt
  done
  python3 tools/privacy-check/check.py --summary "$CHECK_SUMMARY" \
    --merged-manifest apps/android=apps/android/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml \
    --merged-manifest apps/wear=apps/wear/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml \
    --resolved-deps apps/android=build/privacy-check/deps-android.txt \
    --resolved-deps apps/wear=build/privacy-check/deps-wear.txt
}

# "Assemble release (R8) + debug input leak check" (#78, #57). Signed when RELEASE_KEYSTORE_BASE64
# (CI secret) is set: decoded into the runner's temp dir (the workflow removes it in any case), or
# when the WATCHAUDIO_RELEASE_* variables already name a keystore (docs/dev/04 §7.1); else unsigned.
# Output `signed=true` when signed with the CI secret. Secret values are never printed.
step_assemble_release() {
  local signing="unsigned (release key secrets are not set)"
  if [ -n "${RELEASE_KEYSTORE_BASE64:-}" ]; then
    local keystore="${RUNNER_TEMP:-$CHECK_OUT}/release.jks"
    printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 -d > "$keystore"
    is_ci || trap 'rm -f "$keystore"' EXIT
    export WATCHAUDIO_RELEASE_STORE_FILE="$keystore"
    set_output signed true
    signing="signed with the release key (secrets)"
  elif [ -n "${WATCHAUDIO_RELEASE_STORE_FILE:-}" ]; then
    signing="signed with WATCHAUDIO_RELEASE_STORE_FILE (local)"
  fi
  gw :apps:android:assembleRelease :apps:wear:assembleRelease
  local f
  {
    echo "### Release builds (R8): $signing"
    echo
    echo "| APK | Size |"
    echo "| --- | --- |"
    for f in apps/android/build/outputs/apk/release/*.apk apps/wear/build/outputs/apk/release/*.apk; do
      echo "| $(basename "$f") | $(du -h "$f" | cut -f1) |"
    done
    echo
    echo "Wear OS debug input guard (verifyReleaseDebugInput, #57):"
    echo '```'
    cat apps/wear/build/reports/debug-input/release.txt
    echo '```'
  } | tee -a "$CHECK_SUMMARY"
}

# check-no-tts [APK...]: "sherpa-onnx JNI without TTS (release and debug APKs)" (android.yml) and
# "... (delivered phone APK)" (deliver.yml, with the APK as argument). #173.
step_check_no_tts() {
  if [ $# -eq 0 ]; then
    set -- apps/android/build/outputs/apk/release/*.apk apps/android/build/outputs/apk/debug/*.apk
  fi
  if ! is_ci && [ -z "${WATCHAUDIO_SHERPA_ONNX_JNI_DIR:-}" ]; then
    skip "no JNI library without TTS in this run (step sherpa-onnx-jni skipped): the APKs pack the AAR's"
  fi
  tools/sherpa-onnx-android/check-no-tts.sh "$@"
  cat apps/android/build/reports/sherpa-onnx-jni/*.txt
}

# --- local extras ------------------------------------------------------------------------------

# The iOS Kotlin type-check on any host (CLAUDE.md "Commands"): main and test sources, no linking.
step_ios_cross_compile() {
  gw compileKotlinIosSimulatorArm64 compileTestKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
}

# --- models and harness ------------------------------------------------------------------------

# Models for the harness and the phone emulator (tools/harness/models). WATCHAUDIO_MODELS_DIR puts
# them elsewhere (e.g. an external drive): tools/harness/models becomes a link to it.
link_models_dir() {
  [ -n "${WATCHAUDIO_MODELS_DIR:-}" ] || return 0
  mkdir -p "$WATCHAUDIO_MODELS_DIR"
  if [ -L tools/harness/models ]; then
    return 0
  elif [ -e tools/harness/models ]; then
    note "tools/harness/models exists; WATCHAUDIO_MODELS_DIR is not used (move it there and remove it to switch)"
  else
    ln -s "$WATCHAUDIO_MODELS_DIR" tools/harness/models
  fi
}

# emulator.yml "Download ASR models" (and the first part of android.yml's end-to-end step).
step_models() {
  link_models_dir
  tools/harness/download-models.sh
}

# android.yml "End-to-end without devices (chunks -> ingest -> on-device ASR -> SQLite)".
# ffmpeg is installed by the workflow's setup step in CI; locally it must be on PATH.
step_harness() {
  have ffmpeg || fail "ffmpeg is not installed (Ubuntu/WSL: sudo apt-get install ffmpeg; macOS: brew install ffmpeg)"
  link_models_dir
  tools/harness/download-models.sh
  gw :tools:harness:test -PrequireModels=true
}

# --- emulator.yml release-test-apks job --------------------------------------------------------

# A throwaway release key for builds that only have to install on an emulator (#78). Never the
# real key: these APKs are not distributed.
throwaway_key() {
  export WATCHAUDIO_RELEASE_STORE_FILE="${RUNNER_TEMP:-$CHECK_OUT}/throwaway.jks"
  export WATCHAUDIO_RELEASE_STORE_PASSWORD=ci-throwaway
  export WATCHAUDIO_RELEASE_KEY_ALIAS=ci
  export WATCHAUDIO_RELEASE_KEY_PASSWORD=ci-throwaway
  rm -f "$WATCHAUDIO_RELEASE_STORE_FILE"
  keytool -genkeypair -noprompt -keystore "$WATCHAUDIO_RELEASE_STORE_FILE" -storepass ci-throwaway \
    -keypass ci-throwaway -alias ci -keyalg RSA -keysize 2048 -validity 1 -dname "CN=watch-audio CI throwaway"
}

# "Build the release APK and the test APK for the release ASR check": release-test-apks/.
step_release_test_apks() {
  throwaway_key
  gw :apps:android:assembleRelease :apps:android:assembleReleaseAndroidTest -Pwatchaudio.testBuildType=release
  rm -rf release-test-apks
  mkdir -p release-test-apks
  cp apps/android/build/outputs/apk/release/android-release.apk \
    apps/android/build/outputs/apk/androidTest/release/android-release-androidTest.apk release-test-apks/
  no_tts_check release-test-apks/android-release.apk
}
