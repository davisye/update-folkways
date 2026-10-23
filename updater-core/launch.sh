#!/usr/bin/env bash
# Stable seed protocol v1. Install this script manually; it never replaces itself.
set -euo pipefail
umask 077
game='' bootstrap_source='' companion='' java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java" dedicated=0
while (($#)); do
  case "$1" in
    --game-dir) game=$2; shift 2 ;;
    --bootstrap-jar) bootstrap_source=$2; shift 2 ;;
    --companion-jar) companion=$2; shift 2 ;;
    --java) java_bin=$2; shift 2 ;;
    --dedicated) dedicated=1; shift ;;
    --) shift; break ;;
    *) printf '%s\n' 'Unknown seed option. Use --game-dir DIR [--bootstrap-jar FILE --companion-jar FILE] [--dedicated] -- java ARGS...' >&2; exit 2 ;;
  esac
done
[[ -n "$game" && -d "$game" && $# -gt 0 ]] || { printf '%s\n' 'A game directory and foreground Java command are required.' >&2; exit 2; }
command=("$@")
case "${command[0]##*/}" in java|java.exe) ;; *) printf '%s\n' 'Seed v1 requires the foreground Java JVM command. Detached GUI launchers and shell wrappers are unsupported.' >&2; exit 2 ;; esac
game=$(cd -- "$game" && pwd -P)
home="$game/.folkways-updater"; bin="$home/bin/folkways-bootstrap.jar"; runtime="$home/runtime"
for own in "$home" "$home/bin" "$runtime" "$home/seed.lock" "$bin"; do [[ ! -L "$own" ]] || { printf '%s\n' 'Updater paths must not be symlinks.' >&2; exit 1; }; done
mkdir -p -- "$home/bin" "$runtime"
exec 9>"$home/seed.lock"
flock -n 9 || { printf '%s\n' 'Another stable seed is active.' >&2; exit 1; }
if [[ ! -f "$bin" ]]; then
  [[ -n "$bootstrap_source" && -f "$bootstrap_source" ]] || { printf '%s\n' 'First installation requires --bootstrap-jar pointing to the downloaded standalone JAR.' >&2; exit 2; }
  initial=$(mktemp "$home/bin/initial-XXXXXXXX.jar")
  cp -- "$bootstrap_source" "$initial"
  mv -- "$initial" "$bin"
fi
active_dir='' active_pid=''
cleanup() {
  if [[ -n "$active_dir" && "$active_dir" == "$runtime"/launch-* && ! -L "$active_dir" ]]; then
    if [[ -z "$active_pid" ]] || ! kill -0 "$active_pid" 2>/dev/null; then rm -r -- "$active_dir"; fi
  fi
}
trap cleanup EXIT
run_runner() {
  active_dir=$(mktemp -d "$runtime/launch-XXXXXXXX") || return $?
  cp -- "$bin" "$active_dir/runner.jar" || return $?
  "$java_bin" -jar "$active_dir/runner.jar" "$@" <&0 &
  active_pid=$!
  local result=0
  wait "$active_pid" || result=$?
  if ! kill -0 "$active_pid" 2>/dev/null; then active_pid=''; cleanup; active_dir=''; fi
  return "$result"
}
if [[ -n "$companion" && ! -f "$home/self-state.json" ]]; then run_runner self-adopt --game-dir "$game" --companion-jar "$companion"; fi
export FOLKWAYS_SEED_PROTOCOL=1
for ((bounce=0; bounce<=50; bounce++)); do
  export FOLKWAYS_SEED_RESTART_COUNT=$bounce
  run_runner self-prepare --game-dir "$game"
  args=(launch --game-dir "$game")
  ((dedicated == 0)) || args+=(--dedicated)
  args+=(-- "${command[@]}")
  result=0; run_runner "${args[@]}" || result=$?
  if ((result != 75)); then exit "$result"; fi
done
printf '%s\n' 'Stable seed restart budget exhausted.' >&2
exit 1
