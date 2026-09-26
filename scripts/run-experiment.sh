#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
profile_file="${1:-}"
if [[ -z "$profile_file" || ! -f "$profile_file" ]]; then
  echo "Usage: $0 profiles/<profile>.env" >&2
  exit 1
fi
source scripts/profile-common.sh
load_experiment_profile "$profile_file"

TRAFFIC_PROFILE="${TRAFFIC_PROFILE:-normal}"
case "$TRAFFIC_PROFILE" in normal|faults) ;; *) echo 'Unknown TRAFFIC_PROFILE' >&2; exit 1 ;; esac
# Named traffic profiles are immutable within a series; runtime profiles remain overrideable.
set -a
source "profiles/traffic/${TRAFFIC_PROFILE}.env"
set +a
TRACE_SEED="${TRACE_SEED:-20260925}"
RATE_TIME_UNIT="${RATE_TIME_UNIT:-1s}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-300}"
POST_IDLE_SECONDS="${POST_IDLE_SECONDS:-1800}"
export TRAFFIC_PROFILE TRACE_SEED RATE_TIME_UNIT SLO_P95_MS SLO_P99_MS DRAIN_TIMEOUT_SECONDS POST_IDLE_SECONDS
SCENARIO="${SCENARIO:-load}"
case "$SCENARIO" in
  idle) RATE=0; SEED_ORDERS=0 ;;
  low-load) RATE="${RATE:-1}" ;;
  load) RATE="${RATE:-10}" ;;
  *) echo "Unknown SCENARIO: $SCENARIO" >&2; exit 1 ;;
esac
DURATION="${DURATION:-30m}"
DURATION_SECONDS="$(python3 scripts/experiment_support.py duration "$DURATION")"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"
ORDER_POOL="${ORDER_POOL:-10000}"
SEED_ORDERS="${SEED_ORDERS:-$ORDER_POOL}"
PREALLOCATED_VUS="${PREALLOCATED_VUS:-50}"
MAX_VUS="${MAX_VUS:-200}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.1}"
APP_PORT="${APP_PORT:-8080}"
RUN_PROFILE="${RUN_PROFILE:-$(basename "$profile_file" .env)}"
JVM_VARIANT="${JVM_VARIANT:-hotspot-liberica}"
JVM_PROFILE="${JVM_PROFILE:-baseline}"
RUNTIME_MODE="${RUNTIME_MODE:-jit}"
MEMORY_PROFILE="${MEMORY_PROFILE:-fixed-heap}"
RUNTIME_DOCKERFILE="${RUNTIME_DOCKERFILE:-Dockerfile}"
RUNTIME_IMAGE="${RUNTIME_IMAGE:-bellsoft/liberica-openjre-alpine:21.0.11-11}"
CONTAINER_CPU_LIMIT="${CONTAINER_CPU_LIMIT:-2}"
CONTAINER_MEMORY_LIMIT="${CONTAINER_MEMORY_LIMIT:-1g}"
WORK_CPU_LIMIT="${WORK_CPU_LIMIT:-2}"
GIT_SHA="${GIT_SHA:-$(git rev-parse HEAD)}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-${RUN_PROFILE}-${SCENARIO}-rate${RATE}-$$}"
[[ "$RUN_ID" =~ ^[a-zA-Z0-9_.-]+$ && "$RUN_ID" != . && "$RUN_ID" != .. ]] || { echo 'Invalid RUN_ID' >&2; exit 1; }
RESULTS_ROOT="${RESULTS_ROOT:-results/${SCENARIO}/${DURATION}}"
RUN_RESULTS_DIR="$(python3 -c 'from pathlib import Path; import sys; print((Path(sys.argv[1])/sys.argv[2]).resolve())' "$RESULTS_ROOT" "$RUN_ID")"
BASE_URL="http://localhost:${APP_PORT}"
# A fresh, isolated project prevents old MongoDB data / Kafka events contaminating idle.
COMPOSE_PROJECT_NAME="jvm-exp-$(date -u +%Y%m%d%H%M%S)-$$"
COLLECT_PROMETHEUS="${COLLECT_PROMETHEUS:-true}"
SYNTHETIC_WARMUP="${SYNTHETIC_WARMUP:-false}"
K6_TIME_SERIES="${K6_TIME_SERIES:-false}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"

if [[ "$SCENARIO" == idle && "$SYNTHETIC_WARMUP" != false ]]; then
  echo 'idle must not run synthetic warmup' >&2; exit 1
