# shellcheck shell=bash
# Steps of ios.yml (macOS only): Kotlin tests on the iOS simulator, the Xcode project, Debug builds
# for the simulators, the simulator checks (.github/scripts/*), and the release job's unsigned
# Release builds for devices. Sourced by check.sh.
#
# DERIVED_DATA (default build/DerivedData) is where every build and test step puts its products, so
# the launch and hosted test steps reuse the builds; point it at an external drive to save space.
# KONAN_DATA_DIR moves Kotlin/Native's toolchain (~/.konan). See docs/dev/04 「手元での確認」.

derived_data() { printf '%s' "${DERIVED_DATA:-build/DerivedData}"; }

require_macos() {
  [ "$CHECK_OS" = "macos" ] || skip "needs macOS with Xcode"
  have xcodebuild || skip "xcodebuild not found (install Xcode 26 and run: sudo xcode-select -s /Applications/Xcode.app)"
}

# xcode_build <log> <xcodebuild args...>: a quiet build; on failure, the log's errors and, when Xcode
# lacks a platform, how to install it.
xcode_build() {
  local log=$1 status=0
  shift
  xcodebuild -project apps/ios/WatchAudio.xcodeproj -derivedDataPath "$(derived_data)" \
    CODE_SIGNING_ALLOWED=NO "$@" build -quiet 2>&1 | tee "$log" || status=$?
  if [ $status -ne 0 ] && grep -qE "is not installed|Unable to find a destination" "$log"; then
    echo "Xcode lacks a platform this build needs: Xcode > Settings > Components, or" \
      "xcodebuild -downloadPlatform iOS / watchOS (docs/dev/04 「手元での確認」)"
  fi
  return $status
}

# ios-kotlin-tests: "Shared tests (iOS simulator)".
step_ios_kotlin_tests() {
  require_macos
  gw iosSimulatorArm64Test
}

