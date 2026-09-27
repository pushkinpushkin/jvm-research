#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Short local comparison. This is an experiment helper, not a baseline runner.
SCENARIO="${SCENARIO:-low-load}"
DURATION="${DURATION:-10m}"
RATE="${RATE:-1}"
RATE_TIME_UNIT="${RATE_TIME_UNIT:-1s}"
POST_IDLE_SECONDS="${POST_IDLE_SECONDS:-0}"
ORDER_POOL="${ORDER_POOL:-1000}"
SEED_ORDERS="${SEED_ORDERS:-1000}"
TRAFFIC_PROFILE="${TRAFFIC_PROFILE:-normal}"
TRACE_SEED="${TRACE_SEED:-20260925}"
MATRIX_PASSES="${MATRIX_PASSES:-forward}"
SERIES_ID="${SERIES_ID:-$(date -u +%Y%m%dT%H%M%SZ)-local-10m}"
RESULTS_ROOT="${RESULTS_ROOT:-results/local-10m/${SERIES_ID}}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"
COLLECT_PROMETHEUS="${COLLECT_PROMETHEUS:-true}"
K6_TIME_SERIES="${K6_TIME_SERIES:-true}"
DRY_RUN="${DRY_RUN:-false}"

profiles_forward=(
  work-hotspot-elastic
  work-openj9-elastic
  work-graalvm-elastic
  work-graalvm-native
)
profiles_reverse=(
  work-graalvm-native
  work-graalvm-elastic
  work-openj9-elastic
  work-hotspot-elastic
)

log() {
  printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

usage() {
  cat <<'USAGE'
Run one 10-minute local experiment for every runtime profile.

Defaults:
  SCENARIO=low-load RATE=1 RATE_TIME_UNIT=1s DURATION=10m
  ORDER_POOL=1000 SEED_ORDERS=1000 TRAFFIC_PROFILE=normal
  POST_IDLE_SECONDS=0 MATRIX_PASSES=forward

Environment:
  SCENARIO              idle, low-load, or load
  DURATION              main workload duration, default 10m
  RATE / RATE_TIME_UNIT low-load/load rate, default 1/1s
  POST_IDLE_SECONDS     optional post-load idle window, default 0
  MATRIX_PASSES         forward, reverse, or both
  RESULTS_ROOT          result root; each run gets its own subdirectory
  DRY_RUN=true          print commands without running them

Examples:
  bash scripts/run-local-10m-matrix.sh
  SCENARIO=idle bash scripts/run-local-10m-matrix.sh
  SCENARIO=load RATE=10 MATRIX_PASSES=both bash scripts/run-local-10m-matrix.sh
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

case "$SCENARIO" in
  idle|low-load|load) ;;
  *) echo "Unknown SCENARIO: $SCENARIO" >&2; exit 1 ;;
esac
case "$TRAFFIC_PROFILE" in
  normal|faults) ;;
  *) echo "Unknown TRAFFIC_PROFILE: $TRAFFIC_PROFILE" >&2; exit 1 ;;
esac
for tool in docker curl python3; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
if [[ "$SCENARIO" != idle ]]; then
  command -v k6 >/dev/null || { echo 'k6 is required for low-load/load' >&2; exit 1; }
fi
docker compose version >/dev/null

run_one() {
  local pass_name="$1"
  local order_index="$2"
  local profile="$3"
  local run_id="${SERIES_ID}-${pass_name}-${order_index}-${profile}"
  local result_root="${RESULTS_ROOT}/${pass_name}"
  local profile_file="profiles/${profile}.env"

  [[ -f "$profile_file" ]] || { echo "Missing profile: $profile_file" >&2; exit 1; }
  log "start ${pass_name}/${order_index}: ${profile}; results=${result_root}/${run_id}"

  local -a env_args=(
    env -u JAVA_TOOL_OPTIONS
    SCENARIO="$SCENARIO"
    DURATION="$DURATION"
    RATE="$RATE"
    RATE_TIME_UNIT="$RATE_TIME_UNIT"
    POST_IDLE_SECONDS="$POST_IDLE_SECONDS"
    ORDER_POOL="$ORDER_POOL"
    SEED_ORDERS="$SEED_ORDERS"
    TRAFFIC_PROFILE="$TRAFFIC_PROFILE"
    TRACE_SEED="$TRACE_SEED"
    INTERVAL_SECONDS="$INTERVAL_SECONDS"
    COLLECT_PROMETHEUS="$COLLECT_PROMETHEUS"
    K6_TIME_SERIES="$K6_TIME_SERIES"
    RESULTS_ROOT="$result_root"
    RUN_ID="$run_id"
  )

  if [[ "$DRY_RUN" == true ]]; then
    printf '%q ' "${env_args[@]}" bash scripts/run-experiment.sh "$profile_file"
    printf '\n'
  else
    "${env_args[@]}" bash scripts/run-experiment.sh "$profile_file"
  fi
}

run_pass() {
  local pass_name="$1"
  shift
  local index=1
  for profile in "$@"; do
    run_one "$pass_name" "$index" "$profile"
    index=$((index + 1))
  done
}

log "series=${SERIES_ID} scenario=${SCENARIO} duration=${DURATION} results=${RESULTS_ROOT}"
for pass in $MATRIX_PASSES; do
  case "$pass" in
    forward) run_pass pass1-forward "${profiles_forward[@]}" ;;
    reverse) run_pass pass2-reverse "${profiles_reverse[@]}" ;;
    both)
      run_pass pass1-forward "${profiles_forward[@]}"
      run_pass pass2-reverse "${profiles_reverse[@]}"
      ;;
    *) echo "Unknown MATRIX_PASSES entry: $pass" >&2; exit 1 ;;
  esac
done
log "series complete: ${RESULTS_ROOT}"
