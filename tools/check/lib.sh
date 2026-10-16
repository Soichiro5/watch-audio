# shellcheck shell=bash
# Shared helpers of tools/check/check.sh (sourced, not run). Works with bash 3.2 (macOS), bash 5
# (Linux, WSL) and Git Bash (Windows): no associative arrays, no ${var,,}, no mapfile.
#
# A step is a shell function `step_<name with _>` in one of the steps-*.sh files. check.sh runs it
# in a subshell with `set -eo pipefail`, records passed / failed / skipped and its duration, and
# prints a summary table at the end. In CI (GITHUB_ACTIONS=true or --ci) a workflow step runs exactly
# one step (`check.sh step <name>`) and the CI-only parts (GITHUB_OUTPUT, GITHUB_ENV, RUNNER_TEMP,
# `simctl shutdown all`) are switched on; locally they never are.

CHECK_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CHECK_CI=${CHECK_CI:-0}
[ "${GITHUB_ACTIONS:-}" = "true" ] && CHECK_CI=1
CHECK_OUT=${CHECK_OUT:-$CHECK_ROOT/build/check}
CHECK_GRADLE_ARGS=${CHECK_GRADLE_ARGS:-}
# Where the checks keep what survives `rm -rf build` (the sherpa-onnx JNI library). Overridable,
# e.g. on an external drive (docs/dev/04 「手元での確認」).
WATCHAUDIO_CACHE_DIR=${WATCHAUDIO_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/watch-audio}

# --- environment -------------------------------------------------------------------------------

detect_os() {
  case "$(uname -s)" in
    Linux)
      if grep -qi microsoft /proc/version 2>/dev/null; then CHECK_OS=wsl; else CHECK_OS=linux; fi ;;
    Darwin) CHECK_OS=macos ;;
    MINGW* | MSYS* | CYGWIN*) CHECK_OS=windows ;;
    *) CHECK_OS=unknown ;;
  esac
  CHECK_ARCH=$(uname -m)
}
detect_os

is_ci() { [ "$CHECK_CI" = "1" ]; }
have() { command -v "$1" > /dev/null 2>&1; }

# Absolute path of a file that may not exist yet (no readlink -f on macOS).
abspath() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s/%s\n' "$(pwd)" "$1" ;;
  esac
}

sha256_of() {
  if have sha256sum; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Free space in KiB of the file system holding $1 (or its nearest existing parent).
free_kib() {
  local d=$1
  while [ ! -e "$d" ] && [ "$d" != "/" ] && [ -n "$d" ]; do d=$(dirname "$d"); done
  df -Pk "$d" 2>/dev/null | awk 'NR == 2 { print $4 }'
}

# Mount point of the file system holding $1 (to group sizes per disk).
mount_of() {
  local d=$1
  while [ ! -e "$d" ] && [ "$d" != "/" ] && [ -n "$d" ]; do d=$(dirname "$d"); done
  df -Pk "$d" 2>/dev/null | awk 'NR == 2 { print $6 }'
}

# Size in KiB of a path (0 if missing).
size_kib() {
  if [ -e "$1" ]; then du -sk "$1" 2>/dev/null | awk '{ s += $1 } END { print s + 0 }'; else echo 0; fi
}

human_kib() {
  awk -v k="$1" 'BEGIN {
    if (k >= 1048576) printf "%.1f GB", k / 1048576;
    else if (k >= 1024) printf "%.0f MB", k / 1024;
    else printf "%d KB", k }'
}

# Gradle with the local extra arguments (e.g. --max-workers=2 on a shared machine).
gw() {
  # shellcheck disable=SC2086
  "$CHECK_ROOT/gradlew" $CHECK_GRADLE_ARGS "$@"
}

# --- step plumbing -----------------------------------------------------------------------------

# The step summary (Markdown evidence): the job summary in CI, a file under build/check locally.
# The .github/scripts helpers append to $GITHUB_STEP_SUMMARY when it is set, so locally it points at
# the same file (only in this process tree; nothing is written to GitHub).
init_out() {
  mkdir -p "$CHECK_OUT/logs"
  if is_ci; then
    CHECK_SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}
    CHECK_ENV_FILE=${GITHUB_ENV:-$CHECK_OUT/env}
  else
    CHECK_SUMMARY=${CHECK_SUMMARY:-$CHECK_OUT/summary.md}
    CHECK_ENV_FILE=$CHECK_OUT/env
    export GITHUB_STEP_SUMMARY=$CHECK_SUMMARY
  fi
  export CHECK_SUMMARY CHECK_ENV_FILE CHECK_OUT CHECK_CI CHECK_GRADLE_ARGS WATCHAUDIO_CACHE_DIR
}

