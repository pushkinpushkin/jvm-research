#!/usr/bin/env bash
set -euo pipefail

JVM_NAME="${JVM_NAME:-local}"
RESULTS_DIR="${RESULTS_DIR:-results/$JVM_NAME}"
JAVA_OPTS="${JAVA_OPTS:-}"

mkdir -p "$RESULTS_DIR"

./gradlew --no-daemon clean classes

java -version > "$RESULTS_DIR/java-version.txt" 2>&1 || true

echo "Running SandboxApp for JVM_NAME=$JVM_NAME"
# shellcheck disable=SC2086
java $JAVA_OPTS -cp build/classes/java/main dev.pushkin.jvmresearch.SandboxApp | tee "$RESULTS_DIR/app-run.log"
