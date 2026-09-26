#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SERIES_ID="${SERIES_ID:-$(date -u +%Y%m%dT%H%M%SZ)-v1-idle-rare-1h}"
RESULTS_BASE="${RESULTS_BASE:-results/v1-idle-rare-1h/${SERIES_ID}}"
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

Each profile runs:
  - fresh idle: 1h
  - rare requests: 1h at 1 request / 10s, then 1h post-load idle

Environment:
  SERIES_ID      stable id for deterministic RUN_ID values
  RESULTS_BASE   root directory for this series
  DRY_RUN=true   print planned commands without running them
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
    ORDER_POOL=1000
    SEED_ORDERS=1000
    RESULTS_ROOT="$results_root"
    RUN_ID="$run_id"
  )

  if [[ "$scenario_label" == "fresh-idle" ]]; then
    env_args+=(
      SCENARIO=idle
      DURATION=1h
    )
  elif [[ "$scenario_label" == "rare-requests" ]]; then
    env_args+=(
      SCENARIO=low-load
      RATE=1
      RATE_TIME_UNIT=10s
      DURATION=1h
      POST_IDLE_SECONDS=3600
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

log "series: ${SERIES_ID}"
log "results base: ${RESULTS_BASE}"
preflight
run_pass pass1-forward "${profiles_forward[@]}"
run_pass pass2-reverse "${profiles_reverse[@]}"
log "series complete: ${RESULTS_BASE}"