fi
if [[ "$RUNTIME_MODE" == native && -n "${JAVA_TOOL_OPTIONS:-}" ]]; then
  echo 'Native profile must not inherit JAVA_TOOL_OPTIONS; unset it first' >&2; exit 1
fi
export SCENARIO RATE DURATION INTERVAL_SECONDS ORDER_POOL SEED_ORDERS APP_PORT RUN_PROFILE JVM_VARIANT JVM_PROFILE
export RUNTIME_MODE MEMORY_PROFILE RUNTIME_DOCKERFILE RUNTIME_IMAGE CONTAINER_CPU_LIMIT CONTAINER_MEMORY_LIMIT WORK_CPU_LIMIT
export GIT_SHA RUN_ID RUN_RESULTS_DIR BASE_URL COMPOSE_PROJECT_NAME COLLECT_PROMETHEUS SYNTHETIC_WARMUP K6_TIME_SERIES
export PREALLOCATED_VUS MAX_VUS SLEEP_SECONDS
python3 - <<'PY'
import math, os
for name in ['INTERVAL_SECONDS', 'CONTAINER_CPU_LIMIT']:
    value = float(os.environ[name])
    if not math.isfinite(value) or value <= 0:
        raise SystemExit(f'{name} must be positive and finite')
if os.environ['SCENARIO'] != 'idle':
    for name in ['RATE', 'ORDER_POOL', 'SEED_ORDERS', 'PREALLOCATED_VUS', 'MAX_VUS']:
        if int(os.environ[name]) <= 0:
            raise SystemExit(f'{name} must be positive')
    if int(os.environ['SEED_ORDERS']) < int(os.environ['ORDER_POOL']):
        raise SystemExit('SEED_ORDERS must cover ORDER_POOL')
for name in ['POST_IDLE_SECONDS', 'DRAIN_TIMEOUT_SECONDS']:
    if int(os.environ[name]) < 0:
        raise SystemExit(f'{name} must be nonnegative')
for name in ['COLLECT_PROMETHEUS', 'SYNTHETIC_WARMUP', 'K6_TIME_SERIES']:
    if os.environ[name] not in ('true', 'false'):
        raise SystemExit(f'{name} must be true or false')
PY
for tool in docker curl python3; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }; done
if [[ "$SCENARIO" != idle ]]; then
  command -v k6 >/dev/null || { echo 'k6 is required for low-load/load; experiment was not started' >&2; exit 1; }
fi
docker compose version >/dev/null
[[ ! -e "$RUN_RESULTS_DIR" ]] || { echo "Results already exist: $RUN_RESULTS_DIR" >&2; exit 1; }
mkdir -p "$RUN_RESULTS_DIR/app-logs"
cp "$profile_file" "$RUN_RESULTS_DIR/profile.env"
python3 scripts/experiment_support.py init "$RUN_RESULTS_DIR/metadata.json"
python3 scripts/workload.py "$RUN_RESULTS_DIR"
TRACE_FILE="$RUN_RESULTS_DIR/workload.json"
export TRACE_FILE

