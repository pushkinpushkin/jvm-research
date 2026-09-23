#!/usr/bin/env bash
set -euo pipefail
# Historical host-PID collector remains available, including the old numeric-PID interface.
if [[ "${1:-}" != "--container" ]]; then
  exec bash "$(dirname "$0")/collect-process-metrics.sh" "$@"
fi
if [[ -z "${2:-}" ]]; then
  echo "Usage: $0 --container <container-id> (OUT, INTERVAL_SECONDS, BASE_URL)" >&2
  exit 1
fi
exec python3 "$(dirname "$0")/collect-container-metrics.py" "$2"
