#!/usr/bin/env bash
# Folder restructure (#49): moves every directory with `git mv` (history is kept) and then rewrites
# the paths that refer to them (tools/restructure/rewrite.py). See tools/restructure/plan.md.
#
#   tools/restructure/migrate.sh --dry-run          # print the moves and the files that would change
#   tools/restructure/migrate.sh --dry-run --diff   # ... with the full diff of the text rewrites
#   tools/restructure/migrate.sh                    # do it (on a clean branch from the current main)
#
# Idempotent: a move whose source is gone is skipped, and the rewrites only match the old paths, so
# running it again changes nothing. Run it from a clean working tree; review `git diff -M` afterwards.
set -euo pipefail

cd "$(dirname "$0")/../.."

DRY_RUN=0
REWRITE_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1; REWRITE_ARGS+=(--dry-run) ;;
    --diff) REWRITE_ARGS+=(--diff) ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [ "$DRY_RUN" -eq 0 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "The working tree has uncommitted changes; commit or discard them first." >&2
  exit 1
fi

run() {
  if [ "$DRY_RUN" -eq 1 ]; then echo "  $*"; else "$@"; fi
}

# move <old> <new>: git mv of a tracked file or directory. A directory is renamed as a whole, so
# ignored files inside it (build/, Configuration/Local.xcconfig, a generated .xcodeproj) move along.
move() {
  local old=$1 new=$2
  if [ ! -e "$old" ]; then
    if [ -e "$new" ]; then echo "  done already: $old -> $new"; else echo "  skip (not found): $old"; fi
    return
  fi
  if [ -e "$new" ]; then
    echo "both $old and $new exist; resolve by hand" >&2
    exit 1
  fi
  [ -d "$(dirname "$new")" ] || run mkdir -p "$(dirname "$new")"
  run git mv "$old" "$new"
}

# remove <dir>: an empty placeholder module (tracked files, then leftover build output).
remove() {
  local dir=$1
  if [ ! -e "$dir" ]; then echo "  done already: removed $dir"; return; fi
  if [ -n "$(git ls-files -- "$dir")" ]; then run git rm -r -q -- "$dir"; fi
  run rm -rf -- "$dir"
}

echo "== Moves"
# iOS folders are renamed inside iosApp/ first, so every source path exists in the current layout.
move iosApp/iosApp iosApp/App
move iosApp/WatchApp iosApp/Watch
move iosApp/Shared iosApp/SharedSwift
for t in ProtocolTests WatchLogicTests SpeechTests PlaybackTests TestSupport; do
  move "iosApp/$t" "iosApp/Tests/$t"
done
move iosApp apps/ios
move androidApp apps/android
move wearApp apps/wear

move composeApp shared/ui
for m in protocol db pipeline platform model; do
  move "shared/core-$m" "shared/$m"
done
remove shared/core-search
remove shared/core-ai

move spikes research

move docs/01-research.md docs/product/01-research.md
move docs/03-roadmap.md docs/product/03-roadmap.md
move docs/02-architecture.md docs/architecture/02-architecture.md
move docs/04-dev-environment.md docs/dev/04-dev-environment.md
move docs/test-audio.md docs/dev/test-audio.md
move docs/impl docs/platforms
move docs/spikes docs/research

echo "== Path rewrites"
python3 tools/restructure/rewrite.py "${REWRITE_ARGS[@]}"

if [ "$DRY_RUN" -eq 0 ]; then
  for p in apps/android/build.gradle.kts apps/wear/build.gradle.kts apps/ios/project.yml apps/ios/App apps/ios/Watch \
           apps/ios/SharedSwift apps/ios/Tests/ProtocolTests shared/ui/build.gradle.kts shared/protocol/testdata \
           research docs/product/03-roadmap.md docs/platforms docs/research; do
    [ -e "$p" ] || { echo "expected $p after the move" >&2; exit 1; }
  done
  cat <<'EOF'
== Done. Next (tools/restructure/plan.md, "検証"):
  python3 tools/check-links/check_links.py
  python3 -m unittest discover -s tools/privacy-check && python3 tools/privacy-check/check.py
  ./gradlew --max-workers=2 jvmTest testAndroidHostTest :apps:android:testDebugUnitTest :apps:wear:testDebugUnitTest
  ./gradlew --max-workers=2 :apps:android:assembleDebug :apps:wear:assembleDebug
  ./gradlew --max-workers=2 compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
  Mac: rm -rf apps/ios/WatchAudio.xcodeproj && (cd apps/ios && xcodegen generate)
EOF
fi