# summary <line>: appends a line to the step summary.
summary() { printf '%s\n' "$*" >> "$CHECK_SUMMARY"; }

# export_env <name> <value>: for the later steps (GITHUB_ENV in CI, re-read by check.sh locally).
export_env() {
  export "$1=$2"
  printf '%s=%s\n' "$1" "$2" >> "$CHECK_ENV_FILE"
}

# set_output <name> <value>: a step output in CI (steps.<id>.outputs.<name>); nothing locally.
set_output() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"; fi
}

load_env_file() {
  [ -f "$CHECK_ENV_FILE" ] || return 0
  local k v
  while IFS='=' read -r k v; do
    case "$k" in '' | \#*) continue ;; esac
    export "$k=$v"
  done < "$CHECK_ENV_FILE"
}

# skip <reason>: ends the running step as skipped. In CI a skip is a failure: CI must run everything.
skip() {
  printf '%s\n' "$*" > "$CHECK_OUT/skip-reason"
  if is_ci; then
    echo "::error::step skipped in CI: $*"
    exit 1
  fi
  echo "SKIPPED: $*"
  exit 200
}

# note <text>: a remark shown next to the step in the summary table (e.g. a sub-check left out).
note() {
  printf '%s\n' "$*" >> "$CHECK_OUT/step-notes"
  echo "NOTE: $*"
}

fail() {
  echo "FAILED: $*" >&2
  exit 1
}

fn_of_step() { printf 'step_%s' "$(printf '%s' "$1" | tr '-' '_')"; }

is_step() { declare -F "$(fn_of_step "$1")" > /dev/null; }

# Every step name, from the function names of the sourced steps-*.sh files.
all_steps() { declare -F | awk '{ print $3 }' | sed -n 's/^step_//p' | tr '_' '-'; }

# --- results -----------------------------------------------------------------------------------

R_NAMES=()
R_STATUS=()
R_SECS=()
R_NOTES=()

record() {
  R_NAMES+=("$1")
  R_STATUS+=("$2")
  R_SECS+=("$3")
  R_NOTES+=("$4")
}

# status_of <step>: the recorded status of the last run of a step (empty if it did not run).
status_of() {
  local i n=${#R_NAMES[@]} s=""
  i=0
  while [ "$i" -lt "$n" ]; do
    [ "${R_NAMES[$i]}" = "$1" ] && s=${R_STATUS[$i]}
    i=$((i + 1))
  done
  printf '%s' "$s"
}

passed() { [ "$(status_of "$1")" = "passed" ]; }

# Step selection (--only / --skip, comma-separated names or shell patterns such as ios-*).
CHECK_ONLY=${CHECK_ONLY:-}
CHECK_SKIP=${CHECK_SKIP:-}
matches_list() {
  local name=$1 list=$2 pat
  local IFS=,
  for pat in $list; do
    # shellcheck disable=SC2254
    case "$name" in $pat) return 0 ;; esac
  done
  return 1
}

CHECK_FAIL_FAST=${CHECK_FAIL_FAST:-0}
CHECK_DRY_RUN=${CHECK_DRY_RUN:-0}
CHECK_ABORTED=0

