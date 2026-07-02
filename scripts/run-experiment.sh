#!/usr/bin/env bash
set -euo pipefail

profile_file="${1:-}"
if [[ -z "$profile_file" || ! -f "$profile_file" ]]; then
  echo "Usage: $0 profiles/<profile>.env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$profile_file"
set +a

RATE="${RATE:-20}"
DURATION="${DURATION:-5m}"
ORDER_POOL="${ORDER_POOL:-10000}"
SEED_ORDERS="${SEED_ORDERS:-$ORDER_POOL}"
APP_PORT="${APP_PORT:-8080}"
RUN_PROFILE="${RUN_PROFILE:-$(basename "$profile_file" .env)}"
JVM_VARIANT="${JVM_VARIANT:-hotspot-liberica}"
JVM_PROFILE="${JVM_PROFILE:-baseline}"
GIT_SHA="${GIT_SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-${RUN_PROFILE}-rate${RATE}}"
RESULTS_ROOT="${RESULTS_ROOT:-results}"
RUN_RESULTS_DIR="$(pwd)/${RESULTS_ROOT}/${RUN_ID}"
BASE_URL="http://localhost:${APP_PORT}"

export RATE DURATION ORDER_POOL SEED_ORDERS APP_PORT RUN_PROFILE JVM_VARIANT JVM_PROFILE GIT_SHA RUN_ID RUN_RESULTS_DIR BASE_URL

mkdir -p "$RUN_RESULTS_DIR/app-logs"

python3 - <<'PY'
import json
import os
from pathlib import Path

result_dir = Path(os.environ["RUN_RESULTS_DIR"])
metadata = {
    "runId": os.environ.get("RUN_ID"),
    "runProfile": os.environ.get("RUN_PROFILE"),
    "jvmVariant": os.environ.get("JVM_VARIANT"),
    "jvmProfile": os.environ.get("JVM_PROFILE"),
    "runtimeImage": os.environ.get("RUNTIME_IMAGE"),
    "gitSha": os.environ.get("GIT_SHA"),
    "javaToolOptions": os.environ.get("JAVA_TOOL_OPTIONS"),
    "container": {
        "cpuLimit": os.environ.get("CONTAINER_CPU_LIMIT"),
        "memoryLimit": os.environ.get("CONTAINER_MEMORY_LIMIT"),
    },
    "workHelmReference": {
        "replicaCount": 3,
        "cpuRequest": os.environ.get("WORK_CPU_REQUEST"),
        "cpuLimit": os.environ.get("WORK_CPU_LIMIT"),
        "memoryRequest": os.environ.get("WORK_MEMORY_REQUEST"),
        "memoryLimit": os.environ.get("WORK_MEMORY_LIMIT"),
        "timezone": os.environ.get("WORK_TIMEZONE"),
        "port": 8080,
        "prometheusEnabled": True,
        "extraJavaOpts": os.environ.get("WORK_EXTRA_JAVA_OPTS"),
    },
    "load": {
        "scenario": "enterprise-flow",
        "rate": int(os.environ.get("RATE", "20")),
        "duration": os.environ.get("DURATION"),
        "orderPool": int(os.environ.get("ORDER_POOL", "10000")),
        "seedOrders": int(os.environ.get("SEED_ORDERS", os.environ.get("ORDER_POOL", "10000"))),
    },
}
(result_dir / "metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
PY

cleanup() {
  docker compose -f infra/docker-compose.yml down >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup

docker compose -f infra/docker-compose.yml up --build -d

healthy=false
for _ in $(seq 1 120); do
  if curl -fsS "${BASE_URL}/actuator/health" > "$RUN_RESULTS_DIR/health.json"; then
    healthy=true
    break
  fi
  sleep 1
done

if [[ "$healthy" != "true" ]]; then
  docker compose -f infra/docker-compose.yml logs --no-color sandbox-service > "$RUN_RESULTS_DIR/app.log" || true
  echo "Application health check failed" >&2
  exit 1
fi

curl -fsS "${BASE_URL}/run-info" | tee "$RUN_RESULTS_DIR/run-info.json" >/dev/null
curl -fsS "${BASE_URL}/actuator/prometheus" > "$RUN_RESULTS_DIR/prometheus-before.txt"
curl -fsS -X POST "${BASE_URL}/synthetic/runtime?iterations=20&payloadSize=100000" \
  | tee "$RUN_RESULTS_DIR/synthetic-runtime.json" >/dev/null

if command -v k6 >/dev/null 2>&1; then
  k6 run --summary-export "$RUN_RESULTS_DIR/k6-summary.json" load/k6/enterprise-flow.js \
    | tee "$RUN_RESULTS_DIR/k6.log"
else
  echo "k6 is not installed; skipping enterprise load" | tee "$RUN_RESULTS_DIR/k6.log"
fi

curl -fsS "${BASE_URL}/actuator/prometheus" > "$RUN_RESULTS_DIR/prometheus-after.txt"
docker compose -f infra/docker-compose.yml logs --no-color sandbox-service > "$RUN_RESULTS_DIR/app.log"
docker compose -f infra/docker-compose.yml ps > "$RUN_RESULTS_DIR/docker-compose-ps.txt"

container_id="$(docker compose -f infra/docker-compose.yml ps -q sandbox-service || true)"
if [[ -n "$container_id" ]]; then
  docker stats --no-stream "$container_id" > "$RUN_RESULTS_DIR/docker-stats.txt" || true
fi

if docker compose -f infra/docker-compose.yml exec -T sandbox-service sh -lc 'command -v jcmd >/dev/null 2>&1'; then
  docker compose -f infra/docker-compose.yml exec -T sandbox-service jcmd 1 VM.command_line > "$RUN_RESULTS_DIR/jcmd-vm-command-line.txt" || true
  docker compose -f infra/docker-compose.yml exec -T sandbox-service jcmd 1 VM.flags > "$RUN_RESULTS_DIR/jcmd-vm-flags.txt" || true
  docker compose -f infra/docker-compose.yml exec -T sandbox-service jcmd 1 VM.system_properties > "$RUN_RESULTS_DIR/jcmd-vm-system-properties.txt" || true
else
  echo "jcmd is not available in runtime image" > "$RUN_RESULTS_DIR/jcmd-not-available.txt"
fi

echo "Experiment results saved to $RUN_RESULTS_DIR"
