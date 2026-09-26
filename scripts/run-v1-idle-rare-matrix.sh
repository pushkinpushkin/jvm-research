#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

CONFIG_FILE="${MATRIX_CONFIG:-}"
if [[ "${1:-}" == "--config" ]]; then
  CONFIG_FILE="${2:-}"
  [[ -n "$CONFIG_FILE" ]] || { echo "Missing value for --config" >&2; exit 1; }
  shift 2
elif [[ "${1:-}" == --config=* ]]; then
  CONFIG_FILE="${1#--config=}"
  shift
fi

if [[ -n "$CONFIG_FILE" ]]; then
  [[ -f "$CONFIG_FILE" ]] || { echo "Missing config: $CONFIG_FILE" >&2; exit 1; }
  # shellcheck source=/dev/null
  allexport_was_on=false
  case "$-" in *a*) allexport_was_on=true ;; esac
  set -a
  source "$CONFIG_FILE"
  if [[ "$allexport_was_on" != true ]]; then set +a; fi
fi

MATRIX_LABEL="${MATRIX_LABEL:-v1-idle-rare-1h}"
SERIES_ID="${SERIES_ID:-$(date -u +%Y%m%dT%H%M%SZ)-${MATRIX_LABEL}}"
RESULTS_BASE="${RESULTS_BASE:-results/${MATRIX_LABEL}/${SERIES_ID}}"
DRY_RUN="${DRY_RUN:-false}"
MATRIX_PASSES="${MATRIX_PASSES:-forward reverse}"
FRESH_IDLE_DURATION="${FRESH_IDLE_DURATION:-1h}"
RARE_REQUESTS_DURATION="${RARE_REQUESTS_DURATION:-1h}"
RARE_POST_IDLE_SECONDS="${RARE_POST_IDLE_SECONDS:-3600}"
RARE_RATE="${RARE_RATE:-1}"
RARE_RATE_TIME_UNIT="${RARE_RATE_TIME_UNIT:-10s}"
MATRIX_ORDER_POOL="${MATRIX_ORDER_POOL:-1000}"
MATRIX_SEED_ORDERS="${MATRIX_SEED_ORDERS:-1000}"
REPORT_HTML="${REPORT_HTML:-}"

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

preflight() {
  local tool
  for tool in docker curl python3 k6; do
    command -v "$tool" >/dev/null || {
      echo "Missing required tool: $tool" >&2
      exit 1
    }
  done
  docker compose version >/dev/null
}

usage() {
  cat <<'USAGE'
Run v1 idle/rare-request matrix:
  pass 1: HotSpot -> OpenJ9 -> GraalVM JIT -> GraalVM Native
  pass 2: GraalVM Native -> GraalVM JIT -> OpenJ9 -> HotSpot

Default profile runs:
  - fresh idle: 1h
  - rare requests: 1h at 1 request / 10s, then 1h post-load idle

Environment:
  MATRIX_CONFIG              env file to source before planning the matrix
  SERIES_ID                  stable id for deterministic RUN_ID values
  RESULTS_BASE               root directory for this series
  MATRIX_LABEL               default results/<label>/<series>, default v1-idle-rare-1h
  MATRIX_PASSES              space-separated: forward, reverse, or both
  FRESH_IDLE_DURATION        fresh idle duration, default 1h
  RARE_REQUESTS_DURATION     rare-request load duration, default 1h
  RARE_POST_IDLE_SECONDS     post-load idle seconds after rare requests, default 3600
  RARE_RATE                  rare-request rate, default 1
  RARE_RATE_TIME_UNIT        rare-request rate unit, default 10s
  MATRIX_ORDER_POOL          order pool for rare requests, default 1000
  MATRIX_SEED_ORDERS         seed orders for rare requests, default 1000
  REPORT_HTML                optional self-contained HTML report path
  DRY_RUN=true               print planned commands without running them

Examples:
  bash scripts/run-v1-idle-rare-matrix.sh --config configs/local-idle-rare-quick.env
  DRY_RUN=true bash scripts/run-v1-idle-rare-matrix.sh --config configs/local-idle-rare-quick.env
USAGE
}

