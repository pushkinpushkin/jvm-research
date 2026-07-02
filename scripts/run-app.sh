#!/usr/bin/env bash
set -euo pipefail

JVM_NAME="${JVM_NAME:-local}"
RESULTS_DIR="${RESULTS_DIR:-results/$JVM_NAME}"
JAVA_OPTS="${JAVA_OPTS:-}"
SERVER_PORT="${SERVER_PORT:-8080}"

mkdir -p "$RESULTS_DIR"

./gradlew --no-daemon clean bootJar

java -version > "$RESULTS_DIR/java-version.txt" 2>&1 || true

echo "Running EnterpriseSandboxApplication for JVM_NAME=$JVM_NAME"
# shellcheck disable=SC2086
java $JAVA_OPTS -jar build/libs/*.jar > "$RESULTS_DIR/app-run.log" 2>&1 &
APP_PID=$!

cleanup() {
  kill "$APP_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null; then
    break
  fi
  sleep 1
done

curl -fsS -X POST "http://localhost:${SERVER_PORT}/synthetic/runtime?iterations=20&payloadSize=100000" \
  | tee "$RESULTS_DIR/synthetic-runtime.json"
