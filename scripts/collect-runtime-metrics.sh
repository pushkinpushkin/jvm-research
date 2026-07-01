#!/usr/bin/env bash
set -euo pipefail

PID="${1:-}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-1}"
SAMPLES="${SAMPLES:-30}"
OUT="${OUT:-runtime-metrics.csv}"

if [[ -z "$PID" ]]; then
  echo "Usage: $0 <pid>"
  exit 1
fi

echo "timestamp,pid,rss_kb,vsz_kb,cpu_percent,mem_percent,threads" > "$OUT"

for _ in $(seq 1 "$SAMPLES"); do
  if ! ps -p "$PID" > /dev/null; then
    echo "Process $PID is not running"
    exit 0
  fi

  ps -p "$PID" -o pid=,rss=,vsz=,%cpu=,%mem=,nlwp= | awk -v ts="$(date -Iseconds)" '{print ts "," $1 "," $2 "," $3 "," $4 "," $5 "," $6}' >> "$OUT"
  sleep "$INTERVAL_SECONDS"
done

echo "Metrics saved to $OUT"