compose() { docker compose -f infra/docker-compose.yml "$@"; }
collector_pid=''
workload_pid=''
compose_started=false
stop_collector() {
  if [[ -n "$collector_pid" ]]; then
    kill -TERM "$collector_pid" 2>/dev/null || true
    wait "$collector_pid"
    collector_pid=''
  fi
}
cleanup() {
  local code=$?
  trap - EXIT INT TERM
  if [[ -n "$workload_pid" ]]; then
    kill -TERM "$workload_pid" 2>/dev/null || true
    wait "$workload_pid" 2>/dev/null || true
  fi
  stop_collector || { [[ "$code" -ne 0 ]] || code=1; }
  if [[ "$compose_started" == true ]]; then
    compose logs --no-color > "$RUN_RESULTS_DIR/compose.log" 2>&1 || true
    compose logs --no-color sandbox-service > "$RUN_RESULTS_DIR/app.log" 2>&1 || true
    compose ps -a > "$RUN_RESULTS_DIR/docker-compose-ps.txt" 2>&1 || true
    # Only the newly generated, private project and its disposable data are removed.
    compose down -v > "$RUN_RESULTS_DIR/cleanup.log" 2>&1 || { [[ "$code" -ne 0 ]] || code=1; }
  fi
  python3 scripts/experiment_support.py finish "$RUN_RESULTS_DIR/metadata.json" "$code" || true
  python3 scripts/validate-run.py "$RUN_RESULTS_DIR" || { [[ "$code" -ne 0 ]] || code=2; }
  python3 scripts/phase-report.py "$RUN_RESULTS_DIR" || { [[ "$code" -ne 0 ]] || code=2; }
  echo "Experiment results saved to $RUN_RESULTS_DIR (exit $code)"
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
compose config > "$RUN_RESULTS_DIR/compose-config.yml"
docker version > "$RUN_RESULTS_DIR/docker-version.txt"
docker info > "$RUN_RESULTS_DIR/docker-info.txt"
git diff HEAD --binary > "$RUN_RESULTS_DIR/source.diff"
# Build before starting the application: build time is not startup time.
compose build sandbox-service > "$RUN_RESULTS_DIR/build.log" 2>&1
compose_started=true
compose up -d mongo kafka wiremock
# Wait for actual dependencies, so startup is not dominated by Kafka/Mongo initialization.
for attempt in $(seq 1 90); do
  if compose exec -T mongo mongosh --quiet --eval 'db.adminCommand({ping:1}).ok' >/dev/null 2>&1 \
    && compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list >/dev/null 2>&1 \
    && curl --max-time 2 -fsS http://localhost:8089/__admin/mappings >/dev/null; then break; fi
  [[ "$attempt" -lt 90 ]] || { echo 'Dependencies did not become ready' >&2; exit 1; }
  sleep 2
done
python3 scripts/phase.py "$RUN_RESULTS_DIR" startup
OBSERVATION_EPOCH="$(python3 -c 'import time; print(time.time())')"
export OBSERVATION_EPOCH
compose up -d --no-deps sandbox-service
container_id="$(compose ps -q sandbox-service)"
docker inspect "$container_id" > "$RUN_RESULTS_DIR/container-inspect.json"
OUT="$RUN_RESULTS_DIR/runtime-metrics.csv" bash scripts/collect-runtime-metrics.sh --container "$container_id" \
  > "$RUN_RESULTS_DIR/collector.log" 2>&1 &
collector_pid=$!
healthy=false
health_deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
while (( SECONDS < health_deadline )); do
  if curl --max-time 2 -fsS "${BASE_URL}/actuator/health/readiness" > "$RUN_RESULTS_DIR/health.json" 2>/dev/null \
    && python3 -c 'import json,sys; sys.exit(json.load(open(sys.argv[1])).get("status") != "UP")' "$RUN_RESULTS_DIR/health.json"; then
    healthy=true
    break
  fi
  sleep 0.25
done
[[ "$healthy" == true ]] || { echo 'Application readiness check failed' >&2; exit 1; }
python3 scripts/experiment_support.py ready "$RUN_RESULTS_DIR/metadata.json" "$RUN_RESULTS_DIR/container-inspect.json"
READY_EPOCH="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["readyEpoch"])' "$RUN_RESULTS_DIR/metadata.json")"
export READY_EPOCH
python3 scripts/phase.py "$RUN_RESULTS_DIR" preparation

curl --max-time 10 -fsS "${BASE_URL}/run-info" > "$RUN_RESULTS_DIR/run-info.json"
curl --max-time 10 -fsS "${BASE_URL}/actuator/prometheus" > "$RUN_RESULTS_DIR/prometheus-before.txt" || true
if [[ "$SYNTHETIC_WARMUP" == true ]]; then
  curl --max-time 60 -fsS -X POST "${BASE_URL}/synthetic/runtime?iterations=20&payloadSize=100000" > "$RUN_RESULTS_DIR/synthetic-runtime.json"
fi
if [[ "$SCENARIO" != idle ]]; then
  curl --max-time 300 -fsS -X POST "${BASE_URL}/orders/generate?count=${SEED_ORDERS}" > "$RUN_RESULTS_DIR/seed.json"
  python3 - "$RUN_RESULTS_DIR/seed.json" "$SEED_ORDERS" <<'CHECK_SEED'
import json,sys
s=json.load(open(sys.argv[1]))
assert s['saved'] == int(sys.argv[2]), s
CHECK_SEED
fi
curl --max-time 10 -fsS "${BASE_URL}/research/state" > "$RUN_RESULTS_DIR/state-before.json"
python3 scripts/phase.py "$RUN_RESULTS_DIR" "$SCENARIO"
if [[ "$SCENARIO" == idle ]]; then
  sleep "$DURATION_SECONDS" &
else
  set --
  if [[ "$K6_TIME_SERIES" == true ]]; then set -- --out "json=$RUN_RESULTS_DIR/k6-timeseries.json"; fi
  k6 run "$@" --summary-export "$RUN_RESULTS_DIR/k6-summary.json" load/k6/enterprise-flow.js \
    > "$RUN_RESULTS_DIR/k6.log" 2>&1 &
fi
workload_pid=$!
load_phase_finished=false
load_deadline=$((SECONDS + $(python3 -c 'import math,sys; print(math.ceil(float(sys.argv[1])))' "$DURATION_SECONDS")))
# Detect collector failures while the workload runs, including during idle.
while kill -0 "$workload_pid" 2>/dev/null; do
  if [[ "$SCENARIO" != idle && "$load_phase_finished" == false ]] && (( SECONDS >= load_deadline )); then
    python3 scripts/phase.py "$RUN_RESULTS_DIR" drain
    load_phase_finished=true
  fi
  kill -0 "$collector_pid" 2>/dev/null || { echo 'Metric collector stopped unexpectedly; see collector.log' >&2; exit 1; }
  sleep 1
done
workload_code=0
wait "$workload_pid" || workload_code=$?
workload_pid=''
if [[ "$SCENARIO" != idle ]]; then
  if [[ "$load_phase_finished" == false ]]; then python3 scripts/phase.py "$RUN_RESULTS_DIR" drain; fi
  drained=false
  drain_deadline=$((SECONDS + DRAIN_TIMEOUT_SECONDS))
  while (( SECONDS < drain_deadline )); do
    kill -0 "$collector_pid" 2>/dev/null || { echo 'Collector failed during drain' >&2; exit 1; }
    curl --max-time 10 -fsS "${BASE_URL}/research/state" > "$RUN_RESULTS_DIR/state-drain.json"
    if python3 scripts/check-drained.py "$RUN_RESULTS_DIR/state-drain.json"; then drained=true; break; fi
    sleep 2
  done
  [[ "$drained" == true ]] || { echo 'Background processing did not drain' >&2; exit 1; }
  python3 scripts/phase.py "$RUN_RESULTS_DIR" post-load-idle
  idle_deadline=$((SECONDS + POST_IDLE_SECONDS))
  while (( SECONDS < idle_deadline )); do
    kill -0 "$collector_pid" 2>/dev/null || { echo 'Collector failed during post-load idle' >&2; exit 1; }
    sleep 1
  done
fi
curl --max-time 10 -fsS "${BASE_URL}/research/state" > "$RUN_RESULTS_DIR/state-after.json"
compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --group jvm-research-sandbox --describe > "$RUN_RESULTS_DIR/kafka-lag.txt"
docker inspect "$container_id" > "$RUN_RESULTS_DIR/container-final.json"
stop_collector
python3 scripts/phase.py "$RUN_RESULTS_DIR" finished
[[ "$SCENARIO" == idle ]] || cat "$RUN_RESULTS_DIR/k6.log"
curl --max-time 10 -fsS "${BASE_URL}/actuator/prometheus" > "$RUN_RESULTS_DIR/prometheus-after.txt" || true
docker stats --no-stream "$container_id" > "$RUN_RESULTS_DIR/docker-stats.txt" || true
# HotSpot flags are not portable to OpenJ9 or Native Image. Micrometer stays universal where available.
if [[ "$RUNTIME_MODE" == jit ]] && compose exec -T sandbox-service sh -c 'command -v jcmd >/dev/null 2>&1'; then
  for diagnostic in VM.command_line VM.system_properties; do
    compose exec -T sandbox-service sh -c 'JAVA_TOOL_OPTIONS= jcmd 1 "$1"' sh "$diagnostic" \
      > "$RUN_RESULTS_DIR/jcmd-${diagnostic//./-}.txt" 2>&1 || true
  done
  if [[ "$JVM_VARIANT" != openj9 ]]; then
    compose exec -T sandbox-service sh -c 'JAVA_TOOL_OPTIONS= jcmd 1 VM.flags' > "$RUN_RESULTS_DIR/jcmd-VM-flags.txt" 2>&1 || true
  fi
else
  echo "jcmd unavailable or inapplicable: $RUNTIME_MODE" > "$RUN_RESULTS_DIR/jcmd-not-available.txt"
fi
exit "$workload_code"