eligible_run_exists() {
  local run_dir="$1"
  python3 - "$run_dir" <<'PY'
import json, sys
from pathlib import Path
run = Path(sys.argv[1])
validation = run / 'validation.json'
metadata = run / 'metadata.json'
if not run.exists():
    sys.exit(1)
try:
    if validation.exists() and json.loads(validation.read_text()).get('eligible') is True:
        sys.exit(0)
    if metadata.exists() and json.loads(metadata.read_text()).get('comparisonEligible') is True:
        sys.exit(0)
except Exception:
    pass
sys.exit(2)
PY
}

append_env_if_set() {
  local name="$1"
  if [[ -n "${!name+x}" ]]; then
    env_args+=("$name=${!name}")
  fi
}

run_one() {
  local pass_name="$1"
  local order_index="$2"
  local profile="$3"
  local scenario_label="$4"
  local run_id="${SERIES_ID}-${pass_name}-${order_index}-${scenario_label}-${profile}"
  local results_root="${RESULTS_BASE}/${pass_name}/${scenario_label}"
  local run_dir="${results_root}/${run_id}"
  local profile_file="profiles/${profile}.env"

  if [[ ! -f "$profile_file" ]]; then
    echo "Missing profile: $profile_file" >&2
    exit 1
  fi

  if eligible_run_exists "$run_dir"; then
    log "skip eligible run: $run_dir"
    return 0
  else
    local status=$?
    if [[ "$status" == 2 ]]; then
      echo "Existing run is not eligible; refusing to overwrite: $run_dir" >&2
      exit 1
    fi
  fi

  log "start ${pass_name}/${order_index}: ${profile} ${scenario_label}"

  local env_args=(
    env
    -u JAVA_TOOL_OPTIONS
    TRAFFIC_PROFILE=normal
    ORDER_POOL="$MATRIX_ORDER_POOL"
    SEED_ORDERS="$MATRIX_SEED_ORDERS"
    RESULTS_ROOT="$results_root"
    RUN_ID="$run_id"
  )

  local inherited_name
  for inherited_name in INTERVAL_SECONDS COLLECT_PROMETHEUS K6_TIME_SERIES DRAIN_TIMEOUT_SECONDS \
    HEALTH_TIMEOUT_SECONDS SYNTHETIC_WARMUP TRACE_SEED PREALLOCATED_VUS MAX_VUS APP_PORT; do
    append_env_if_set "$inherited_name"
  done

  if [[ "$scenario_label" == "fresh-idle" ]]; then
    env_args+=(
      SCENARIO=idle
      DURATION="$FRESH_IDLE_DURATION"
    )
  elif [[ "$scenario_label" == "rare-requests" ]]; then
    env_args+=(
      SCENARIO=low-load
      RATE="$RARE_RATE"
      RATE_TIME_UNIT="$RARE_RATE_TIME_UNIT"
      DURATION="$RARE_REQUESTS_DURATION"
      POST_IDLE_SECONDS="$RARE_POST_IDLE_SECONDS"
    )
  else
    echo "Unknown scenario label: $scenario_label" >&2
    exit 1
  fi

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
  local profiles=("$@")
  local i=1
  for profile in "${profiles[@]}"; do
    run_one "$pass_name" "$i" "$profile" fresh-idle
    run_one "$pass_name" "$i" "$profile" rare-requests
    i=$((i + 1))
  done
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

write_report() {
  [[ -n "$REPORT_HTML" ]] || return 0
  log "write report: ${REPORT_HTML}"
  if [[ "$DRY_RUN" == true ]]; then
    printf '%q ' bash scripts/compare-benchmark-root.sh "$RESULTS_BASE" --html "$REPORT_HTML"
    printf '\n'
  else
    bash scripts/compare-benchmark-root.sh "$RESULTS_BASE" --html "$REPORT_HTML"
  fi
}

log "series: ${SERIES_ID}"
log "results base: ${RESULTS_BASE}"
if [[ -n "$CONFIG_FILE" ]]; then log "config: ${CONFIG_FILE}"; fi
log "fresh idle: ${FRESH_IDLE_DURATION}; rare requests: ${RARE_REQUESTS_DURATION} at ${RARE_RATE}/${RARE_RATE_TIME_UNIT}; post idle: ${RARE_POST_IDLE_SECONDS}s"
preflight
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
write_report
log "series complete: ${RESULTS_BASE}"
