#!/usr/bin/env bash
set -euo pipefail
# Use the phase-aware runner for comparable measurements.
cd "$(dirname "$0")/.."
exec bash scripts/run-experiment.sh "${1:-profiles/work-hotspot-elastic.env}"
