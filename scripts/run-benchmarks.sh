#!/usr/bin/env bash
set -euo pipefail

JVM_NAME="${JVM_NAME:-local}"
RESULTS_DIR="${RESULTS_DIR:-results/$JVM_NAME}"

mkdir -p "$RESULTS_DIR"

echo "Running JMH benchmarks for JVM_NAME=$JVM_NAME"
echo "Results directory: $RESULTS_DIR"

./gradlew --no-daemon clean jmh

cp -f build/reports/jmh/results.json "$RESULTS_DIR/jmh-results.json"

java -version > "$RESULTS_DIR/java-version.txt" 2>&1 || true

echo "Done. Results saved to $RESULTS_DIR"