# run_step <name> [args...]: runs one step and records its result. Returns 0 unless it failed.
run_step() {
  local name=$1
  shift
  local label=$name
  [ $# -gt 0 ] && label="$name $*"
  if [ "$CHECK_ABORTED" = "1" ]; then
    record "$label" skipped 0 "not run: an earlier step failed (--fail-fast)"
    return 0
  fi
  if ! is_step "$name"; then
    echo "unknown step: $name" >&2
    record "$label" failed 0 "unknown step"
    return 1
  fi
  if [ -n "$CHECK_ONLY" ] && ! matches_list "$name" "$CHECK_ONLY"; then
    record "$label" skipped 0 "not selected (--only)"
    return 0
  fi
  if [ -n "$CHECK_SKIP" ] && matches_list "$name" "$CHECK_SKIP"; then
    record "$label" skipped 0 "left out (--skip / --quick)"
    return 0
  fi
  if [ "$CHECK_DRY_RUN" = "1" ]; then
    record "$label" planned 0 ""
    return 0
  fi
  rm -f "$CHECK_OUT/skip-reason" "$CHECK_OUT/step-notes"
  echo
  echo "=== [$(date '+%H:%M:%S')] $label"
  local start=$SECONDS rc fn log
  fn=$(fn_of_step "$name")
  log="$CHECK_OUT/logs/$(printf '%s' "$label" | tr ' /' '__').log"
  if is_ci; then
    (
      set -eo pipefail
      cd "$CHECK_ROOT"
      "$fn" "$@"
    )
    rc=$?
  else
    (
      set -eo pipefail
      cd "$CHECK_ROOT"
      "$fn" "$@"
    ) 2>&1 | tee "$log"
    rc=${PIPESTATUS[0]}
  fi
  local secs=$((SECONDS - start)) note_text=""
  [ -f "$CHECK_OUT/step-notes" ] && note_text=$(tr '\n' ' ' < "$CHECK_OUT/step-notes")
  if [ "$rc" -eq 200 ]; then
    record "$label" skipped "$secs" "$(cat "$CHECK_OUT/skip-reason" 2>/dev/null)"
    return 0
  fi
  is_ci || load_env_file
  if [ "$rc" -eq 0 ]; then
    record "$label" passed "$secs" "$note_text"
    return 0
  fi
  is_ci || note_text="$note_text log: ${log#"$CHECK_ROOT"/}"
  record "$label" failed "$secs" "exit $rc. $note_text"
  [ "$CHECK_FAIL_FAST" = "1" ] && CHECK_ABORTED=1
  return 1
}

# run_step_soft: like run_step, but a failure is only a warning (continue-on-error in the workflow).
run_step_soft() {
  local before=${#R_NAMES[@]}
  run_step "$@" || true
  local i=$before
  if [ "${R_STATUS[$i]:-}" = "failed" ]; then
    R_STATUS[$i]=warning
    [ "$CHECK_FAIL_FAST" = "1" ] && CHECK_ABORTED=0
  fi
  return 0
}

# run_after <dependency> <step> [args...]: runs the step only if the dependency passed
# (the `if: steps.<id>.outcome == 'success'` of the workflows).
run_after() {
  local dep=$1
  shift
  local s
  s=$(status_of "$dep")
  if [ "$s" = "passed" ] || [ "$CHECK_DRY_RUN" = "1" ]; then
    run_step "$@" || true
  else
    local label=$1
    shift
    [ $# -gt 0 ] && label="$label $*"
    record "$label" skipped 0 "needs $dep (${s:-not run})"
  fi
}

# skip_step <step label> <reason>: records a step that the stage decided not to run.
skip_step() { record "$1" skipped 0 "$2"; }

print_results() {
  local i n=${#R_NAMES[@]} ran=0 ok=0 bad=0 skipped=0 warn=0 total=0
  printf '\n%-34s %-8s %7s  %s\n' "STEP" "RESULT" "TIME" "NOTE"
  printf '%-34s %-8s %7s  %s\n' "----" "------" "----" "----"
  i=0
  while [ "$i" -lt "$n" ]; do
    local s=${R_STATUS[$i]} t=${R_SECS[$i]}
    total=$((total + t))
    case "$s" in
      passed) ran=$((ran + 1)); ok=$((ok + 1)) ;;
      failed) ran=$((ran + 1)); bad=$((bad + 1)) ;;
      warning) ran=$((ran + 1)); warn=$((warn + 1)) ;;
      skipped) skipped=$((skipped + 1)) ;;
    esac
    printf '%-34s %-8s %6ss  %s\n' "${R_NAMES[$i]}" "$s" "$t" "${R_NOTES[$i]}"
    i=$((i + 1))
  done
  printf '\nran %d: passed %d, failed %d, warnings %d; skipped %d; %d min %d s\n' \
    "$ran" "$ok" "$bad" "$warn" "$skipped" $((total / 60)) $((total % 60))
  # The same table as Markdown, for a PR's 「確認」 section.
  {
    echo "| Step | Result | Time | Note |"
    echo "| --- | --- | --- | --- |"
    i=0
    while [ "$i" -lt "$n" ]; do
      printf '| %s | %s | %ss | %s |\n' "${R_NAMES[$i]}" "${R_STATUS[$i]}" "${R_SECS[$i]}" "${R_NOTES[$i]}"
      i=$((i + 1))
    done
  } > "$CHECK_OUT/results.md"
  [ "$CHECK_DRY_RUN" = "1" ] || echo "Markdown table: ${CHECK_OUT#"$CHECK_ROOT"/}/results.md, evidence: ${CHECK_SUMMARY#"$CHECK_ROOT"/}, logs: ${CHECK_OUT#"$CHECK_ROOT"/}/logs/"
  [ "$bad" -eq 0 ]
}