# ios-kotlin-evidence: "Shared test evidence (iOS simulator)".
step_ios_kotlin_evidence() {
  python3 .github/scripts/junit-summary.py "Shared Kotlin tests on the iOS simulator" \
    'shared/*/build/test-results/iosSimulatorArm64Test/*.xml' \
    'shared/ui/build/test-results/iosSimulatorArm64Test/*.xml' >> "$CHECK_SUMMARY"
  {
    echo "Playback checks on the iOS simulator (IosPlaybackTest, #19 / #25; the player is prepared, started= is informational):"
    echo '```'
    grep -ho "PLAYBACK_CHECK[^<&]*" shared/ui/build/test-results/iosSimulatorArm64Test/*.xml || echo "no PLAYBACK_CHECK lines"
    echo '```'
  } >> "$CHECK_SUMMARY"
}

# ios-xcodegen: "Generate Xcode project" (XcodeGen is installed by the workflow's setup step in CI).
step_ios_xcodegen() {
  require_macos
  xcodebuild -version
  have xcodegen || fail "xcodegen not found (brew install xcodegen)"
  (cd apps/ios && xcodegen generate)
}

# ios-build-app: "Build iOS app (embeds the watchOS app)", Debug, simulator.
step_ios_build_app() {
  require_macos
  xcode_build xcodebuild-ios.log -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator'
}

# ios-build-watch: "Build watchOS app", Debug, simulator.
step_ios_build_watch() {
  require_macos
  xcode_build xcodebuild-watch.log -scheme WatchApp -configuration Debug -destination 'generic/platform=watchOS Simulator'
}

# ios-watch-release-check: "watchOS Release build has no debug audio input" (#57). The Debug build
# above is the positive control for the search.
step_ios_watch_release_check() {
  require_macos
  local dd
  dd=$(derived_data)
  xcode_build xcodebuild-watch-release.log -scheme WatchApp -configuration Release -destination 'generic/platform=watchOS Simulator'
  local debug_app release_app
  debug_app=$(find "$dd/Build/Products/Debug-watchsimulator" -maxdepth 1 -name '*.app' | head -1)
  release_app=$(find "$dd/Build/Products/Release-watchsimulator" -maxdepth 1 -name '*.app' | head -1)
  echo "Debug: $debug_app / Release: $release_app"
  grep -rqa DebugAudioFileInput "$debug_app" || fail "DebugAudioFileInput not found in the Debug app (search is broken)"
  if grep -rla DebugAudioFileInput "$release_app"; then fail "DebugAudioFileInput leaked into the Release app"; fi
  echo "OK: DebugAudioFileInput is in the Debug app and absent from the Release app" | tee -a "$CHECK_SUMMARY"
}

# ios-complication: "watchOS complication tap on an Apple Watch simulator" (#41).
step_ios_complication() {
  require_macos
  .github/scripts/watch-complication-smoke.sh \
    "$(derived_data)/Build/Products/Debug-watchsimulator/WatchAudio.app" app.watchaudio.watchkitapp
}

# ios-watch-launch: "Launch watchOS app on an Apple Watch simulator" (#7). Then the watch is shut
# down to free the machine for the iPhone (CI: every simulator; locally only the one used here).
step_ios_watch_launch() {
  require_macos
  local status=0
  .github/scripts/simulator-smoke.sh watchOS "Apple Watch" \
    "$(derived_data)/Build/Products/Debug-watchsimulator/WatchAudio.app" app.watchaudio.watchkitapp || status=$?
  if is_ci; then
    xcrun simctl shutdown all || true
  else
    xcrun simctl shutdown "$(.github/scripts/simulator-udid.sh watchOS "Apple Watch")" 2> /dev/null || true
  fi
  return $status
}

# ios-swift-unit: "Swift unit tests (iOS Simulator)" (hostless).
step_ios_swift_unit() {
  require_macos
  DERIVED_DATA=$(derived_data) .github/scripts/xcode-test.sh SwiftUnitTests "Swift unit tests on the iOS simulator"
}

# ios-llm: "On-device language model on the iOS simulator" (#196).
step_ios_llm() {
  require_macos
  local status=0
  DERIVED_DATA=$(derived_data) .github/scripts/xcode-test.sh LanguageModelTests "On-device language model (Foundation Models, #196) on the iOS simulator" || status=$?
  {
    echo "On-device language model checks (build/swift-evidence/llm-*.txt):"
    echo '```'
    cat build/swift-evidence/llm-*.txt 2>/dev/null || echo "no evidence written"
    echo '```'
  } >> "$CHECK_SUMMARY"
  return $status
}

# ios-launch: "Launch iOS app on an iPhone simulator" (#7).
step_ios_launch() {
  require_macos
  .github/scripts/simulator-smoke.sh iOS iPhone \
    "$(derived_data)/Build/Products/Debug-iphonesimulator/WatchAudio.app" app.watchaudio
}

# ios-ja-speech: "Japanese test speech" (#21; continue-on-error: without audio the tests skip).
step_ios_ja_speech() {
  require_macos
  .github/scripts/make-ja-speech.sh build/ja-speech
}

# ios-mixed-speech: "Japanese/English test speech" (#170; continue-on-error).
step_ios_mixed_speech() {
  require_macos
  .github/scripts/make-mixed-speech.sh build/mixed-speech
}

# hosted_test <scheme> <title> <evidence glob> <evidence title>: an app-hosted XCTest scheme on a
# fresh install of the app, reusing the app build (OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES skips the
# Gradle run of the app's "Compile Kotlin Framework" phase), then its evidence in the summary.
hosted_test() {
  local scheme=$1 title=$2 evidence=$3 heading=$4 status=0
  UNINSTALL_APP=app.watchaudio DERIVED_DATA=$(derived_data) .github/scripts/xcode-test.sh "$scheme" "$title" \
    OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES || status=$?
  {
    echo "$heading"
    echo '```'
    # shellcheck disable=SC2086
    cat $evidence 2>/dev/null || echo "no evidence written"
    echo '```'
  } >> "$CHECK_SUMMARY"
  return $status
}

# ios-speech-tests: "Speech transcription on the iOS simulator" (#21, #170).
step_ios_speech_tests() {
  require_macos
  hosted_test SpeechTests "Speech transcription (ja_JP; ja + en routing, #170) on the iOS simulator" \
    'build/swift-evidence/speech-*.txt' "Speech check logs (build/swift-evidence):"
}

# ios-playback: "Playback in the iOS app (simulator)" (#19).
step_ios_playback() {
  require_macos
  hosted_test PlaybackTests "Playback in the iOS app on the simulator (#19)" \
    'build/swift-evidence/hosted-playback.txt' "Playback check in the app process (PlaybackTests):"
}

# ios-embedding-model: "Embedding model" (#178). EMBEDDING_MODEL_DIR (default build/embedding-model).
step_ios_embedding_model() {
  .github/scripts/ios-embedding-model.sh "${EMBEDDING_MODEL_DIR:-build/embedding-model}"
}

# ios-semantic-search: "Semantic search on the iOS simulator" (#178).
step_ios_semantic_search() {
  require_macos
  hosted_test EmbeddingTests "Semantic search on the iOS simulator (#178)" \
    'build/swift-evidence/embedding-*.txt' "Semantic search checks in the app process (EmbeddingTests):"
}

# ios-agent-access: "Agent access on the iOS simulator (bridge CLI on the host)" (#185).
step_ios_agent_access() {
  require_macos
  DERIVED_DATA=$(derived_data) .github/scripts/ios-agent-access.sh OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES
}

# ios-shutdown: "Shut down simulators" (CI: all of them; locally the stage shuts down and deletes
# only the devices it created, see check.sh).
step_ios_shutdown() {
  require_macos
  if is_ci; then
    xcrun simctl shutdown all
  else
    local p
    for p in iOS watchOS; do
      xcrun simctl shutdown "$(.github/scripts/simulator-udid.sh "$p" "$([ $p = iOS ] && echo iPhone || echo 'Apple Watch')" 2>/dev/null)" 2>/dev/null || true
    done
  fi
}

# --- release job -------------------------------------------------------------------------------

# ios-release-build-app: "Build iOS app, Release, device (embeds the watchOS app)".
step_ios_release_build_app() {
  require_macos
  xcode_build xcodebuild-ios-release.log -scheme iosApp -configuration Release -destination 'generic/platform=iOS'
}

# ios-release-build-watch: "Build watchOS app, Release, device".
step_ios_release_build_watch() {
  require_macos
  xcode_build xcodebuild-watch-release.log -scheme WatchApp -configuration Release -destination 'generic/platform=watchOS'
}

# ios-release-evidence: "Release build evidence": one RELEASE_BUILD_CHECK line per app (architectures,
# size), no Debug-only audio input (#57), a privacy manifest in every bundle and no microphone
# usage in the iPhone app (#159).
step_ios_release_evidence() {
  require_macos
  local dd ios_app watch_app app id debug_input bundle manifest
  dd=$(derived_data)
  ios_app=$dd/Build/Products/Release-iphoneos/WatchAudio.app
  watch_app=$dd/Build/Products/Release-watchos/WatchAudio.app
  for app in "$ios_app" "$ios_app/Watch/WatchAudio.app" "$watch_app"; do
    if [ ! -x "$app/WatchAudio" ]; then echo "RELEASE_BUILD_CHECK $app FAILED: no executable"; continue; fi
    id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")
    debug_input=$(grep -rlaq DebugAudioFileInput "$app" && echo present || echo absent)
    echo "RELEASE_BUILD_CHECK ${app#"$dd"/Build/Products/} id=$id archs=$(lipo -archs "$app/WatchAudio") size_kb=$(du -sk "$app" | cut -f1) debug_audio_input=$debug_input"
  done | tee release-checks.txt
  for bundle in "$ios_app" "$ios_app/Watch/WatchAudio.app" "$ios_app/Watch/WatchAudio.app/PlugIns/WatchAudioWidget.appex" "$watch_app"; do
    manifest="$bundle/PrivacyInfo.xcprivacy"
    if plutil -lint -s "$manifest"; then
      echo "PRIVACY_MANIFEST_CHECK ${bundle#"$dd"/Build/Products/} reasons=$(plutil -extract NSPrivacyAccessedAPITypes json -o - "$manifest" | tr -d ' \n')"
    else
      echo "PRIVACY_MANIFEST_CHECK ${bundle#"$dd"/Build/Products/} FAILED: no valid PrivacyInfo.xcprivacy"
    fi
  done | tee -a release-checks.txt
  if /usr/libexec/PlistBuddy -c 'Print :NSMicrophoneUsageDescription' "$ios_app/Info.plist" > /dev/null 2>&1; then
    echo "IPHONE_MICROPHONE_CHECK FAILED: the iPhone app declares NSMicrophoneUsageDescription"
  else
    echo "IPHONE_MICROPHONE_CHECK ok: no NSMicrophoneUsageDescription in the iPhone app"
  fi | tee -a release-checks.txt
  { echo "Release builds (device, unsigned):"; echo '```'; cat release-checks.txt; echo '```'; } >> "$CHECK_SUMMARY"
  ! grep -q "FAILED\|debug_audio_input=present" release-checks.txt
}
